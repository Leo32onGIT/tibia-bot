package com.tibiabot.highscores

import com.tibiabot.domain.HighscoreEvent
import com.tibiabot.tibiadata.HighscoreCategory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Which advances a server sees, and how the line reads.
 *
 *  The gates are the level-up path's own, so what is really pinned here is that
 *  a skill advance obeys the switches a server already set. */
class HighscoreAnnouncementSpec extends AnyFunSuite with Matchers {

  private val observed = Instant.parse("2026-09-02T05:40:00Z")

  private def event(name: String = "Bubble", level: Int = 400, score: Long = 116, vocation: String = "Elite Knight") =
    HighscoreEvent("Antica", "swordfighting", name.toLowerCase, name, vocation, level, score - 1, score, observed)

  private def target(
      showNeutral: String = "true", showAllies: String = "true", showEnemies: String = "true",
      minimumLevel: Int = 20,
      alliedGuilds: Set[String] = Set.empty, huntedGuilds: Set[String] = Set.empty,
      alliedPlayers: Set[String] = Set.empty, huntedPlayers: Set[String] = Set.empty
  ) = HighscoreTarget("1", "Test Guild", "2", showNeutral, showAllies, showEnemies, minimumLevel,
    alliedGuilds, huntedGuilds, alliedPlayers, huntedPlayers)

  test("an unknown guild is neutral and still posts by default") {
    HighscoreAnnouncement.shouldPost(target(), event(), guildName = "") shouldBe true
  }

  test("a character below the world's minimum level is suppressed") {
    HighscoreAnnouncement.shouldPost(target(minimumLevel = 500), event(level = 400), "") shouldBe false
    HighscoreAnnouncement.shouldPost(target(minimumLevel = 400), event(level = 400), "") shouldBe true
  }

  test("hiding neutral levels hides neutral skill advances too") {
    HighscoreAnnouncement.shouldPost(target(showNeutral = "false"), event(), "") shouldBe false
  }

  test("an enemy guild is judged by the enemy switch, not the neutral one") {
    // The case that makes resolving the guild worth a request: a server showing
    // enemies but not neutrals would miss this entirely if we guessed neutral.
    val enemies = target(showNeutral = "false", showEnemies = "true", huntedGuilds = Set("honour"))
    HighscoreAnnouncement.shouldPost(enemies, event(), guildName = "Honour") shouldBe true
    HighscoreAnnouncement.shouldPost(enemies, event(), guildName = "") shouldBe false
  }

  test("an allied guild is judged by the allies switch") {
    val noAllies = target(showAllies = "false", alliedGuilds = Set("ruckus"))
    HighscoreAnnouncement.shouldPost(noAllies, event(), guildName = "Ruckus") shouldBe false
    HighscoreAnnouncement.shouldPost(noAllies, event(), guildName = "Someone Else") shouldBe true
  }

  test("a tracked player is classified by name, with no guild needed") {
    val hunted = target(showNeutral = "false", huntedPlayers = Set("bubble"))
    HighscoreAnnouncement.shouldPost(hunted, event("Bubble"), guildName = "") shouldBe true
  }

  test("a server tracking no guilds never needs one resolved") {
    target().needsGuild shouldBe false
    target(alliedGuilds = Set("ruckus")).needsGuild shouldBe true
    target(huntedGuilds = Set("honour")).needsGuild shouldBe true
  }

  test("the line names the skill, and magic level does not say level twice") {
    HighscoreAnnouncement.line(event(score = 116), HighscoreCategory.SwordFighting, "") should
      include("advanced to sword fighting level **116**")
    HighscoreAnnouncement.line(event(score = 108), HighscoreCategory.MagicLevel, "") should
      include("advanced to magic level **108**")
  }

  test("the line links the character and carries the vocation emoji and icon") {
    val line = HighscoreAnnouncement.line(event("Bubble"), HighscoreCategory.SwordFighting, ":crossed_swords:")
    line should include("[Bubble](https://www.tibia.com/community/?name=Bubble)")
    line should startWith(":shield:")
    line should endWith(":crossed_swords:")
  }

  test("a batch keeps rank order and drops only what the settings suppress") {
    val advances = List(event("First", level = 900), event("Second", level = 10), event("Third", level = 500))
    val lines = HighscoreAnnouncement.linesFor(target(minimumLevel = 100), HighscoreCategory.SwordFighting, advances, _ => "")

    lines should have size 2
    lines.head should include("First")
    lines.last should include("Third")
  }

  test("a batch that every setting suppresses yields nothing to send") {
    HighscoreAnnouncement.linesFor(
      target(showNeutral = "false"), HighscoreCategory.SwordFighting, List(event()), _ => "") shouldBe empty
  }
}
