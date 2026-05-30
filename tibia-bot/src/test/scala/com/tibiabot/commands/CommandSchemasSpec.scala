package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class CommandSchemasSpec extends AnyFunSuite with Matchers {

  test("registered commands have the expected names") {
    CommandSchemas.commands.map(_.getName) should contain theSameElementsAs List(
      "setup", "remove", "hunted", "allies", "neutral", "fullbless",
      "filter", "exiva", "help", "repair", "online", "boosted", "galthen")
  }

  test("admin command list adds /admin to the normal set") {
    CommandSchemas.adminCommands.map(_.getName) shouldBe
      CommandSchemas.commands.map(_.getName) :+ "admin"
  }

  test("setup requires a single 'world' string option") {
    val opts = CommandSchemas.setupCommand.getOptions.asScala
    opts.map(_.getName) shouldBe List("world")
    opts.head.isRequired shouldBe true
  }

  test("hunted exposes the expected subcommands") {
    CommandSchemas.huntedCommand.getSubcommands.asScala.map(_.getName) should contain allOf
      ("guild", "player", "list", "clear", "info", "autodetect", "levels", "deaths")
  }

  test("leaderboards is defined but intentionally not registered") {
    CommandSchemas.leaderboardsCommand.getName shouldBe "leaderboards"
    CommandSchemas.commands.map(_.getName) should not contain "leaderboards"
    CommandSchemas.adminCommands.map(_.getName) should not contain "leaderboards"
  }
}
