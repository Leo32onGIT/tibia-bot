package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OnlineListEmbedsSpec extends AnyFunSuite with Matchers {

  test("durationString formats seconds as backticked minutes under an hour") {
    OnlineListEmbeds.durationString(0) shouldBe "`0min`"
    OnlineListEmbeds.durationString(59) shouldBe "`0min`"
    OnlineListEmbeds.durationString(60) shouldBe "`1min`"
    OnlineListEmbeds.durationString(3540) shouldBe "`59min`"
  }

  test("durationString switches to hours+minutes at 60 minutes") {
    OnlineListEmbeds.durationString(3600) shouldBe "`1hr 0min`"
    OnlineListEmbeds.durationString(3660) shouldBe "`1hr 1min`"
    OnlineListEmbeds.durationString(7320) shouldBe "`2hr 2min`"
  }

  test("baseName strips the bot-appended '-<count>' suffix") {
    OnlineListEmbeds.baseName("online-42", "online") shouldBe "online"
    OnlineListEmbeds.baseName("ɴᴇᴍᴇsɪs-5", "enemies") shouldBe "ɴᴇᴍᴇsɪs"
  }

  test("baseName keeps a name that has no count suffix") {
    OnlineListEmbeds.baseName("allies", "allies") shouldBe "allies"
  }

  test("baseName only strips a trailing -digits, preserving internal hyphens and bare dashes") {
    OnlineListEmbeds.baseName("my-cool-list-99", "online") shouldBe "my-cool-list"
    OnlineListEmbeds.baseName("online-", "online") shouldBe "online-"
  }

  test("categoryName shows both counts with the separator when both are positive") {
    OnlineListEmbeds.categoryName("Antica", 5, 2) shouldBe "Antica・🤍5💀2"
  }

  test("categoryName omits a zero count but keeps the separator while the other is positive") {
    OnlineListEmbeds.categoryName("Antica", 5, 0) shouldBe "Antica・🤍5"
    OnlineListEmbeds.categoryName("Antica", 0, 3) shouldBe "Antica・💀3"
  }

  test("categoryName drops the separator entirely when both counts are zero") {
    OnlineListEmbeds.categoryName("Antica", 0, 0) shouldBe "Antica"
  }

  // --- packFields ---

  test("packFields always returns at least one (empty) field") {
    OnlineListEmbeds.packFields(Nil) shouldBe List("")
  }

  test("packFields newline-joins short lines into a single field") {
    OnlineListEmbeds.packFields(List("a", "b", "c")) shouldBe List("\na\nb\nc")
  }

  test("packFields starts a new field at a section header, but not when the field is still empty") {
    OnlineListEmbeds.packFields(List("a", "### Neutrals", "b")) shouldBe List("\na", "### Neutrals\nb")
    OnlineListEmbeds.packFields(List("### Neutrals", "b")) shouldBe List("\n### Neutrals\nb")
  }

  test("packFields keeps a guild header ('### [') with the preceding lines (no section break)") {
    OnlineListEmbeds.packFields(List("a", "### [Guild](u)", "b")) shouldBe List("\na\n### [Guild](u)\nb")
  }

  test("packFields breaks to a new field once a line would reach 4060 chars") {
    val packed = OnlineListEmbeds.packFields(List("x" * 4000, "y" * 100))
    packed should have size 2
    packed.head shouldBe "\n" + ("x" * 4000)
    packed.last shouldBe "y" * 100
  }

  test("packFields breaks earlier (3850) when the incoming line is a guild header") {
    val packed = OnlineListEmbeds.packFields(List("x" * 3840, "### [G](u)"))
    packed should have size 2
    packed.last shouldBe "### [G](u)"
  }
}
