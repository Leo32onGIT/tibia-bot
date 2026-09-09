package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

/** The character-level traded flag, as TibiaData sends it.
 *
 *  Marked `omitempty` upstream, so the key is simply absent for the great
 *  majority of characters — reading that as anything but "not traded" would be
 *  wrong, and failing to parse the response at all would be worse.
 *
 *  The other half — that the flag survives CipSoft's fansite API winning the
 *  race against TibiaData — is pinned in CharacterMappingSpec, where the mapping
 *  lives.
 *
 *  Built from the recorded live response rather than hand-written JSON, so these
 *  keep testing the shape the API actually sends.
 */
class TradedFlagSpec extends AnyFunSuite with Matchers with JsonSupport {

  private def liveResponse: JsObject = {
    val is = getClass.getResourceAsStream("/tibiadata/character.json")
    require(is != null, "missing fixture /tibiadata/character.json")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString.parseJson.asJsObject
    finally is.close()
  }

  /** The recorded response with `traded` set on the character, or removed. */
  private def withTraded(value: Option[Boolean]): CharacterResponse = {
    val root = liveResponse
    val sheet = root.fields("character").asJsObject
    val character = sheet.fields("character").asJsObject
    val updated = value match {
      case Some(flag) => JsObject(character.fields + ("traded" -> JsBoolean(flag)))
      case None       => JsObject(character.fields - "traded")
    }
    JsObject(root.fields + ("character" ->
      JsObject(sheet.fields + ("character" -> updated)))).convertTo[CharacterResponse]
  }

  test("an absent traded key parses, and means not traded") {
    val parsed = withTraded(None)
    parsed.character.character.traded shouldBe None
    parsed.character.character.traded.getOrElse(false) shouldBe false
  }

  test("traded true is parsed") {
    withTraded(Some(true)).character.character.traded shouldBe Some(true)
  }

  /** Told apart from absent on purpose. Both mean not traded to a caller, but
   *  conflating them in the parse would hide the key going missing upstream. */
  test("traded false is parsed as false rather than as absent") {
    withTraded(Some(false)).character.character.traded shouldBe Some(false)
  }

  test("the rest of the recorded response still parses with the field added") {
    val parsed = withTraded(None)
    parsed.character.character.name should not be empty
    parsed.character.character.world should not be empty
  }

  /** The deletion date, the other `omitempty` field on this endpoint.
   *
   *  Positive evidence that a character is on its way out, and worth far more
   *  than inferring it from a name that stopped resolving — which a rename does
   *  just as well.
   */
  private def withDeletionDate(value: Option[String]): CharacterResponse = {
    val root = liveResponse
    val sheet = root.fields("character").asJsObject
    val character = sheet.fields("character").asJsObject
    val updated = value match {
      case Some(date) => JsObject(character.fields + ("deletion_date" -> JsString(date)))
      case None       => JsObject(character.fields - "deletion_date")
    }
    JsObject(root.fields + ("character" ->
      JsObject(sheet.fields + ("character" -> updated)))).convertTo[CharacterResponse]
  }

  test("an absent deletion date parses, and means not scheduled") {
    withDeletionDate(None).character.character.deletion_date shouldBe None
  }

  test("a deletion date is parsed as sent") {
    withDeletionDate(Some("2026-10-01T00:00:00Z"))
      .character.character.deletion_date shouldBe Some("2026-10-01T00:00:00Z")
  }
}
