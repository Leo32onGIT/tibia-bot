package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ListEmbedsSpec extends AnyFunSuite with Matchers {

  test("a short list yields one embed carrying the thumbnail and colour") {
    val embeds = ListEmbeds.paginate(List("aaa"), "https://x/thumb.gif", 3092790)
    embeds should have size 1
    embeds.head.getDescription shouldBe "\naaa"
    embeds.head.getThumbnail.getUrl shouldBe "https://x/thumb.gif"
    (embeds.head.getColor.getRGB & 0xFFFFFF) shouldBe 3092790
  }

  test("lines exceeding the limit split across embeds; only the first has the thumbnail") {
    val embeds = ListEmbeds.paginate(List("aaa", "bbb", "ccc"), "https://x/thumb.gif", 42, limit = 10)
    embeds should have size 2
    embeds.head.getDescription shouldBe "\naaa\nbbb"
    embeds.head.getThumbnail.getUrl shouldBe "https://x/thumb.gif"
    embeds(1).getDescription shouldBe "ccc"
    embeds(1).getThumbnail shouldBe null
    embeds.foreach(e => (e.getColor.getRGB & 0xFFFFFF) shouldBe 42)
  }

  test("every embed description stays within the limit") {
    val many = (1 to 50).map(i => s"line-$i").toList
    val embeds = ListEmbeds.paginate(many, "https://x/t.gif", 1, limit = 20)
    embeds.foreach(_.getDescription.length should be <= 20)
    // all the content survives across the embeds
    embeds.flatMap(_.getDescription.split('\n')).filter(_.nonEmpty) should contain allElementsOf many
  }

  test("pack accumulates lines into <=limit chunks, breaking when one would overflow") {
    ListEmbeds.pack(List("aaa", "bbb", "ccc"), 10) shouldBe List("\naaa\nbbb", "ccc")
    ListEmbeds.pack(List("a", "b"), 100) shouldBe List("\na\nb")
    ListEmbeds.pack(Nil, 100) shouldBe List("")
    ListEmbeds.pack(List("x", "y", "z"), 100).flatMap(_.split('\n')).filter(_.nonEmpty) shouldBe List("x", "y", "z")
  }

  test("an empty list still yields a single embed with the thumbnail (JDA nulls the empty description)") {
    val embeds = ListEmbeds.paginate(Nil, "https://x/thumb.gif", 42)
    embeds should have size 1
    embeds.head.getDescription shouldBe null
    embeds.head.getThumbnail.getUrl shouldBe "https://x/thumb.gif"
  }
}
