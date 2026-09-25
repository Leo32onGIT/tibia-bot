package com.tibiabot.presentation

import com.tibiabot.presentation.StatisticsCard.Part
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** The statistics post's cards, and how a day is packed into V2 messages. */
class StatisticsCardSpec extends AnyFunSuite with Matchers {

  private val Green = 0x00FF00
  private val Red = 0xFF0000

  /** A block of `length` characters, made of lines so it can be cut between them. */
  private def block(length: Int, tag: String = "x"): String =
    (tag + "\n" + ("y" * 99 + "\n") * (length / 100)).take(length)

  private def textOf(packed: List[List[(Int, List[String])]]): List[Int] =
    packed.map(_.flatMap(_._2).map(_.length).sum)

  test("a section is its label in small caps over its rows") {
    StatisticsCard.section("Most Kills", List("a", "b")) shouldBe "-# ᴍᴏsᴛ ᴋɪʟʟs\na\nb"
  }

  test("an icon leads the label and is left out of the small caps") {
    StatisticsCard.section("High chance", List("a"), icon = ":green_circle:") shouldBe
      "-# :green_circle: ʜɪɢʜ ᴄʜᴀɴᴄᴇ\na"
  }

  test("a card is its blocks with a divider between every two, edged in its colour") {
    val card = StatisticsCard.messages(List(Part(Green, List("## Title", "-# one", "-# two")))).head.head
    card.getAccentColorRaw.intValue shouldBe Green
    card.getComponents.asScala.toList.map {
      case t: TextDisplay => t.getContent
      case _: Separator   => "---"
      case other          => other.toString
    } shouldBe List("## Title", "---", "-# one", "---", "-# two")
  }

  test("an ordinary day is whole cards, in order, as few messages as fit") {
    val packed = StatisticsCard.pack(List(
      Part(Green, List("board", "gains")),
      Part(Red, List("pvp"))))
    packed shouldBe List(List(Green -> List("board", "gains"), Red -> List("pvp")))
  }

  test("a card that won't fit in what is left starts the next message, whole") {
    val packed = StatisticsCard.pack(List(
      Part(Green, List(block(2500))),
      Part(Red, List(block(1000, "r1"), block(1000, "r2")))))
    packed.map(_.map(_._1)) shouldBe List(List(Green), List(Red))
    packed(1).head._2 should have size 2
  }

  test("a card too long for any message is cut between its sections, keeping its colour") {
    val packed = StatisticsCard.pack(List(Part(Red, List(block(1500, "a"), block(1500, "b"), block(1500, "c")))))
    packed.map(_.map(_._1)) shouldBe List(List(Red), List(Red))
    packed.flatten.flatMap(_._2).map(_.linesIterator.next()) shouldBe List("a", "b", "c")
  }

  test("no message is ever past 4,000 characters, and nothing is lost") {
    val parts = (1 to 6).toList.map(i => Part(Green + i, (1 to 4).toList.map(j => block(900, s"$i-$j"))))
    val packed = StatisticsCard.pack(parts)
    textOf(packed).foreach(_ should be <= StatisticsCard.MaxText)
    packed.flatten.flatMap(_._2).map(_.linesIterator.next()) shouldBe
      (for (i <- 1 to 6; j <- 1 to 4) yield s"$i-$j").toList
  }

  test("no message carries more than 40 components") {
    // Twenty-five one-line sections: 50 components as one card, so two messages.
    val packed = StatisticsCard.pack(List(Part(Green, (1 to 25).toList.map(i => s"s$i"))))
    packed.foreach(message => message.map(card => 2 * card._2.size).sum should be <= StatisticsCard.MaxComponents)
    packed.flatten.flatMap(_._2) should have size 25
  }

  test("a single section past the limit is cut between its rows") {
    val packed = StatisticsCard.pack(List(Part(Green, List(block(9000)))))
    packed.size should be >= 3
    textOf(packed).foreach(_ should be <= StatisticsCard.MaxText)
    packed.flatten.flatMap(_._2).mkString("\n").replace("\n", "").length shouldBe
      block(9000).replace("\n", "").length
  }

  test("a card with nothing in it is left out") {
    StatisticsCard.pack(List(Part(Green, Nil), Part(Red, List("pvp")))) shouldBe List(List(Red -> List("pvp")))
  }

  test("nothing in, nothing out") {
    StatisticsCard.messages(Nil) shouldBe empty
  }
}
