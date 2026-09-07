package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions

import scala.jdk.CollectionConverters._

class CommandSchemasSpec extends AnyFunSuite with Matchers {

  test("registered commands have the expected names") {
    CommandSchemas.commands.map(_.getName) should contain theSameElementsAs List(
      "setup", "remove", "repair", "help", "hunted", "allies", "settings",
      "boosted", "galthen", "patreon", "stamina", "bookings", "lootsplit")
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

  test("settings folds the five per-world setting commands into one root") {
    CommandSchemas.settingsCommand.getSubcommands.asScala.map(_.getName) should contain theSameElementsAs
      List("fullbless", "exiva", "layout")
    CommandSchemas.settingsCommand.getSubcommandGroups.asScala.map(_.getName) should contain theSameElementsAs
      List("neutral", "filter")
  }

  test("settings groups expose the subcommands their old commands had") {
    def group(name: String) =
      CommandSchemas.settingsCommand.getSubcommandGroups.asScala.find(_.getName == name).get
    group("neutral").getSubcommands.asScala.map(_.getName) should contain theSameElementsAs
      List("levels", "deaths", "activity")
    group("filter").getSubcommands.asScala.map(_.getName) should contain theSameElementsAs
      List("levels", "deaths", "online")
  }

  // Manage Server is what all five folded commands each carried, and the root
  // has to keep it: it is the only gate on any of them (no handler re-checks).
  test("settings keeps the Manage Server gate its commands had") {
    CommandSchemas.settingsCommand.getDefaultPermissions shouldBe
      DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)
  }

  test("admin exposes the expected subcommands") {
    CommandSchemas.adminCommand.getSubcommands.asScala.map(_.getName) should contain theSameElementsAs
      List("leave", "info", "dreamscar", "boosted", "worldlist", "message")
  }

  test("initialCommands is the minimal set visible before any world is configured") {
    CommandSchemas.initialCommands.map(_.getName) should contain theSameElementsAs
      List("setup", "help", "galthen", "boosted", "patreon", "lootsplit")
  }

  test("commands is exactly initialCommands plus worldConfigCommands") {
    CommandSchemas.commands.map(_.getName) should contain theSameElementsAs
      (CommandSchemas.initialCommands ++ CommandSchemas.worldConfigCommands).map(_.getName)
  }

  test("commandsFor: a support guild always gets adminCommands, regardless of world-config state") {
    CommandSchemas.commandsFor(867319250708463628L, hasWorldConfigured = false, respawnEnabled = true) shouldBe CommandSchemas.adminCommands
    CommandSchemas.commandsFor(1082484147492237515L, hasWorldConfigured = true, respawnEnabled = true) shouldBe CommandSchemas.adminCommands
  }

  test("commandsFor: a non-support guild with no world configured gets the minimal set") {
    CommandSchemas.commandsFor(111L, hasWorldConfigured = false, respawnEnabled = true) shouldBe CommandSchemas.initialCommands
  }

  test("commandsFor: a non-support guild with a world configured gets the full set") {
    CommandSchemas.commandsFor(111L, hasWorldConfigured = true, respawnEnabled = true) shouldBe CommandSchemas.commands
  }

  // /stamina is in the schema lists unconditionally (so SlashRoutingSpec still
  // covers it), but must not reach Discord while the feature is switched off —
  // prod and DEV run the same image, and a visible command the bot refuses to
  // service is worse than no command.
  test("commandsFor: the respawn commands are withheld unless the feature is enabled") {
    val off = CommandSchemas.commandsFor(111L, hasWorldConfigured = true).map(_.getName)
    off should contain noneOf ("stamina", "bookings")
    CommandSchemas.commandsFor(867319250708463628L, hasWorldConfigured = true)
      .map(_.getName) should contain noneOf ("stamina", "bookings")
    CommandSchemas.commandsFor(111L, hasWorldConfigured = true, respawnEnabled = true)
      .map(_.getName) should contain allOf ("stamina", "bookings")
  }

  test("commandsFor: withholding them leaves every other command untouched") {
    CommandSchemas.commandsFor(111L, hasWorldConfigured = true) shouldBe
      CommandSchemas.commands.filterNot(c => Set("stamina", "bookings").contains(c.getName))
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
