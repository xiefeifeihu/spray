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

import spray.http.HttpMethods

class MethodDirectivesSpec extends RoutingSpec {

  "get | put" should {
    val getOrPut = (get | put) { completeOk }

    "block POST requests" in {
      Post() ~> getOrPut ~> check { handled === false }
    }
    "let GET requests pass" in {
      Get() ~> getOrPut ~> check { response === Ok }
    }
    "let PUT requests pass" in {
      Put() ~> getOrPut ~> check { response === Ok }
    }
  }

  "two failed `get` directives" should {
    "only result in a single Rejection" in {
      Put() ~> {
        get { completeOk } ~
          get { completeOk }
      } ~> check {
        rejections === List(MethodRejection(HttpMethods.GET))
      }
    }
  }

  "MethodRejections" should {
    "be cancelled by a successful match" in {
      "if the match happens after the rejection" in {
        Put() ~> {
          get { completeOk } ~
            put { reject(RequestEntityExpectedRejection) }
        } ~> check {
          rejections === List(RequestEntityExpectedRejection)
        }
      }
      "if the match happens after the rejection (example 2)" in {
        Put() ~> {
          (get & complete)(Ok) ~
            (put & reject(RequestEntityExpectedRejection))
        } ~> check {
          rejections === List(RequestEntityExpectedRejection)
        }
      }
      "if the match happens before the rejection" in {
        Put() ~> {
          put { reject(RequestEntityExpectedRejection) } ~
            get { completeOk }
        } ~> check {
          rejections === List(RequestEntityExpectedRejection)
        }
      }
      "if the match happens before the rejection (example 2)" in {
        Put() ~> {
          (put & reject(RequestEntityExpectedRejection)) ~
            (get & complete)(Ok)
        } ~> check {
          rejections === List(RequestEntityExpectedRejection)
        }
      }
    }
  }

}