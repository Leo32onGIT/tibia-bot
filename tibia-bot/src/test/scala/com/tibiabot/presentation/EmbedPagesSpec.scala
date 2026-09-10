package com.tibiabot.presentation

import net.dv8tion.jda.api.EmbedBuilder
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Splitting a post that outgrows Discord's limits, without losing any of it. */
class EmbedPagesSpec extends AnyFunSuite with Matchers {

  private def lines(count: Int, width: Int = 100): String =
    (1 to count).map(i => s"row $i " + "x" * width).mkString("\n")

  private def embed(length: Int) =
    new EmbedBuilder().setDescription("x" * length).build()

  // --- splitting a body ----------------------------------------------------

  test("a body that fits stays one page") {
    EmbedPages.split(lines(5)) should have size 1
  }

  test("a body that does not fit is split, and every page fits") {
    val pages = EmbedPages.split(lines(200))
    pages.size should be > 1
    pages.foreach(_.length should be <= EmbedPages.MaxDescription)
  }

  test("every line survives the split, in order") {
    val body = lines(200)
    EmbedPages.split(body).mkString("\n") shouldBe body
  }

  test("a page breaks between rows, never through one") {
    EmbedPages.split(lines(200)).foreach { page =>
      page.linesIterator.foreach(_ should startWith("row "))
    }
  }

  test("a line longer than a whole description is cut rather than left to throw") {
    // Nothing this post builds comes close; the guard is here so a surprise
    // degrades instead of failing the day's send.
    val pages = EmbedPages.split("y" * (EmbedPages.MaxDescription * 2 + 50))
    pages.size shouldBe 3
    pages.foreach(_.length should be <= EmbedPages.MaxDescription)
  }

  test("an empty body produces no page at all") {
    EmbedPages.split("") shouldBe empty
  }

  // --- building the embeds -------------------------------------------------

  test("the colour is on every page, so they read as one section") {
    val built = EmbedPages.build(123456, lines(200))
    built.size should be > 1
    built.foreach(_.getColor.getRGB & 0xFFFFFF shouldBe 123456)
  }

  test("the footer closes the last page and appears nowhere else") {
    val built = EmbedPages.build(1, lines(200), Some("a note"))
    built.size should be > 1
    built.init.foreach(_.getFooter shouldBe null)
    built.last.getFooter.getText shouldBe "a note"
  }

  test("a single page carries the footer itself") {
    val built = EmbedPages.build(1, lines(2), Some("a note"))
    built should have size 1
    built.head.getFooter.getText shouldBe "a note"
  }

  // --- packing embeds into messages ----------------------------------------

  test("embeds that fit travel together, as one entry in the channel") {
    EmbedPages.messages(List(embed(1000), embed(1000), embed(1000))) should have size 1
  }

  test("embeds that do not fit are split across messages, in order") {
    val messages = EmbedPages.messages(List(embed(3000), embed(2500), embed(2000)))
    messages should have size 2
    messages.head should have size 2
    messages.last should have size 1
    messages.foreach(_.map(_.getLength).sum should be <= EmbedPages.MaxMessage)
  }

  test("no message carries more than ten embeds") {
    val messages = EmbedPages.messages(List.fill(25)(embed(10)))
    messages.foreach(_.size should be <= EmbedPages.MaxEmbeds)
    messages.map(_.size).sum shouldBe 25
  }

  test("nothing in, nothing out") {
    EmbedPages.messages(Nil) shouldBe empty
  }

  test("no embed is lost or reordered") {
    val embeds = (1 to 20).toList.map(i => embed(500 + i))
    EmbedPages.messages(embeds).flatten shouldBe embeds
  }
}
