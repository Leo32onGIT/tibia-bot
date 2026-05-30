package com.tibiabot

import com.tibiabot.presentation.{BoostedEmbeds, DeathEmbeds}
import com.tibiabot.tibiadata.JsonSupport
import com.tibiabot.tibiadata.response._
import com.tibiabot.tracking.{LevelRecord, LevelTracker}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.ZonedDateTime

/**
 * Threads REAL decoded TibiaData fixtures through the bot's production logic
 * (the seams actually wired into the live path): level-up detection
 * (LevelTracker, wired in the stream), the death embed and the boosted embed.
 * This goes a step past the decoder tests — it checks the bot does the right
 * thing with real API data, not just that the JSON parses. Still hermetic.
 */
class RealDataBehaviorSpec extends AnyFunSuite with Matchers with JsonSupport {

  private def fixture(name: String): String = {
    val is = getClass.getResourceAsStream(s"/tibiadata/$name")
    require(is != null, s"missing fixture /tibiadata/$name")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString finally is.close()
  }

  private def character(): Character =
    fixture("character.json").parseJson.convertTo[CharacterResponse].character.character

  test("a real character drives the production level-up decision (LevelTracker)") {
    val sheet = fixture("character.json").parseJson.convertTo[CharacterResponse].character
    val name = sheet.character.name
    val level = sheet.character.level.toInt
    val lastLogin = ZonedDateTime.parse(sheet.character.last_login.getOrElse(fail("character has no last_login")))

    val tracker = new LevelTracker
    // never seen this (name, level) -> the bot would post and cache the level-up
    tracker.shouldRecord(name, level, lastLogin) shouldBe true

    // already recorded this login session -> suppressed (no double post)
    tracker.record(LevelRecord(name, level, sheet.character.vocation, lastLogin, lastLogin))
    tracker.shouldRecord(name, level, lastLogin) shouldBe false

    // a later login at the same level (relog, then re-level) -> posts again
    tracker.shouldRecord(name, level, lastLogin.plusHours(1)) shouldBe true
  }

  test("a real character death renders through the production death embed") {
    val sheet = fixture("character.json").parseJson.convertTo[CharacterResponse].character
    val ch = sheet.character
    val death = sheet.deaths.getOrElse(Nil).headOption.getOrElse(fail("fixture character has no deaths"))

    val embed = DeathEmbeds.build(ch.name, ch.vocation, death.reason, "https://x/t.gif", 3092790).build()
    embed.getTitle should include(ch.name)         // production title = vocation emoji + real name
    embed.getDescription shouldBe death.reason     // real TibiaData death reason flows through
    embed.getDescription should not be empty
  }

  test("the real boosted boss renders through the production boosted embed") {
    val boss = fixture("boostablebosses.json").parseJson.convertTo[BoostedResponse].boostable_bosses.boosted.name
    val text = s"The boosted boss today is: **$boss**"
    val embed = BoostedEmbeds.create(boss, ":crossed_swords:", "https://wiki", "https://x/t.gif", text)
    embed.getDescription should include(boss)
  }

  test("sanity: the character fixture exposes the fields the bot reads") {
    val ch = character()
    ch.name should not be empty
    ch.vocation should not be empty
    ch.level should be > 0.0
  }
}
