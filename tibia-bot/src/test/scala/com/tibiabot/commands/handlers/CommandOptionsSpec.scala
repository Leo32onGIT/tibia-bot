package com.tibiabot.commands.handlers

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CommandOptionsSpec extends AnyFunSuite with Matchers {

  test("fullbless level defaults to 250 and parses an explicit value") {
    FullblessCommands.parseLevel(Map.empty) shouldBe 250
    FullblessCommands.parseLevel(Map("level" -> "300")) shouldBe 300
  }

  test("filter level defaults to 8 and parses an explicit value") {
    FilterCommands.parseLevel(Map.empty) shouldBe 8
    FilterCommands.parseLevel(Map("level" -> "50")) shouldBe 50
  }
}
