package com.tibiabot.presentation

import com.tibiabot.domain.RaidAnnouncement
import com.tibiabot.observer.RaidTypeCatalog
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

/** The raids channel's stage posts. Each titles the stage (or names the raid, once
 *  known) and puts its location under the title as a grey line. The emoji is passed
 *  in so these run without loading Config. */
class ObserverRaidEmbedSpec extends AnyFunSuite with Matchers {

  private val emoji = "<:raid:1>"
  private val start = Instant.parse("2026-09-24T13:00:00Z")
  private val WinterWolves = RaidTypeCatalog.get(289)

  private def raid(category: String, subarea: Option[String] = None) =
    RaidAnnouncement("r1", "Antica", "Hrodmir", subarea, category, Some(start), 289)

  // Joined explicitly rather than a multi-line literal, whose line endings follow
  // the checkout's (CRLF on Windows) while the embed's are always \n.
  private def lines(ls: String*) = ls.mkString("\n")

  test("the area post of an unnamed raid is 'Imminent Raid', with the area under it") {
    val e = ObserverEmbeds.areaEmbed(raid("areaRevealed"), None, emoji)
    e.getTitle shouldBe "<:raid:1> Imminent Raid"
    e.getUrl shouldBe null
    e.getDescription shouldBe lines(
      "-# Hrodmir",
      s"**Subarea reveals:** <t:${start.minus(Duration.ofMinutes(30)).getEpochSecond}:R>")
    e.getThumbnail shouldBe null
  }

  test("the subarea post of an unnamed raid is 'Subarea Revealed', with the subarea under it") {
    val e = ObserverEmbeds.subareaEmbed(raid("subareaRevealed", Some("Krimhorn")), None,
      start.minus(Duration.ofMinutes(30)), emoji)
    e.getTitle shouldBe "<:raid:1> Subarea Revealed"
    e.getDescription shouldBe lines("-# Krimhorn", s"**Raid starts:** <t:${start.getEpochSecond}:R>")
  }

  test("a subarea post with no subarea to give falls back to the area") {
    val e = ObserverEmbeds.subareaEmbed(raid("subareaRevealed"), None, start.minus(Duration.ofMinutes(30)), emoji)
    e.getDescription should startWith("-# Hrodmir\n")
  }

  test("a named raid at its start has its name, the subarea alone, and its creatures and picture") {
    val e = ObserverEmbeds.subareaEmbed(raid("raidStarted", Some("Krimhorn")), WinterWolves,
      start.plusSeconds(20), emoji)
    e.getTitle shouldBe "<:raid:1> Winter Wolves near Krimhorn"
    e.getUrl shouldBe "https://tibia.fandom.com/wiki/Svargrond_Raids#Winter_Wolf_Raid_near_Krimhorn"
    e.getDescription shouldBe lines(
      "-# Krimhorn",
      s"**Raid started:** <t:${start.getEpochSecond}:R>",
      "",
      "**Creatures:**",
      "• [Winter Wolf](https://tibia.fandom.com/wiki/Winter_Wolf)")
    e.getThumbnail.getUrl shouldBe "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Winter_Wolf.gif"
  }

  test("a raid named from the area stage shows the area, the only place revealed so far") {
    val e = ObserverEmbeds.areaEmbed(raid("areaRevealed"), WinterWolves, emoji)
    e.getTitle shouldBe "<:raid:1> Winter Wolves near Krimhorn"
    e.getDescription should startWith("-# Hrodmir\n**Subarea reveals:**")
  }
}
