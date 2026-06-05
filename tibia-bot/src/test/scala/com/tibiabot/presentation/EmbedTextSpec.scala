package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class EmbedTextSpec extends AnyFunSuite with Matchers {

  private def longBody = (1 to 500).map(i => s"line-$i-xxxxxxxxxx").mkString("\n")

  test("text that fits is returned unchanged when there is no command") {
    EmbedText.fit("a\nb\nc") shouldBe "a\nb\nc"
  }

  test("text that fits appends the command line after a blank line") {
    EmbedText.fit("list", "done") shouldBe "list\n\ndone"
  }

  test("overflowing text is cut at a line boundary and gets an overflow marker") {
    val body = longBody
    body.length should be > 4096
    val out = EmbedText.fit(body)
    out.length should be < body.length
    out should endWith ("cannot display any more results`*")
    out should not include "line-500" // tail content past the cut is dropped
    // the kept portion is a genuine prefix of the original (cut on a newline)
    val kept = out.substring(0, out.indexOf("\n\n*`..."))
    body should startWith (kept)
  }

  test("overflowing text keeps the command line after the marker") {
    val out = EmbedText.fit(longBody, "added Oberon")
    out should include ("cannot display any more results")
    out should endWith ("added Oberon")
  }
}
