/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.channels.SocketChannel
import scala.collection.immutable
import akka.actor.ActorRef
import akka.io.Inet.SocketOption
import akka.io.SelectionHandler.ChannelRegistered

/**
 * An actor handling the connection state machine for an incoming, already connected
 * SocketChannel.
 *
 * INTERNAL API
 */
private[io] class TcpIncomingConnection(_channel: SocketChannel,
                                        _tcp: TcpExt,
                                        handler: ActorRef,
                                        options: immutable.Traversable[SocketOption])
    extends TcpConnection(_channel, _tcp, initialOps = 0) {

  context.watch(handler) // sign death pact

  context.parent ! registration

  def receive = {
    case ChannelRegistered ⇒ completeConnect(handler, options)
  }
}
