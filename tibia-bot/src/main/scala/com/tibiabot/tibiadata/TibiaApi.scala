package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response.{BoostedResponse, CharacterResponse, CreatureResponse, GuildResponse, HighscoresResponse, WorldResponse, WorldsResponse}

import scala.concurrent.Future

/** Port over the TibiaData HTTP API, implemented by TibiaDataClient. Lets
 *  callers depend on the interface (and be given stubs in tests) rather than
 *  the concrete Akka-HTTP client. */
trait TibiaApi {
  def getWorld(world: String): Future[Either[String, WorldResponse]]
  def getWorlds(): Future[Either[String, WorldsResponse]]
  def getBoostedBoss(): Future[Either[String, BoostedResponse]]
  def getBoostedCreature(): Future[Either[String, CreatureResponse]]
  def getHighscores(world: String, page: Int): Future[Either[String, HighscoresResponse]]
  def getGuild(guild: String): Future[Either[String, GuildResponse]]
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)]
  def getCharacter(name: String): Future[Either[String, CharacterResponse]]
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]]
  def getCharacterV2(input: (String, Int)): Future[Either[String, CharacterResponse]]
  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)]
}
