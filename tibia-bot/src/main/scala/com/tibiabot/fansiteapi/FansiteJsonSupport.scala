package com.tibiabot.fansiteapi

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.tibiabot.fansiteapi.response._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/** Decoders for the fansite API's character payload.
 *
 *  Separate from [[com.tibiabot.tibiadata.JsonSupport]] rather than folded into
 *  it, because that trait rebinds the String format to unescape HTML entities —
 *  a TibiaData-specific quirk (it serves scraped tibia.com markup) that must
 *  not be applied here. This API returns already-decoded text, so running it
 *  through `unescapeHtml4` would corrupt any name or comment legitimately
 *  containing an entity-like sequence.
 *
 *  Read-only on purpose: nothing re-serialises a fansite payload. The bot's
 *  Redis publishing round-trips the mapped `CharacterResponse` instead, which
 *  keeps one wire format in the cache regardless of which upstream produced it. */
trait FansiteJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val fansiteGameInformationFormat: RootJsonFormat[FansiteGameInformation] = jsonFormat18(FansiteGameInformation)
  implicit val fansiteMurdererFormat: RootJsonFormat[FansiteMurderer] = jsonFormat5(FansiteMurderer)
  implicit val fansiteDeathFormat: RootJsonFormat[FansiteDeath] = jsonFormat3(FansiteDeath)
  implicit val fansiteDeathsFormat: RootJsonFormat[FansiteDeaths] = jsonFormat2(FansiteDeaths)
  implicit val fansiteCharacterResponseFormat: RootJsonFormat[FansiteCharacterResponse] = jsonFormat2(FansiteCharacterResponse)
}
