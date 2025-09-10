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

  implicit val apiFormat: RootJsonFormat[Api] = jsonFormat3(Api)
  implicit val statusFormat: RootJsonFormat[Status] = jsonFormat1(Status)
  implicit val informationFormat: RootJsonFormat[Information] = jsonFormat2(Information)

  implicit val onlinePlayersFormat: RootJsonFormat[OnlinePlayers] = jsonFormat3(OnlinePlayers)
  implicit val worldFormat: RootJsonFormat[World] = jsonFormat16(World)
  implicit val worldResponseFormat: RootJsonFormat[WorldResponse] = jsonFormat2(WorldResponse)
  
  implicit val regularWorldFormat: RootJsonFormat[RegularWorld] = jsonFormat11(RegularWorld)
  implicit val worldsFormat: RootJsonFormat[Worlds] = jsonFormat4(Worlds)
  implicit val worldsResponseFormat: RootJsonFormat[WorldsResponse] = jsonFormat2(WorldsResponse)

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

  implicit val charactersFormat: RootJsonFormat[CharacterSheet] = jsonFormat3(CharacterSheet)
  implicit val characterResponseFormat: RootJsonFormat[CharacterResponse] = jsonFormat2(CharacterResponse)

  implicit val invitesFormat: RootJsonFormat[Invites] = jsonFormat2(Invites)
  implicit val membersFormat: RootJsonFormat[Members] = jsonFormat7(Members)
  implicit val guildHallFormat: RootJsonFormat[GuildHalls] = jsonFormat3(GuildHalls)
  implicit val guildDataFormat: RootJsonFormat[GuildData] = jsonFormat18(GuildData)
  implicit val guildResponseFormat: RootJsonFormat[GuildResponse] = jsonFormat2(GuildResponse)

  implicit val boostableBossListFormat: RootJsonFormat[BoostableBossList] = jsonFormat3(BoostableBossList)
  implicit val boostableBossesFormat: RootJsonFormat[BoostableBosses] = jsonFormat2(BoostableBosses)
  implicit val boostedResponseFormat: RootJsonFormat[BoostedResponse] = jsonFormat2(BoostedResponse)

  implicit val boostedCreatureFormat: RootJsonFormat[BoostedCreature] = jsonFormat4(BoostedCreature)
  implicit val creatureListItemFormat: RootJsonFormat[CreatureListItem] = jsonFormat4(CreatureListItem)
  implicit val creaturesFormat: RootJsonFormat[Creatures] = jsonFormat2(Creatures)
  implicit val creaturesResponseFormat: RootJsonFormat[CreaturesResponse] = jsonFormat2(CreaturesResponse)

  implicit val creatureListFormat: RootJsonFormat[CreatureList] = jsonFormat3(CreatureList)
  implicit val creatureDataFormat: RootJsonFormat[CreatureData] = jsonFormat2(CreatureData)
  implicit val creatureResponseFormat: RootJsonFormat[CreatureResponse] = jsonFormat2(CreatureResponse)

  implicit val raceFormat: RootJsonFormat[Race] = jsonFormat20(Race)
  implicit val raceResponseFormat: RootJsonFormat[RaceResponse] = jsonFormat2(RaceResponse)

  implicit val highscorePageFormat: RootJsonFormat[HighscoresPage] = jsonFormat3(HighscoresPage)
  implicit val highscoreListFormat: RootJsonFormat[HighscoresList] = jsonFormat6(HighscoresList)
  implicit val highscoresFormat: RootJsonFormat[Highscores] = jsonFormat6(Highscores)
  implicit val highscoresResponseFormat: RootJsonFormat[HighscoresResponse] = jsonFormat2(HighscoresResponse)

  implicit val newsEntryFormat: RootJsonFormat[NewsEntry] = jsonFormat6(NewsEntry)
  implicit val newsDataFormat: RootJsonFormat[NewsData] = jsonFormat1(NewsData)
  implicit val newsResponseFormat: RootJsonFormat[NewsResponse] = jsonFormat2(NewsResponse)
  
  implicit val newsTickerEntryFormat: RootJsonFormat[NewsTickerEntry] = jsonFormat2(NewsTickerEntry)
  implicit val newsTickerDataFormat: RootJsonFormat[NewsTickerData] = jsonFormat1(NewsTickerData)
  implicit val newsTickerResponseFormat: RootJsonFormat[NewsTickerResponse] = jsonFormat2(NewsTickerResponse)
}

// This is needed because you can't just call json.convertTo[String] inside strFormat above because you get a stack overflow because it calls back on itself
object JsonConvertNoCustomImplicits extends SprayJsonSupport with DefaultJsonProtocol {
  def convert(json: JsValue): String = json.convertTo[String]
}
