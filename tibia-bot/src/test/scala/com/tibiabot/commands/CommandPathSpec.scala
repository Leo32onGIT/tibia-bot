package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CommandPathSpec extends AnyFunSuite with Matchers {

  test("a command with no subcommands is just its name") {
    CommandPath.of("help", None, None) shouldBe "help"
  }

  test("a subcommand is appended to the name") {
    CommandPath.of("hunted", None, Some("player")) shouldBe "hunted player"
  }

  test("a grouped subcommand keeps group then subcommand order") {
    CommandPath.of("settings", Some("neutral"), Some("levels")) shouldBe "settings neutral levels"
  }
}
