package com.tibiabot

import com.tibiabot.presentation.{BoostedEmbeds, DeathEmbeds}
import com.tibiabot.tibiadata.JsonSupport
import com.tibiabot.tibiadata.response._
import com.tibiabot.tracking.{LevelRecord, LevelTracker, OnlineTracker}
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

  test("real death killers from the API drive the production killer-text logic") {
    val deaths = fixture("character.json").parseJson.convertTo[CharacterResponse].character.deaths.getOrElse(Nil)
    deaths should not be empty
    val killerNames = deaths.flatMap(_.killers.map(_.name)).distinct
    killerNames should not be empty

    // these fixture deaths are by plain creatures, not "<summon> of <player>"
    killerNames.foreach(n => domain.Killers.parseSummon(n) shouldBe None)

    // each renders as a single-entry phrase with the right indefinite article,
    // exactly as the death block composes "by <killer>."
    killerNames.foreach { n =>
      val phrase = domain.Killers.joinNatural(Seq(s"${domain.Killers.sourceArticle(n)}$n"))
      phrase should (startWith("a ") or startWith("an "))
      phrase should endWith(n)
    }

    // a multi-killer death would join with commas + "and"
    domain.Killers.joinNatural(killerNames.take(2)) should include(killerNames.head)
  }

  test("every real online player's vocation maps to a production emoji") {
    val online = fixture("world_antica.json").parseJson.convertTo[WorldResponse].world.online_players.getOrElse(Nil)
    online should not be empty
    val vocations = online.map(_.vocation).distinct
    vocations should contain("Elite Knight")
    vocations should contain("Exalted Monk") // monk is included in TibiaBot's vocEmoji variant

    // no real online vocation renders as a blank in the online list
    vocations.foreach { v =>
      withClue(s"vocation '$v' produced no emoji: ") {
        presentation.Emojis.vocEmoji(v) should not be empty
      }
    }
  }

  test("every real guild-member vocation maps to a production emoji (incl. monk)") {
    val members = fixture("guild.json").parseJson.convertTo[GuildResponse].guild.members.getOrElse(Nil)
    members should not be empty
    val vocations = members.map(_.vocation).distinct
    vocations should contain("Exalted Monk") // monk now resolves on every path

    vocations.foreach { v =>
      withClue(s"vocEmoji('$v'): ") { presentation.Emojis.vocEmoji(v) should not be empty }
    }
  }

  test("the real boosted boss renders through the production boosted embed") {
    val boss = fixture("boostablebosses.json").parseJson.convertTo[BoostedResponse].boostable_bosses.boosted.name
    val text = s"The boosted boss today is: **$boss**"
    val embed = BoostedEmbeds.create("https://x/t.gif", text)
    embed.getDescription should include(boss)
  }

  test("the real boosted creature renders through the production boosted embed") {
    // the creature feed decodes via a different path (CreatureResponse.creatures)
    // than the boss feed (BoostedResponse.boostable_bosses), so cover it too.
    val creature = fixture("creatures.json").parseJson.convertTo[CreatureResponse].creatures.boosted.name
    creature should not be empty
    val text = s"The boosted creature today is: **$creature**"
    val embed = BoostedEmbeds.create("https://x/t.gif", text)
    embed.getDescription should include(creature)
  }

  test("every real guild member flows through the production list ordering (WorldList.byWorld)") {
    val members = fixture("guild.json").parseJson.convertTo[GuildResponse].guild.members.getOrElse(Nil)
    members should not be empty

    // Build the per-vocation (level, world, line) entries the way the list command does.
    val entriesByVoc: Map[String, Seq[(Int, String, String)]] =
      members.groupBy(_.vocation.toLowerCase.split(' ').last)
        .view.mapValues(_.map(m =>
          (m.level.toInt, "Antica", s"${presentation.Emojis.vocEmoji(m.vocation)} ${m.name}")).toSeq)
        .toMap

    val grouped = presentation.WorldList.byWorld(entriesByVoc)

    // single world, and no member dropped (every real vocation maps to a known bucket)
    grouped.keySet shouldBe Set("Antica")
    grouped("Antica") should have size members.size

    // vocation priority holds on real data: the first druid precedes the first knight
    val vocations = members.map(_.vocation.toLowerCase.split(' ').last).toSet
    if (vocations("druid") && vocations("knight")) {
      val lines = grouped("Antica")
      lines.indexWhere(_.startsWith(":snowflake:")) should be < lines.indexWhere(_.startsWith(":shield:"))
    }
  }

  test("OnlineTracker ingests and re-cycles a real world's online players correctly") {
    val online = fixture("world_antica.json").parseJson.convertTo[WorldResponse].world.online_players.getOrElse(Nil)
    online should not be empty
    val rows = online.map(p => (p.name, p.level.toInt, p.vocation))
    val t0 = ZonedDateTime.parse("2026-01-01T00:00:00Z")

    val tracker = new OnlineTracker
    // cycle 1: every real player is tracked (online names are unique); all new -> zero duration
    tracker.updateFromOnline(rows, t0)
    tracker.size shouldBe online.size
    tracker.snapshot.forall(_.duration == 0L) shouldBe true

    // a guild + flag set mid-cycle must carry over to the next cycle
    val sample = online.head.name
    tracker.setGuild(sample, "Bona Fide")
    tracker.setFlag(sample, ":star:")

    // cycle 2, 10 minutes later: only the first half stay online; the rest log off
    val stayed = rows.take(rows.size / 2)
    tracker.updateFromOnline(stayed, t0.plusMinutes(10))
    tracker.size shouldBe stayed.size // logged-off players dropped, no leak
    val carried = tracker.find(sample).getOrElse(fail("a still-online player was dropped"))
    carried.guildName shouldBe "Bona Fide" // carried over across the cycle
    carried.flag shouldBe ":star:"
    carried.duration shouldBe 600L // 10 minutes accumulated
  }

  test("a real character's last_login parses through RecentLogin.stamp to the right epoch") {
    // Guards against the real API timestamp format silently failing RecentLogin's
    // ISO parse (which would render an empty stamp in the /allies|/hunted list).
    val lastLogin = character().last_login.getOrElse(fail("fixture character has no last_login"))
    val loginInstant = java.time.Instant.parse(lastLogin)
    // anchor 'now' one hour after the real login so it falls inside the 24h window
    val stamp = presentation.RecentLogin.stamp(lastLogin, loginInstant.plusSeconds(3600))
    stamp should include(s"<t:${loginInstant.getEpochSecond}:R>")
  }

  test("sanity: the character fixture exposes the fields the bot reads") {
    val ch = character()
    ch.name should not be empty
    ch.vocation should not be empty
    ch.level should be > 0.0
  }
}
