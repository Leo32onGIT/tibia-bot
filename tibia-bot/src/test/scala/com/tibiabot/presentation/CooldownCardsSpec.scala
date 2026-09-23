package com.tibiabot.presentation

import com.tibiabot.cooldowns.CooldownIds
import com.tibiabot.domain.{CooldownKind, CooldownStamp}
import net.dv8tion.jda.api.components.Component
import net.dv8tion.jda.api.components.container.Container
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import scala.jdk.CollectionConverters._

/** The cooldown tracker in the notifications channel and somebody's own card.
 *  The emoji are passed in so these run without loading Config. */
class CooldownCardsSpec extends AnyFunSuite with Matchers {

  private val emojiOf: CooldownKind => String = {
    case CooldownKind.Satchel    => "<:satchel:1>"
    case CooldownKind.DragonHead => "<:jadedragonhead:2>"
  }

  private def stamp(kind: CooldownKind, tag: String) =
    CooldownStamp("u1", kind, ZonedDateTime.parse("2026-09-24T10:00:00Z"), tag)

  private def children(c: Container) = c.getComponents.asScala.toList

  private def rowButtons(c: Container) =
    children(c).filter(_.getType == Component.Type.ACTION_ROW).map(_.asActionRow.getButtons.asScala.toList)

  private def texts(c: Container): String = children(c).flatMap { child =>
    child.getType match {
      case Component.Type.TEXT_DISPLAY => List(child.asTextDisplay.getContent)
      case Component.Type.SECTION      => child.asSection.getContentComponents.asScala.map(_.asTextDisplay.getContent)
      case _                           => Nil
    }
  }.mkString("\n")

  test("the tracker has one emoji-only button per item, opening your own cooldowns") {
    val buttons = children(CooldownEmbeds.tracker(emojiOf))
      .filter(_.getType == Component.Type.SECTION).map(_.asSection.getAccessory)
      .filter(_.getType == Component.Type.BUTTON).map(_.asButton)
    buttons.map(_.getCustomId) shouldBe CooldownKind.all.map(CooldownIds.button(_, CooldownIds.Action.Open))
    buttons.map(b => Option(b.getLabel).getOrElse("")).distinct shouldBe List("")
    texts(CooldownEmbeds.tracker(emojiOf)) should include("30-day cooldown")
    texts(CooldownEmbeds.tracker(emojiOf)) should include("14-day cooldown")
  }

  test("a satchel is collected and a dragon head used") {
    val rows = rowButtons(CooldownEmbeds.personal(_ => Nil, "Beams", emojiOf = emojiOf))
    rows.map(_.head.getLabel) shouldBe List("Collected", "Used")
    CooldownEmbeds.doneLabel(CooldownKind.Satchel) shouldBe "Collected"
    CooldownEmbeds.doneLabel(CooldownKind.DragonHead) shouldBe "Used"
  }

  test("your own cooldowns show straight away, and an item with none says so") {
    val card = CooldownEmbeds.personal({
      case CooldownKind.Satchel => List(stamp(CooldownKind.Satchel, "main"))
      case _                    => Nil
    }, "Beams", emojiOf = emojiOf)
    texts(card) should include("**`main`** — ready <t:")
    texts(card) should include("Nothing tracked yet.")
  }

  test("Remove narrows with the list: none, one taken directly, several asked which") {
    def labels(count: Int) = rowButtons(CooldownEmbeds.personal(
      k => if (k == CooldownKind.Satchel) (1 to count).toList.map(i => stamp(k, s"alt$i")) else Nil,
      "Beams", emojiOf = emojiOf)).head.map(b => (b.getLabel, CooldownIds.parse(b.getCustomId).map(_._2)))
    labels(0).map(_._1) shouldBe List("Collected", "For a character…")
    labels(1).last shouldBe ("Remove" -> Some(CooldownIds.Action.RemoveAll))
    labels(2).drop(2) shouldBe List(
      "Remove" -> Some(CooldownIds.Action.RemoveForm), "Clear All" -> Some(CooldownIds.Action.RemoveAll))
  }

  test("what just changed leads the card") {
    val card = CooldownEmbeds.personal(_ => Nil, "Beams", note = "✅ Stopped tracking Jade Dragon Head.", emojiOf = emojiOf)
    texts(card).linesIterator.toList.drop(2).head shouldBe "✅ Stopped tracking Jade Dragon Head."
  }
}
