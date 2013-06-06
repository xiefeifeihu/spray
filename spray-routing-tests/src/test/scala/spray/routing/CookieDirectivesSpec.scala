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

import spray.http._
import HttpHeaders.Cookie

class CookieDirectivesSpec extends RoutingSpec {

  "The 'cookie' directive" should {
    "extract the respectively named cookie" in {
      Get() ~> addHeader(Cookie(HttpCookie("fancy", "pants"))) ~> {
        cookie("fancy") { echoComplete }
      } ~> check { entityAs[String] === "fancy=pants" }
    }
    "reject the request if the cookie is not present" in {
      Get() ~> {
        cookie("fancy") { echoComplete }
      } ~> check { rejection === MissingCookieRejection("fancy") }
    }
    "properly pass through inner rejections" in {
      Get() ~> addHeader(Cookie(HttpCookie("fancy", "pants"))) ~> {
        cookie("fancy") { c ⇒ reject(ValidationRejection("Dont like " + c.content)) }
      } ~> check { rejection === ValidationRejection("Dont like pants") }
    }
  }

  "The 'deleteCookie' directive" should {
    "add a respective Set-Cookie headers to successful responses" in {
      Get() ~> {
        deleteCookie("myCookie", "test.com") { completeOk }
      } ~> check {
        response.toString === "HttpResponse(200 OK,EmptyEntity,List(Set-Cookie: myCookie=deleted; " +
          "Expires=Wed, 01 Jan 1800 00:00:00 GMT; Domain=test.com),HTTP/1.1)"
      }
    }

    "support deleting multiple cookies at a time" in {
      Get() ~> {
        deleteCookie(HttpCookie("myCookie", "test.com"), HttpCookie("myCookie2", "foobar.com")) { completeOk }
      } ~> check {
        response.toString === "HttpResponse(200 OK,EmptyEntity,List(" +
          "Set-Cookie: myCookie=deleted; Expires=Wed, 01 Jan 1800 00:00:00 GMT, " +
          "Set-Cookie: myCookie2=deleted; Expires=Wed, 01 Jan 1800 00:00:00 GMT" +
          "),HTTP/1.1)"
      }
    }
  }

  "The 'optionalCookie' directive" should {
    "produce a `Some(cookie)` extraction if the cookie is present" in {
      Get() ~> Cookie(HttpCookie("abc", "123")) ~> {
        optionalCookie("abc") { echoComplete }
      } ~> check { entityAs[String] === "Some(abc=123)" }
    }
    "produce a `None` extraction if the cookie is not present" in {
      Get() ~> optionalCookie("abc") { echoComplete } ~> check { entityAs[String] === "None" }
    }
    "let rejections from its inner route pass through" in {
      Get() ~> {
        optionalCookie("test-cookie") { _ ⇒
          validate(false, "ouch") { completeOk }
        }
      } ~> check { rejection === ValidationRejection("ouch") }
    }
  }

  "The 'setCookie' directive" should {
    "add a respective Set-Cookie headers to successful responses" in {
      Get() ~> {
        setCookie(HttpCookie("myCookie", "test.com")) { completeOk }
      } ~> check {
        response.toString === "HttpResponse(200 OK,EmptyEntity,List(Set-Cookie: myCookie=test.com),HTTP/1.1)"
      }
    }

    "support setting multiple cookies at a time" in {
      Get() ~> {
        setCookie(HttpCookie("myCookie", "test.com"), HttpCookie("myCookie2", "foobar.com")) { completeOk }
      } ~> check {
        response.toString === "HttpResponse(200 OK,EmptyEntity,List(" +
          "Set-Cookie: myCookie=test.com, " +
          "Set-Cookie: myCookie2=foobar.com" +
          "),HTTP/1.1)"
      }
    }
  }
}
