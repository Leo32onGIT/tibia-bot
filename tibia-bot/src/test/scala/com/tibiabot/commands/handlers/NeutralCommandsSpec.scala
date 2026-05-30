package com.tibiabot.commands.handlers

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NeutralCommandsSpec extends AnyFunSuite with Matchers {

  test("isValidEmoji accepts standard emojis and rejects custom/text/empty") {
    NeutralCommands.isValidEmoji("😀") shouldBe true   // grinning face
    NeutralCommands.isValidEmoji("⚔️") shouldBe true   // crossed swords
    NeutralCommands.isValidEmoji("<:custom:123>") shouldBe false // custom emoji
    NeutralCommands.isValidEmoji("hello") shouldBe false
    NeutralCommands.isValidEmoji("") shouldBe false
  }

  test("sanitizeLabel keeps letters, digits and spaces, strips the rest and trims") {
    NeutralCommands.sanitizeLabel("  Hello, World! 123  ") shouldBe "Hello World 123"
    NeutralCommands.sanitizeLabel("a@#b") shouldBe "ab"
  }
}
