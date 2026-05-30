package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

/**
 * Decodes real TibiaData v4 payloads (captured under test/resources/tibiadata)
 * with the production [[JsonSupport]] formats. Hermetic: no network at test time;
 * the fixtures are frozen snapshots that lock the live API contract the bot relies on.
 */
class TibiaDataDecodersSpec extends AnyFunSuite with Matchers with JsonSupport {

  private def fixture(name: String): String = {
    val is = getClass.getResourceAsStream(s"/tibiadata/$name")
    require(is != null, s"missing fixture /tibiadata/$name")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString
    finally is.close()
  }

  test("boostablebosses: boosted boss is named and appears in the full list") {
    val r = fixture("boostablebosses.json").parseJson.convertTo[BoostedResponse]
    r.boostable_bosses.boosted.name should not be empty
    r.boostable_bosses.boostable_boss_list.size should be > 10
    r.boostable_bosses.boostable_boss_list.map(_.name) should contain(r.boostable_bosses.boosted.name)
  }

  test("creatures: boosted creature is named and the full list decodes") {
    val r = fixture("creatures.json").parseJson.convertTo[CreaturesResponse]
    r.creatures.boosted.name should not be empty
    r.creatures.boosted.race should not be empty
    r.creatures.creature_list.size should be > 50
    // every list entry fully decodes (name + image url present)
    all(r.creatures.creature_list.map(_.name)) should not be empty
    all(r.creatures.creature_list.map(_.image_url)) should startWith("https://")
  }

  test("world: online players list decodes with positive levels") {
    val r = fixture("world_antica.json").parseJson.convertTo[WorldResponse]
    r.world.name shouldBe "Antica"
    r.world.status shouldBe "online"
    val online = r.world.online_players.getOrElse(Nil)
    online should not be empty
    all(online.map(_.level)) should be > 0.0
  }

  test("character: empty guild object decodes to None and deaths/killers parse") {
    val r = fixture("character.json").parseJson.convertTo[CharacterResponse]
    val sheet = r.character
    sheet.character.name should not be empty
    sheet.character.level should be > 0.0
    sheet.character.guild shouldBe None // TibiaData returns {} for guildless chars -> optGuildFormat -> None
    val deaths = sheet.deaths.getOrElse(Nil)
    deaths should not be empty
    val first = deaths.head
    first.time should not be empty
    first.level should be > 0.0
    (first.killers.nonEmpty || first.reason.nonEmpty) shouldBe true
  }

  test("guild: members list decodes with ranks, vocations and levels") {
    val r = fixture("guild.json").parseJson.convertTo[GuildResponse]
    r.guild.name should not be empty
    r.guild.world should not be empty
    val members = r.guild.members.getOrElse(Nil)
    members should not be empty
    all(members.map(_.name)) should not be empty
    all(members.map(_.vocation)) should not be empty
    all(members.map(_.level)) should be > 0.0
  }

  test("highscores: experience page decodes with a ranked list and paging") {
    val r = fixture("highscores_antica.json").parseJson.convertTo[HighscoresResponse]
    r.highscores.world shouldBe "Antica"
    r.highscores.category shouldBe "experience"
    val list = r.highscores.highscore_list.getOrElse(Nil)
    list should not be empty
    list.head.rank shouldBe 1.0
    all(list.map(_.name)) should not be empty
    all(list.map(_.value)) should be > 0.0
    r.highscores.highscore_page.total_pages should be > 0.0
  }
}
