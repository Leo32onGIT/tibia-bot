package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class CommandSchemasSpec extends AnyFunSuite with Matchers {

  test("registered commands have the expected names") {
    CommandSchemas.commands.map(_.getName) should contain theSameElementsAs List(
      "setup", "remove", "hunted", "allies", "neutral", "fullbless",
      "filter", "exiva", "help", "repair", "online", "boosted", "galthen", "patreon")
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

  test("initialCommands is the minimal set visible before any world is configured") {
    CommandSchemas.initialCommands.map(_.getName) should contain theSameElementsAs
      List("setup", "help", "galthen", "boosted", "patreon")
  }

  test("commands is exactly initialCommands plus worldConfigCommands") {
    CommandSchemas.commands.map(_.getName) should contain theSameElementsAs
      (CommandSchemas.initialCommands ++ CommandSchemas.worldConfigCommands).map(_.getName)
  }

  test("commandsFor: a support guild always gets adminCommands, regardless of world-config state") {
    CommandSchemas.commandsFor(867319250708463628L, hasWorldConfigured = false) shouldBe CommandSchemas.adminCommands
    CommandSchemas.commandsFor(1082484147492237515L, hasWorldConfigured = true) shouldBe CommandSchemas.adminCommands
  }

  test("commandsFor: a non-support guild with no world configured gets the minimal set") {
    CommandSchemas.commandsFor(111L, hasWorldConfigured = false) shouldBe CommandSchemas.initialCommands
  }

  test("commandsFor: a non-support guild with a world configured gets the full set") {
    CommandSchemas.commandsFor(111L, hasWorldConfigured = true) shouldBe CommandSchemas.commands
  }

  test("commandsFor: excludeAll returns an empty list regardless of the guild's own state") {
    CommandSchemas.commandsFor(867319250708463628L, hasWorldConfigured = false, excludeAll = true) shouldBe Nil
    CommandSchemas.commandsFor(111L, hasWorldConfigured = true, excludeAll = true) shouldBe Nil
  }

  test("excludedFromCommands: any identity other than the designated owner is excluded from a restricted guild") {
    CommandSchemas.excludedFromCommands(867319250708463628L, "1193678088165404807") shouldBe false // Blue, the owner
    CommandSchemas.excludedFromCommands(867319250708463628L, "1438767287447584893") shouldBe true // Red
    CommandSchemas.excludedFromCommands(867319250708463628L, "1064479962515644507") shouldBe true // DEV
  }

  test("excludedFromCommands: an unrestricted guild never excludes anyone") {
    CommandSchemas.excludedFromCommands(111L, "1064479962515644507") shouldBe false
  }
}
