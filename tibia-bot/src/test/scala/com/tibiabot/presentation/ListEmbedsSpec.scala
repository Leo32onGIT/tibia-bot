package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ListEmbedsSpec extends AnyFunSuite with Matchers {

  test("pack accumulates lines into <=limit chunks, breaking when one would overflow") {
    ListEmbeds.pack(List("aaa", "bbb", "ccc"), 10) shouldBe List("\naaa\nbbb", "ccc")
    ListEmbeds.pack(List("a", "b"), 100) shouldBe List("\na\nb")
    ListEmbeds.pack(Nil, 100) shouldBe List("")
    ListEmbeds.pack(List("x", "y", "z"), 100).flatMap(_.split('\n')).filter(_.nonEmpty) shouldBe List("x", "y", "z")
  }

  test("every chunk stays within the limit and all the content survives") {
    val many = (1 to 50).map(i => s"line-$i").toList
    val chunks = ListEmbeds.pack(many, 20)
    chunks.foreach(_.length should be <= 20)
    chunks.flatMap(_.split('\n')).filter(_.nonEmpty) should contain allElementsOf many
  }
}
