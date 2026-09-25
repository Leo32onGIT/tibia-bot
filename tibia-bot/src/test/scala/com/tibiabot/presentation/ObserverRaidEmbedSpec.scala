package com.tibiabot.presentation

import com.tibiabot.domain.RaidAnnouncement
import com.tibiabot.observer.{ObserverRaidPoller, RaidTypeCatalog}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The raids channel's three stage posts. Each puts its location under the title as
 *  a grey line; only the start post names the raid, since the feed only says which
 *  raid it is then. The emoji is passed in so these run without loading Config. */
class ObserverRaidEmbedSpec extends AnyFunSuite with Matchers {

  private val emoji = "<:raid:1>"
  private val start = Instant.parse("2026-09-24T13:00:00Z")
  private val WinterWolves = RaidTypeCatalog.get(289)

  private def raid(category: String, subarea: Option[String] = None, typeId: Int = 0) =
    RaidAnnouncement("r1", "Antica", "Hrodmir", subarea, category, Some(start), typeId)

  // Joined explicitly rather than a multi-line literal, whose line endings follow
  // the checkout's (CRLF on Windows) while the embed's are always \n.
  private def lines(ls: String*) = ls.mkString("\n")

  test("the area post is 'Imminent Raid', with the area under it and when the subarea reveals") {
    val e = ObserverEmbeds.areaEmbed(raid("areaRevealed"), emoji)
    e.getTitle shouldBe "<:raid:1> Imminent Raid"
    e.getUrl shouldBe null
    e.getDescription shouldBe lines(
      "-# Hrodmir",
      s"**Subarea reveals:** <t:${start.minus(ObserverRaidPoller.SubareaLead).getEpochSecond}:R>")
    e.getThumbnail shouldBe null
  }

  test("the subarea post is 'Subarea Revealed', with the subarea under it and when the raid starts") {
    val e = ObserverEmbeds.subareaEmbed(raid("subareaRevealed", Some("Krimhorn")), emoji)
    e.getTitle shouldBe "<:raid:1> Subarea Revealed"
    e.getDescription shouldBe lines("-# Krimhorn", s"**Raid starts:** <t:${start.getEpochSecond}:R>")
    e.getThumbnail shouldBe null
  }

  test("a subarea post with no subarea to give falls back to the area") {
    ObserverEmbeds.subareaEmbed(raid("subareaRevealed"), emoji).getDescription should startWith("-# Hrodmir\n")
  }

  test("the start post names the raid, with the subarea, when it started, and its creatures and picture") {
    val e = ObserverEmbeds.startedEmbed(raid("raidStarted", Some("Krimhorn"), 289), WinterWolves, emoji)
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

  test("a start the catalogue doesn't know is 'Raid Started', with no creatures") {
    val e = ObserverEmbeds.startedEmbed(raid("raidStarted", Some("Krimhorn"), 9999), None, emoji)
    e.getTitle shouldBe "<:raid:1> Raid Started"
    e.getDescription shouldBe lines("-# Krimhorn", s"**Raid started:** <t:${start.getEpochSecond}:R>")
    e.getThumbnail shouldBe null
  }
}
