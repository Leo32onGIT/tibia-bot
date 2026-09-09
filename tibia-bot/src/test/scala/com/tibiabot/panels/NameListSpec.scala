package com.tibiabot.panels

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NameListSpec extends AnyFunSuite with Matchers {

  test("one name per line is the ordinary case") {
    NameList.parse("Bubble\nEternal Oblivion\nCharm") shouldBe
      List("Bubble", "Eternal Oblivion", "Charm")
  }

  test("blank lines and stray whitespace are dropped") {
    NameList.parse("  Bubble  \n\n\n   \n Charm \n") shouldBe List("Bubble", "Charm")
  }

  // A spreadsheet column pastes as newlines; a Discord message somebody typed
  // pastes as commas; a multi-column copy pastes as tabs.
  test("commas, semicolons and tabs separate names too") {
    NameList.parse("Bubble, Charm; Vidar\tArieswar") shouldBe
      List("Bubble", "Charm", "Vidar", "Arieswar")
  }

  test("bullets and numbering are stripped from the front of a line") {
    NameList.parse("- Bubble\n* Charm\n1. Vidar\n12) Arieswar\n• Zyzz") shouldBe
      List("Bubble", "Charm", "Vidar", "Arieswar", "Zyzz")
  }

  test("a hyphen inside a name survives") {
    NameList.parse("Kharon-Ur\n- Kharon-Ur II") shouldBe List("Kharon-Ur", "Kharon-Ur II")
  }

  test("quotes, backticks and Discord bold are stripped from both ends") {
    NameList.parse("\"Bubble\"\n`Charm`\n**Vidar**") shouldBe List("Bubble", "Charm", "Vidar")
  }

  // Case is left alone: the lists match case-insensitively and the API echoes
  // back the real capitalisation, so lowering here would only uglify the reply.
  test("duplicates are dropped case-insensitively, keeping the first spelling") {
    NameList.parse("Bubble\nbubble\nBUBBLE\nCharm") shouldBe List("Bubble", "Charm")
  }

  test("names too short or too long to be real are dropped before any lookup") {
    val tooLong = "a" * 30
    NameList.parse(s"A\nBubble\n$tooLong") shouldBe List("Bubble")
  }

  test("empty and null input give nothing rather than throwing") {
    NameList.parse("") shouldBe empty
    NameList.parse(null) shouldBe empty
  }

  /** The ceiling splits rather than truncates, so the caller can say what it
   *  left — silently processing the first hundred of a hundred and forty is the
   *  version of this that loses names without telling anybody. */
  test("take splits at the ceiling instead of dropping the remainder") {
    val names = (1 to 10).map(i => s"Name$i").toList
    val (taken, left) = NameList.take(names, 4)
    taken shouldBe names.take(4)
    left shouldBe names.drop(4)
  }
}
