package com.tibiabot.presentation

import com.tibiabot.presentation.DeathCard.{Post, Screenshot}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.mediagallery.MediaGallery
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.thumbnail.Thumbnail
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** A death as a V2 card: what it holds, where the button goes, and that a post
 *  reads back to what it was built from — from a card, or from an embed posted
 *  before the switch. */
class DeathCardSpec extends AnyFunSuite with Matchers {

  private val ally = 13773097
  private val post = Post(
    title = DeathCard.title("Mira Solvane", "Elder Druid"),
    description = "<:guild:1> *Vice Leader* of the [Blackout Company](https://g)\nKilled <t:1:R> at level 865\nby **[Oskar [1120]](https://o)**.",
    thumbnail = Some("https://violentbot.xyz/discord/effects/Phantasmal_Ooze.gif"),
    colour = ally)

  private def card(parts: List[net.dv8tion.jda.api.components.MessageTopLevelComponent]): Container =
    parts.collectFirst { case c: Container => c }.get

  test("the title is the name between its vocation's emoji, linked to its page") {
    DeathCard.title("Mira Solvane", "Elder Druid") shouldBe
      ":snowflake: [Mira Solvane](https://www.tibia.com/community/?name=Mira+Solvane) :snowflake:"
  }

  test("a vocation with no emoji leaves just the linked name") {
    DeathCard.title("Nobody", "") shouldBe "[Nobody](https://www.tibia.com/community/?name=Nobody)"
  }

  test("the name is the card's header, and the post's button sits on its line") {
    val parts = card(DeathCard.components(post, Some(DeathCard.exivaButton("Mira Solvane", 1L, "<:exiva:9>")), Nil)).getComponents.asScala.toList
    parts.head match {
      case section: Section =>
        section.getContentComponents.asScala.collect { case t: TextDisplay => t.getContent } shouldBe List(s"### ${post.title}")
        section.getAccessory shouldBe a[Button]
        section.getAccessory.asInstanceOf[Button].getCustomId shouldBe "death_exiva_Mira Solvane_1"
      case other => fail(s"expected a section, got $other")
    }
  }

  test("no divider under the name: the body follows it, with the picture beside it") {
    val parts = card(DeathCard.components(post, None, Nil)).getComponents.asScala.toList
    parts.map(_.getClass.getSimpleName.replace("Impl", "")) shouldBe List("TextDisplay", "Section")
    parts(1) match {
      case section: Section =>
        section.getAccessory match {
          case t: Thumbnail => t.getUrl shouldBe post.thumbnail.get
          case other => fail(s"expected the picture, got $other")
        }
      case other => fail(s"expected a section, got $other")
    }
  }

  test("the edge is the death's side colour") {
    card(DeathCard.components(post, None, Nil)).getAccentColorRaw.intValue shouldBe ally
  }

  test("a neutral with no guild gets no edge at all") {
    card(DeathCard.components(post.copy(colour = DeathCard.NoEdgeColour), None, Nil)).getAccentColorRaw shouldBe null
  }

  test("a ping is a line of its own above the card") {
    val parts = DeathCard.components(post.copy(ping = Some("<@&42>")), None, Nil)
    parts.head match {
      case t: TextDisplay => t.getContent shouldBe "<@&42>"
      case other => fail(s"expected the ping, got $other")
    }
    parts(1) shouldBe a[Container]
  }

  test("a screenshot is a gallery under the body, its line under that, and its paging at the foot") {
    val shot = Screenshot("https://cdn/shot.png", "Screenshot added by Zaryx • 2/2")
    val row = DeathCard.screenshotRow("Ilse", 1L, "m", 1, 2, deletable = true)
    val parts = card(DeathCard.components(post.copy(screenshot = Some(shot)), Some(DeathCard.cameraButton("Ilse", 1L, "m")), row))
      .getComponents.asScala.toList
    parts(2) shouldBe a[MediaGallery]
    parts(3) match {
      case t: TextDisplay => t.getContent shouldBe "-# Screenshot added by Zaryx • 2/2"
      case other => fail(s"expected the caption, got $other")
    }
    parts(4) shouldBe a[ActionRow]
  }

  test("the camera is the whole button") {
    val camera = DeathCard.cameraButton("Ilse", 5L)
    camera.getCustomId shouldBe "death_screenshot_Ilse_5_placeholder"
    Option(camera.getLabel).filter(_.nonEmpty) shouldBe None
    camera.getEmoji.getName shouldBe "📷"
  }

  test("paging shows only when there is more than one screenshot, delete only for whoever may") {
    DeathCard.screenshotRow("A", 1L, "m", 0, 1, deletable = false) shouldBe empty
    DeathCard.screenshotRow("A", 1L, "m", 0, 1, deletable = true).map(_.getLabel) shouldBe List("🗑️")
    DeathCard.screenshotRow("A", 1L, "m", 1, 3, deletable = true).map(_.getLabel) shouldBe List("◀", "2/3", "▶", "🗑️")
  }

  test("a new death is sent as a V2 message") {
    DeathCard.create(post, None).isUsingComponentsV2 shouldBe true
  }

  test("an edit clears the text and embeds, so an embed post becomes a card in place") {
    val edit = DeathCard.edit(post.copy(ping = Some("<@&42>")), None, Nil)
    edit.isUsingComponentsV2 shouldBe true
    edit.getContent shouldBe ""
    edit.getEmbeds shouldBe empty
  }

  test("a card reads back to the post it was built from") {
    val full = post.copy(ping = Some("<@&42>"), screenshot = Some(Screenshot("https://cdn/shot.png", "Screenshot added by Zaryx • 1/1")))
    DeathCard.readComponents(DeathCard.components(full, Some(DeathCard.cameraButton("Mira Solvane", 1L)),
      DeathCard.screenshotRow("Mira Solvane", 1L, "m", 0, 1, deletable = true))) shouldBe Some(full)
    DeathCard.readComponents(DeathCard.components(post.copy(colour = DeathCard.NoEdgeColour), None, Nil)) shouldBe
      Some(post.copy(colour = DeathCard.NoEdgeColour))
  }

  test("an embed posted before the switch reads back as the same post") {
    val embed = new EmbedBuilder()
      .setTitle(":snowflake: Mira Solvane :snowflake:", "https://www.tibia.com/community/?name=Mira+Solvane")
      .setDescription(post.description)
      .setThumbnail(post.thumbnail.get)
      .setColor(ally)
      .setImage("https://cdn/shot.png")
      .setFooter("Screenshot added by Zaryx • 1/1")
      .build()
    DeathCard.readEmbed(embed, "<@&42>", "Mira Solvane") shouldBe
      post.copy(ping = Some("<@&42>"), screenshot = Some(Screenshot("https://cdn/shot.png", "Screenshot added by Zaryx • 1/1")))
  }

  test("an embed with no colour and no text reads as a no-edge post with no ping") {
    val embed = new EmbedBuilder().setTitle(":shield: Bob :shield:", "https://x").setDescription("Died").build()
    val read = DeathCard.readEmbed(embed, "", "Bob")
    read.colour shouldBe DeathCard.NoEdgeColour
    read.ping shouldBe None
    read.title shouldBe ":shield: [Bob](https://x) :shield:"
  }
}
