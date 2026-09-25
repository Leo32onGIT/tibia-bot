package com.tibiabot.presentation

import com.tibiabot.domain.RaidAnnouncement
import com.tibiabot.observer.{ObserverRaidPoller, RaidTypeCatalog}
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.thumbnail.Thumbnail
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.jdk.CollectionConverters._

/** The raids channel's three stage cards. Each opens on its stage as a small grey
 *  label in small caps, over the place as a header, with when the next thing
 *  happens as a small grey line; only the start card names the raid, since the
 *  feed only says which raid it is then. The broadcast lines stay grey embeds.
 *  The emoji is passed in so these run without loading Config. */
class ObserverRaidEmbedSpec extends AnyFunSuite with Matchers {

  private val emoji = "<:raid:1>"
  private val start = Instant.parse("2026-09-24T13:00:00Z")
  private val WinterWolves = RaidTypeCatalog.get(289)

  private def raid(category: String, subarea: Option[String] = None, typeId: Int = 0) =
    RaidAnnouncement("r1", "Antica", "Hrodmir", subarea, category, Some(start), typeId)

  // Joined explicitly rather than a multi-line literal, whose line endings follow
  // the checkout's (CRLF on Windows) while the card's are always \n.
  private def lines(ls: String*) = ls.mkString("\n")

  /** A card's parts in order: a text as its content, a section as its text and
   *  then its picture, and a divider as `---`. */
  private def partsOf(card: Container): List[String] =
    card.getComponents.asScala.toList.flatMap {
      case t: TextDisplay => List(t.getContent)
      case s: Section =>
        s.getContentComponents.asScala.toList.collect { case t: TextDisplay => t.getContent } ++
          (s.getAccessory match {
            case t: Thumbnail => List(s"picture:${t.getUrl}")
            case _            => Nil
          })
      case _: Separator => List("---")
      case other        => List(other.toString)
    }

  test("the area card is the imminent-raid label over the area, and when the subarea reveals") {
    val card = ObserverEmbeds.areaCard(raid("areaRevealed"), emoji)
    partsOf(card) shouldBe List(lines(
      "-# ɪᴍᴍɪɴᴇɴᴛ ʀᴀɪᴅ",
      "### <:raid:1> Hrodmir",
      s"-# **Subarea reveals:** <t:${start.minus(ObserverRaidPoller.SubareaLead).getEpochSecond}:R>"))
    card.getAccentColorRaw.intValue shouldBe Embeds.AutomaticColor
  }

  test("the subarea card is the subarea-revealed label over the subarea, and when the raid starts") {
    partsOf(ObserverEmbeds.subareaCard(raid("subareaRevealed", Some("Krimhorn")), emoji)) shouldBe List(lines(
      "-# sᴜʙᴀʀᴇᴀ ʀᴇᴠᴇᴀʟᴇᴅ",
      "### <:raid:1> Krimhorn",
      s"-# **Raid starts:** <t:${start.getEpochSecond}:R>"))
  }

  test("a subarea card with no subarea to give falls back to the area") {
    partsOf(ObserverEmbeds.subareaCard(raid("subareaRevealed"), emoji)).head should include("### <:raid:1> Hrodmir\n")
  }

  test("the start card names the raid over the subarea in bold, with its picture, then its creatures") {
    val card = ObserverEmbeds.startedCard(raid("raidStarted", Some("Krimhorn"), 289), WinterWolves, emoji)
    partsOf(card) shouldBe List(
      lines(
        "-# ʀᴀɪᴅ sᴛᴀʀᴛᴇᴅ",
        "### <:raid:1> [Winter Wolves near Krimhorn](https://tibia.fandom.com/wiki/Svargrond_Raids#Winter_Wolf_Raid_near_Krimhorn)",
        "-# **Krimhorn**",
        s"-# **Raid started:** <t:${start.getEpochSecond}:R>"),
      "picture:https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Winter_Wolf.gif",
      "---",
      lines(
        "-# ᴄʀᴇᴀᴛᴜʀᴇs",
        "• [Winter Wolf](https://tibia.fandom.com/wiki/Winter_Wolf)"))
    card.getAccentColorRaw.intValue shouldBe Embeds.AutomaticColor
  }

  test("a start the catalogue doesn't know has the subarea as its header, with no picture or creatures") {
    partsOf(ObserverEmbeds.startedCard(raid("raidStarted", Some("Krimhorn"), 9999), None, emoji)) shouldBe List(lines(
      "-# ʀᴀɪᴅ sᴛᴀʀᴛᴇᴅ",
      "### <:raid:1> Krimhorn",
      s"-# **Raid started:** <t:${start.getEpochSecond}:R>"))
  }

  test("a stage card is sent as a V2 message, and a broadcast line as its grey embed alone") {
    val stage = ObserverEmbeds.stageMessage(ObserverEmbeds.areaCard(raid("areaRevealed"), emoji))
    stage.isUsingComponentsV2 shouldBe true
    stage.getEmbeds shouldBe empty

    val line = ObserverEmbeds.raidLineMessage("Winter wolves are howling near Krimhorn.")
    line.isUsingComponentsV2 shouldBe false
    line.getContent shouldBe empty
    line.getEmbeds.asScala.map(_.getDescription) shouldBe List("**Winter wolves are howling near Krimhorn.**")
    line.getEmbeds.get(0).getColorRaw shouldBe 4540237
  }
}
