/*
 * Copyright (C) 2011-2012 spray.io
 * Based on code copyright (C) 2010-2011 by the BlueEyes Web Framework Team (http://github.com/jdegoes/blueeyes)
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

package spray.http

import java.nio.charset.Charset

sealed abstract class HttpCharsetRange extends Renderable {
  def value: String
  def matches(charset: HttpCharset): Boolean
}

case class HttpCharset private[http] (value: String)(val aliases: String*)
    extends HttpCharsetRange with LazyValueBytesRenderable {

  @transient private[this] var _nioCharset: Charset = Charset.forName(value)
  def nioCharset: Charset = _nioCharset

  private def readObject(in: java.io.ObjectInputStream): Unit = {
    in.defaultReadObject()
    _nioCharset = Charset.forName(value)
  }

  def matches(charset: HttpCharset) = this == charset
}

object HttpCharset {
  def custom(value: String, aliases: String*): Option[HttpCharset] =
    try Some(HttpCharset(value)(aliases: _*))
    catch {
      case e: java.nio.charset.UnsupportedCharsetException ⇒ None
    }
}

// see http://www.iana.org/assignments/character-sets
object HttpCharsets extends ObjectRegistry[String, HttpCharset] {

  def register(charset: HttpCharset): HttpCharset = {
    charset.aliases.foreach(alias ⇒ register(alias.toLowerCase, charset))
    register(charset.value.toLowerCase, charset)
  }

  case object `*` extends HttpCharsetRange with SingletonValueRenderable {
    def matches(charset: HttpCharset) = true
  }

  private def register(value: String)(aliases: String*): HttpCharset =
    register(HttpCharset(value)(aliases: _*))

  // format: OFF
  val `US-ASCII`     = register("US-ASCII")("iso-ir-6", "ANSI_X3.4-1986", "ISO_646.irv:1991", "ASCII", "ISO646-US", "us", "IBM367", "cp367", "csASCII")
  val `ISO-8859-1`   = register("ISO-8859-1")("iso-ir-100", "ISO_8859-1", "latin1", "l1", "IBM819", "CP819", "csISOLatin1")
  val `ISO-8859-2`   = register("ISO-8859-2")("iso-ir-101", "ISO_8859-2", "latin2", "l2", "csISOLatin2")
  val `ISO-8859-3`   = register("ISO-8859-3")("iso-ir-109", "ISO_8859-3", "latin3", "l3", "csISOLatin3")
  val `ISO-8859-4`   = register("ISO-8859-4")("iso-ir-110", "ISO_8859-4", "latin4", "l4", "csISOLatin4")
  val `ISO-8859-5`   = register("ISO-8859-5")("iso-ir-144", "ISO_8859-5", "cyrillic", "csISOLatinCyrillic")
  val `ISO-8859-6`   = register("ISO-8859-6")("iso-ir-127", "ISO_8859-6", "ECMA-114", "ASMO-708", "arabic", "csISOLatinArabic")
  val `ISO-8859-7`   = register("ISO-8859-7")("iso-ir-126", "ISO_8859-7", "ELOT_928", "ECMA-118", "greek", "greek8", "csISOLatinGreek")
  val `ISO-8859-8`   = register("ISO-8859-8")("iso-ir-138", "ISO_8859-8", "hebrew", "csISOLatinHebrew")
  val `ISO-8859-9`   = register("ISO-8859-9")("iso-ir-148", "ISO_8859-9", "latin5", "l5", "csISOLatin5")
  val `ISO-8859-10`  = register("ISO-8859-1")("iso-ir-157", "l6", "ISO_8859-10", "csISOLatin6", "latin6")
  val `UTF-8`        = register("UTF-8")("UTF8")
  val `UTF-16`       = register("UTF-16")("UTF16")
  val `UTF-16BE`     = register("UTF-16BE")()
  val `UTF-16LE`     = register("UTF-16LE")()
  val `UTF-32`       = register("UTF-32")("UTF32")
  val `UTF-32BE`     = register("UTF-32BE")()
  val `UTF-32LE`     = register("UTF-32LE")()
  val `windows-1250` = register("windows-1250")("cp1250", "cp5346")
  val `windows-1251` = register("windows-1251")("cp1251", "cp5347")
  val `windows-1252` = register("windows-1252")("cp1252", "cp5348")
  val `windows-1253` = register("windows-1253")("cp1253", "cp5349")
  val `windows-1254` = register("windows-1254")("cp1254", "cp5350")
  val `windows-1257` = register("windows-1257")("cp1257", "cp5353")
  // format: ON
}
