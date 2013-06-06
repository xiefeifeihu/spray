/*
 * Copyright (C) 2011-2013 spray.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.routing
package directives

import shapeless._
import akka.dispatch.{ ExecutionContext, Future }
import akka.util.NonFatal
import spray.httpx.marshalling.Marshaller

trait FutureDirectives {

  /**
   * "Unwraps" a ``Future[T]`` and runs it's inner route after future
   * completion with the future's value as an extraction of type ``Try[T]``.
   */
  def onComplete[T](magnet: OnCompleteFutureMagnet[T]): Directive1[Either[Throwable, T]] = magnet

  /**
   * "Unwraps" a ``Future[T]`` and runs it's inner route after future
   * completion with the future's value as an extraction of type ``T``.
   * If the future fails its failure throwable is bubbled up to the nearest
   * ExceptionHandler.
   * If type ``T`` is already an HList it is directly expanded into the respective
   * number of extractions.
   */
  def onSuccess(magnet: OnSuccessFutureMagnet): Directive[magnet.Out] = magnet.get

  /**
   * "Unwraps" a ``Future[T]`` and runs it's inner route when the future has failed
   * with the future's failure exception as an extraction of type ``Throwable``.
   * If the future succeeds the request is completed using the values marshaller
   * (This directive therefore requires a marshaller for the futures type to be
   * implicitly available.)
   */
  def onFailure(magnet: OnFailureFutureMagnet): Directive1[Throwable] = magnet
}

object FutureDirectives extends FutureDirectives

trait OnCompleteFutureMagnet[T] extends Directive1[Either[Throwable, T]]

object OnCompleteFutureMagnet {
  implicit def apply[T](future: Future[T])(implicit ec: ExecutionContext) =
    new OnCompleteFutureMagnet[T] {
      def happly(f: (Either[Throwable, T] :: HNil) ⇒ Route) = ctx ⇒
        try future.onComplete(t ⇒ f(t :: HNil)(ctx))
        catch { case NonFatal(error) ⇒ ctx.failWith(error) }
    }
}

trait OnSuccessFutureMagnet {
  type Out <: HList
  def get: Directive[Out]
}

object OnSuccessFutureMagnet {
  implicit def apply[T](future: Future[T])(implicit hl: HListable[T], ec: ExecutionContext) =
    new Directive[hl.Out] with OnSuccessFutureMagnet {
      type Out = hl.Out
      def get = this
      def happly(f: Out ⇒ Route) = ctx ⇒ future.onComplete {
        case Right(t) ⇒
          try f(hl(t))(ctx)
          catch { case NonFatal(error) ⇒ ctx.failWith(error) }
        case Left(error) ⇒ ctx.failWith(error)
      }
    }
}

trait OnFailureFutureMagnet extends Directive1[Throwable]

object OnFailureFutureMagnet {
  implicit def apply[T](future: Future[T])(implicit m: Marshaller[T], ec: ExecutionContext) =
    new OnFailureFutureMagnet {
      def happly(f: (Throwable :: HNil) ⇒ Route) = ctx ⇒ future.onComplete {
        case Right(t) ⇒ ctx.complete(t)
        case Left(error) ⇒
          try f(error :: HNil)(ctx)
          catch { case NonFatal(err) ⇒ ctx.failWith(err) }
      }
    }
}
