package com.tibiabot
package tibiadata

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.tibiabot.tibiadata.response._
import org.apache.commons.text.StringEscapeUtils
import spray.json.{DefaultJsonProtocol, JsObject, JsString, JsValue, RootJsonFormat}

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val strFormat: RootJsonFormat[String] = new RootJsonFormat[String] {
    override def write(obj: String): JsValue = new JsString(obj)

    override def read(json: JsValue): String = StringEscapeUtils.unescapeHtml4(JsonConvertNoCustomImplicits.convert(json))
  }

  implicit val informationFormat: RootJsonFormat[Information] = jsonFormat2(Information)

  implicit val onlinePlayersFormat: RootJsonFormat[OnlinePlayers] = jsonFormat3(OnlinePlayers)
  implicit val worldFormat: RootJsonFormat[World] = jsonFormat16(World)
  implicit val worldsFormat: RootJsonFormat[Worlds] = jsonFormat1(Worlds)
  implicit val worldResponseFormat: RootJsonFormat[WorldResponse] = jsonFormat2(WorldResponse)

  implicit val housesFormat: RootJsonFormat[Houses] = jsonFormat4(Houses)
  implicit val guildFormat: RootJsonFormat[Guild] = jsonFormat2(Guild)
  // required because TibiaData returns an empty object instead of null when a player has no guild
  implicit val optGuildFormat: RootJsonFormat[Option[Guild]] = new RootJsonFormat[Option[Guild]] {
    override def read(json: JsValue): Option[Guild] = json match {
      case JsObject.empty => None
      case j => Some(j.convertTo[Guild])
    }

    override def write(obj: Option[Guild]): JsValue = ???
  }
  implicit val characterFormat: RootJsonFormat[response.Character] = jsonFormat16(response.Character)
  implicit val killersFormat: RootJsonFormat[Killers] = jsonFormat4(Killers)
  implicit val deathsFormat: RootJsonFormat[Deaths] = jsonFormat5(Deaths)
  implicit val accountInformationFormat: RootJsonFormat[AccountInformation] = jsonFormat3(AccountInformation)
  // required because TibiaData returns an empty object instead of null when a player has no account info
  implicit val optAccountInformationFormat: RootJsonFormat[Option[AccountInformation]] = new RootJsonFormat[Option[AccountInformation]] {
    override def read(json: JsValue): Option[AccountInformation] = json match {
      case JsObject.empty => None
      case j => Some(j.convertTo[AccountInformation])
    }

    override def write(obj: Option[AccountInformation]): JsValue = ???
  }
  implicit val charactersFormat: RootJsonFormat[Characters] = jsonFormat3(Characters)
  implicit val characterResponseFormat: RootJsonFormat[CharacterResponse] = jsonFormat2(CharacterResponse)

}

// This is needed because you can't just call json.convertTo[String] inside strFormat above because you get a stack overflow because it calls back on itself
object JsonConvertNoCustomImplicits extends SprayJsonSupport with DefaultJsonProtocol {
  def convert(json: JsValue): String = json.convertTo[String]
}
