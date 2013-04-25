/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.lang.Runnable
import java.nio.channels.spi.{ AbstractSelector, SelectorProvider }
import java.nio.channels._
import java.nio.channels.SelectionKey._
import java.util.concurrent.atomic.AtomicBoolean
import com.typesafe.config.Config
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.duration.Duration
import akka.io.IO.HasFailureMessage
import akka.actor._

abstract class SelectionHandlerSettings(config: Config) {
  import config._

  val MaxChannels = getString("max-channels") match {
    case "unlimited" ⇒ -1
    case _           ⇒ getInt("max-channels")
  }
  val SelectTimeout = getString("select-timeout") match {
    case "infinite" ⇒ Duration.Inf
    case x          ⇒ Duration(x)
  }
  val SelectorAssociationRetries = getInt("selector-association-retries")

  val SelectorDispatcher = getString("selector-dispatcher")
  val WorkerDispatcher = getString("worker-dispatcher")
  val TraceLogging = getBoolean("trace-logging")

  require(MaxChannels == -1 || MaxChannels > 0, "max-channels must be > 0 or 'unlimited'")
  require(SelectTimeout >= Duration.Zero, "select-timeout must not be negative")
  require(SelectorAssociationRetries >= 0, "selector-association-retries must be >= 0")

  def MaxChannelsPerSelector: Int

}

private[io] object SelectionHandler {

  case class WorkerForCommand(apiCommand: HasFailureMessage, commander: ActorRef, childProps: Props)

  case object ChannelRegistered
  case class Retry(command: WorkerForCommand, retriesLeft: Int) { require(retriesLeft >= 0) }

  case object ChannelConnectable
  case object ChannelAcceptable
  case object ChannelReadable
  case object ChannelWritable

  final class ConnectionRegistration(val channel: SelectableChannel, val channelActor: ActorRef, initialOps: Int) {
    @volatile private[this] var _key: SelectionKey = _
    private[this] var _ops: Int = initialOps
    private[this] val currentlySettingInterest = new AtomicBoolean // TODO: remove (use AtomicFieldUpdater instead)

    private[SelectionHandler] def registerWith(selector: AbstractSelector): Unit =
      _key = channel.register(selector, initialOps, this)

    @tailrec private[SelectionHandler] def enableInterestWithoutWakeup(ops: Int): SelectionKey =
      if (currentlySettingInterest.getAndSet(true))
        enableInterestWithoutWakeup(ops)
      else
        try setInterest(_ops | ops)
        finally currentlySettingInterest.set(false)

    @tailrec private[SelectionHandler] def disableInterestWithoutWakeup(ops: Int): SelectionKey =
      if (currentlySettingInterest.getAndSet(true))
        disableInterestWithoutWakeup(ops)
      else
        try setInterest(_ops & ~ops)
        finally currentlySettingInterest.set(false)

    private def setInterest(ops: Int): SelectionKey = {
      _ops = ops
      val key = _key // only read the volatile field once
      key.interestOps(ops)
      key
    }

    def enableInterest(ops: Int): Unit = enableInterestWithoutWakeup(ops).selector.wakeup()
    def disableInterest(ops: Int): Unit = disableInterestWithoutWakeup(ops).selector.wakeup()
  }
}

