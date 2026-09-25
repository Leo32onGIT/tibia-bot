package com.tibiabot.presentation

import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.thumbnail.Thumbnail
import net.dv8tion.jda.api.components.tree.MessageComponentTree
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** A world's role card. Emoji are passed in so these run without loading Config. */
class RoleCardSpec extends AnyFunSuite with Matchers {

  private val emoji: String => String = id => s"<:$id:1>"
  private def card(bountyRole: String = "5") = RoleCard.card("Antica", "1", "2", "3", "4", bountyRole, "250", emoji)

  private def rows(bountyRole: String = "5"): List[Section] =
    card(bountyRole).getComponents.asScala.toList.collect { case s: Section => s }
  private def text(row: Section): String =
    row.getContentComponents.asScala.collect { case t: TextDisplay => t.getContent }.mkString

  test("opens on the world, linked, with the line saying what the card is for") {
    card().getComponents.asScala.head.asInstanceOf[TextDisplay].getContent shouldBe
      s"### :crossed_swords: [Antica](${Urls.worldUrl("Antica")})\n-# ${RoleCard.Lead}"
  }

  test("has one row per role, its button beside it, in the order they have always been") {
    rows().map(_.getAccessory.asInstanceOf[Button].getCustomId) shouldBe
      List("fullbless", "nemesis", "allypk", "masslog", "bounty")
    text(rows().head) shouldBe "<@&1>\n-# If an enemy fullblesses and is over level `250`"
    text(rows()(3)) shouldBe "<@&4>\n-# If enough enemies log in at once on **Antica**"
  }

  test("carries no picture") {
    MessageComponentTree.of(card()).findAll(classOf[Thumbnail]) shouldBe empty
  }

  test("names the bounty role in words until the world has one, rather than as a deleted role") {
    text(rows("0").last) should startWith("**Bounty**\n")
    text(rows().last) should startWith("<@&5>\n")
  }

  test("reads the world off an embed card from before, and nothing else") {
    RoleCard.worldOfTitle(":crossed_swords: Antica :crossed_swords:") shouldBe Some("Antica")
    RoleCard.worldOfTitle(":crossed_swords: Secura") shouldBe Some("Secura")
    RoleCard.worldOfTitle("Cooldown tracker") shouldBe None
    RoleCard.worldOfTitle(":crossed_swords:") shouldBe None
  }
}
