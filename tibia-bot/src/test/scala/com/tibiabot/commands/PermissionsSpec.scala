package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PermissionsSpec extends AnyFunSuite with Matchers {

  test("isBotCreator only matches the exact application-owner id") {
    Permissions.isBotCreator("123", "123") shouldBe true
    Permissions.isBotCreator("123", "456") shouldBe false
  }

  test("isBotCreator denies everyone when the owner id is unknown (empty)") {
    Permissions.isBotCreator("123", "") shouldBe false
    Permissions.isBotCreator("", "") shouldBe false
  }
}
