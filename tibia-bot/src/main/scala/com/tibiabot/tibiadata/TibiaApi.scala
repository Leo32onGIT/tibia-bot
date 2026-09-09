package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response.{BoostedResponse, CharacterResponse, CreatureResponse, GuildResponse, WorldResponse, WorldsResponse}

import scala.concurrent.Future

/** Port over the TibiaData HTTP API, implemented by TibiaDataClient. Lets
 *  callers depend on the interface (and be given stubs in tests) rather than
 *  the concrete Pekko HTTP client. */
trait TibiaApi {
  def getWorld(world: String): Future[Either[String, WorldResponse]]
  def getWorlds(): Future[Either[String, WorldsResponse]]
  def getBoostedBoss(): Future[Either[String, BoostedResponse]]
  def getBoostedCreature(): Future[Either[String, CreatureResponse]]
  def getGuild(guild: String): Future[Either[String, GuildResponse]]
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)]
  def getCharacter(name: String): Future[Either[String, CharacterResponse]]

  /** A character fetch for the command path — a name somebody typed or pasted,
   *  looked up once before it is added to a list.
   *
   *  Separate from `getCharacter` because that one is the poll's, and deliberately
   *  skips the inline retry: it asks again a minute later, so a 503 costs it
   *  nothing. Here the next attempt is never, and roughly 45% of this API's
   *  responses are 503s — so one attempt reports a real character as "does not
   *  exist" about as often as not.
   *
   *  The default delegates, so every decorator and test stub keeps compiling —
   *  but it loses the retry. Override it in anything sitting in front of a
   *  command path.
   */
  def getCharacterOnDemand(name: String): Future[Either[String, CharacterResponse]] = getCharacter(name)
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]]
  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)]
}