private[io] class SelectionHandler(manager: ActorRef, settings: SelectionHandlerSettings) extends Actor with ActorLogging {
  import SelectionHandler._
  import settings._

  var connectionCount = 0
  val sequenceNumber = Iterator.from(0)
  val selectorManagementDispatcher = context.system.dispatchers.lookup(SelectorDispatcher)
  val selector = SelectorProvider.provider.openSelector
  final val OP_READ_AND_WRITE = OP_READ | OP_WRITE // compile-time constant

  def receive: Receive = {
    case cmd: WorkerForCommand ⇒
      withCapacityProtection(cmd, SelectorAssociationRetries) { spawnChild(cmd.childProps) }

    case reg: ConnectionRegistration ⇒
      selectorManagementDispatcher.execute(register(reg))
      selector.wakeup()

    case Retry(WorkerForCommand(cmd, commander, _), 0) ⇒
      commander ! cmd.failureMessage

    case Retry(cmd, retriesLeft) ⇒
      withCapacityProtection(cmd, retriesLeft) { spawnChild(cmd.childProps) }

    case Terminated(child) ⇒ connectionCount -= 1
  }

  override def postStop() {
    try {
      try {
        val iterator = selector.keys.iterator
        while (iterator.hasNext) {
          val key = iterator.next()
          try key.channel.close()
          catch {
            case NonFatal(e) ⇒ log.error(e, "Error closing channel")
          }
        }
      } finally selector.close()
    } catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing selector")
    }
  }

  // we can never recover from failures of a connection or listener child
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def withCapacityProtection(cmd: WorkerForCommand, retriesLeft: Int)(body: ⇒ Unit): Unit = {
    if (TraceLogging) log.debug("Executing [{}]", cmd)
    if (MaxChannelsPerSelector == -1 || context.children.size < MaxChannelsPerSelector) {
      body
    } else {
      log.warning("Rejecting [{}] with [{}] retries left, retrying...", cmd, retriesLeft)
      context.parent forward Retry(cmd, retriesLeft - 1)
    }
  }

  def spawnChild(props: Props): ActorRef = {
    val child = context.actorOf(props.withDispatcher(WorkerDispatcher), sequenceNumber.next().toString)
    if (MaxChannelsPerSelector > 0) {
      context.watch(child) // we don't need to watch the child if we don't have to "uncount" connections
      connectionCount += 1
    }
    child
  }

  //////////////// Management Tasks scheduled via the selectorManagementDispatcher /////////////

  def register(reg: ConnectionRegistration): Task =
    new Task {
      def tryRun() {
        reg.registerWith(selector)
        reg.channelActor ! ChannelRegistered
      }
    }

  val select = new Task {
    val doSelect: () ⇒ Int =
      SelectTimeout match {
        case Duration.Zero ⇒ () ⇒ selector.selectNow()
        case Duration.Inf  ⇒ () ⇒ selector.select()
        case x             ⇒ val millis = x.toMillis; () ⇒ selector.select(millis)
      }
    def tryRun() {
      if (doSelect() > 0) {
        val keys = selector.selectedKeys
        val iterator = keys.iterator()
        while (iterator.hasNext) {
          val key = iterator.next
          if (key.isValid) {
            try {
              // Cache because the performance implications of calling this on different platforms are not clear
              val readyOps = key.readyOps()
              val reg = key.attachment.asInstanceOf[ConnectionRegistration]
              reg.disableInterestWithoutWakeup(readyOps) // prevent immediate reselection by always clearing
              readyOps match {
                case OP_READ                   ⇒ reg.channelActor ! ChannelReadable
                case OP_WRITE                  ⇒ reg.channelActor ! ChannelWritable
                case OP_READ_AND_WRITE         ⇒ reg.channelActor ! ChannelWritable; reg.channelActor ! ChannelReadable
                case x if (x & OP_ACCEPT) > 0  ⇒ reg.channelActor ! ChannelAcceptable
                case x if (x & OP_CONNECT) > 0 ⇒ reg.channelActor ! ChannelConnectable
                case x                         ⇒ log.warning("Invalid readyOps: [{}]", x)
              }
            } catch {
              case _: CancelledKeyException ⇒
              // can be ignored because this exception is triggered when the key becomes invalid
              // because `channel.close()` in `TcpConnection.postStop` is called from another thread
            }
          }
        }
        keys.clear() // we need to remove the selected keys from the set, otherwise they remain selected
      }
      selectorManagementDispatcher.execute(this) // re-schedules select behind all currently queued tasks
    }
  }

  selectorManagementDispatcher.execute(select) // start selection "loop"

  // FIXME: Add possibility to signal failure of task to someone
  abstract class Task extends Runnable {
    def tryRun()
    def run() {
      try tryRun()
      catch {
        case _: ClosedSelectorException ⇒ // ok, expected during shutdown
        case NonFatal(e)                ⇒ log.error(e, "Error during selector management task: [{}]", e)
      }
    }
  }
}