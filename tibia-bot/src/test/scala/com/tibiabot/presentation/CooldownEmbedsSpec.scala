package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CooldownEmbedsSpec extends AnyFunSuite with Matchers {

  test("short content is joined with newlines and not truncated") {
    CooldownEmbeds.truncate(Seq("ab", "cd"), limit = 100) shouldBe "ab\ncd"
  }

  test("empty and single-line inputs") {
    CooldownEmbeds.truncate(Seq.empty) shouldBe ""
    CooldownEmbeds.truncate(Seq("only line"), limit = 100) shouldBe "only line"
  }

  test("over the limit, truncation cuts back to the last whole line") {
    // "aaa\nbbb\nccc" is 11 chars; first 5 = "aaa\nb"; last newline at index 3 -> "aaa"
    CooldownEmbeds.truncate(Seq("aaa", "bbb", "ccc"), limit = 5) shouldBe "aaa"
  }

  test("over the limit with no newline in range keeps the hard cut") {
    // "aaaaaa" has no newline within the first 3 chars
    CooldownEmbeds.truncate(Seq("aaaaaa"), limit = 3) shouldBe "aaa"
  }

  test("default limit keeps output within 4050 characters") {
    val many = (1 to 1000).map(i => s"line number $i")
    val out = CooldownEmbeds.truncate(many)
    out.length should be <= 4050
    out should not include "\n\n" // no empty fragments introduced
  }
}
