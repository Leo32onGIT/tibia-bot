package com.tibiabot.commands

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData, SubcommandData}
import net.dv8tion.jda.api.interactions.commands.{DefaultMemberPermissions, OptionType}

/** Slash-command schema (shape) definitions, extracted verbatim from BotApp.
 *  Pure JDA command-builder data — no behaviour, no external coupling.
 *
 *  Note: `leaderboardsCommand` is defined but intentionally NOT in `commands`
 *  or `adminCommands` (it is unregistered today); preserved as-is. */
object CommandSchemas {

  // create the command to set up the bot
  val setupCommand: SlashCommandData = Commands.slash("setup", "Setup a world to be tracked")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(new OptionData(OptionType.STRING, "world", "The world you want to track")
    .setRequired(true))

  // remove world command
  val removeCommand: SlashCommandData = Commands.slash("remove", "Remove a world from being tracked")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(new OptionData(OptionType.STRING, "world", "The world you want to remove")
    .setRequired(true))

  // hunted command
  val huntedCommand: SlashCommandData = Commands.slash("hunted", "Manage the hunted list")
    .addSubcommands(
      new SubcommandData("guild", "Manage guilds in the hunted list")
      .addOptions(
        new OptionData(OptionType.STRING, "option", "Would you like to add or remove a guild?").setRequired(true)
          .addChoices(
            new Choice("add", "add"),
            new Choice("remove", "remove")
          ),
        new OptionData(OptionType.STRING, "name", "The guild name you want to add to the hunted list").setRequired(true)
        ),
      new SubcommandData("player", "Manage players in the hunted list")
      .addOptions(
        new OptionData(OptionType.STRING, "option", "Would you like to add or remove a player?").setRequired(true)
          .addChoices(
            new Choice("add", "add"),
            new Choice("remove", "remove")
          ),
        new OptionData(OptionType.STRING, "name", "The player name you want to add to the hunted list").setRequired(true),
        new OptionData(OptionType.STRING, "reason", "You can add a reason when players are added to the hunted list")
        ),
      new SubcommandData("list", "List players & guilds in the hunted list"),
      new SubcommandData("clear", "Remove all players and guilds from the hunted list"),
      new SubcommandData("info", "Show detailed info on a hunted player")
        .addOptions(new OptionData(OptionType.STRING, "name", "The player name you want to check").setRequired(true)
      ),
      new SubcommandData("autodetect", "Configure the auto-detection on or off")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to toggle it on or off?").setRequired(true)
            .addChoices(
              new Choice("on", "on"),
              new Choice("off", "off")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        ),
      new SubcommandData("levels", "Show or hide hunted levels")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide hunted levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        ),
      new SubcommandData("deaths", "Show or hide hunted deaths")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide hunted deaths?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
      )

  // allies command
  val alliesCommand: SlashCommandData = Commands.slash("allies", "Manage the allies list")
    .addSubcommands(
      new SubcommandData("guild", "Manage guilds in the allies list")
      .addOptions(
        new OptionData(OptionType.STRING, "option", "Would you like to add or remove a guild?").setRequired(true)
          .addChoices(
            new Choice("add", "add"),
            new Choice("remove", "remove")
          ),
        new OptionData(OptionType.STRING, "name", "The guild name you want to add to the allies list").setRequired(true)
        ),
      new SubcommandData("player", "Manage players in the allies list")
      .addOptions(
        new OptionData(OptionType.STRING, "option", "Would you like to add or remove a player?").setRequired(true)
          .addChoices(
            new Choice("add", "add"),
            new Choice("remove", "remove")
          ),
        new OptionData(OptionType.STRING, "name", "The player name you want to add to the allies list").setRequired(true)
        ),
      new SubcommandData("list", "List players & guilds in the allies list"),
      new SubcommandData("clear", "Remove all players and guilds from the allies list"),
      new SubcommandData("info", "Show detailed info on a allied player")
        .addOptions(new OptionData(OptionType.STRING, "name", "The player name you want to check").setRequired(true)
      ),
      new SubcommandData("levels", "Show or hide ally levels")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide ally levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        ),
      new SubcommandData("deaths", "Show or hide ally deaths")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide ally levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
      )

  // neutrals command
  val neutralsCommand: SlashCommandData = Commands.slash("neutral", "Configuration options for neutrals")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("levels", "Show or hide neutral levels")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide neutral levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        ),
      new SubcommandData("deaths", "Show or hide neutral deaths")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide neutral levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
    )

  // fullbless command
  val fullblessCommand: SlashCommandData = Commands.slash("fullbless", "Modify the level at which enemy fullblesses poke")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(
      new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true),
      new OptionData(OptionType.INTEGER, "level", "The minimum level you want to set for fullbless pokes").setRequired(true)
        .setMinValue(1)
        .setMaxValue(4000)
    )

  // leaderboards command
  val leaderboardsCommand: SlashCommandData = Commands.slash("leaderboards", "Modify the level at which enemy fullblesses poke")
    .addOptions(
      new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
    )

  // minimum levels/deaths command
  val filterCommand: SlashCommandData = Commands.slash("filter", "Set a minimum level for the levels or deaths channels")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("levels", "Hide events in the levels channel if the character is below a certain level")
      .addOptions(
        new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true),
        new OptionData(OptionType.INTEGER, "level", "The minimum level you want to set for the levels channel").setRequired(true)
          .setMinValue(1)
          .setMaxValue(4000)
      ),
      new SubcommandData("deaths", "Hide events in the deaths channel if the character is below a certain level")
      .addOptions(
        new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true),
        new OptionData(OptionType.INTEGER, "level", "The minimum level you want to set for the deaths channel").setRequired(true)
          .setMinValue(1)
          .setMaxValue(4000)
      )
    )

  // admin command
  val adminCommand: SlashCommandData = Commands.slash("admin", "Commands only available to the bot creator")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("leave", "Force the bot to leave a specific discord")
      .addOptions(
        new OptionData(OptionType.STRING, "guildid", "The guild ID you want the bot to leave").setRequired(true),
        new OptionData(OptionType.STRING, "reason", "What reason do you want to leave for the discord owner?").setRequired(true)
      ),
      new SubcommandData("info", "get discord info"),
      new SubcommandData("dreamscar", "resync dreamscar wiki info"),
      new SubcommandData("worldlist", "get discord info"),
      new SubcommandData("message", "Send a message to a specific discord")
      .addOptions(
        new OptionData(OptionType.STRING, "guildid", "The guild ID you want the bot to leave").setRequired(true),
        new OptionData(OptionType.STRING, "message", "What message do you want to leave for the discord owner?").setRequired(true)
      )
    )

  // exiva command
  val exivaCommand: SlashCommandData = Commands.slash("exiva", "Show or hide exiva lists on death posts")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("deaths", "Show or hide the exiva list in the deaths channel")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide the exiva list?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
    )

  // help command
  val helpCommand: SlashCommandData = Commands.slash("help", "Resend the welcome message & basic getting started information")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))

  // recreate channel command
  val repairCommand: SlashCommandData = Commands.slash("repair", "Repair & recreate channels that have been deleted for a specific world")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
      .addOptions(
        new OptionData(OptionType.STRING, "world", "What world are you trying to recreate channels for?").setRequired(true),
      )

  // set galthen satchel reminder
  val galthenCommand: SlashCommandData = Commands.slash("galthen", "Use this to set a galthen satchel cooldown timer")
    .addSubcommands(
      new SubcommandData("satchel", "Use this to set a galthen satchel cooldown timer")
      .addOptions(
        new OptionData(OptionType.STRING, "character", "What character/tag is this for?")
      )
    )

  // online list config  command
  val onlineCombineCommand: SlashCommandData = Commands.slash("online", "Configure how the online list is displayed")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("list", "Configure the online list")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to combine the list into one channel or keep them separate?").setRequired(true)
            .addChoices(
              new Choice("separate", "separate"),
              new Choice("combine", "combine")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
    )

  // boosted notifications command
  val boostedCommand: SlashCommandData = Commands.slash("boosted", "Turn off these notifications or filter them")
    .addOptions(
      new OptionData(OptionType.STRING, "option", "Would you like to add/remove a boss or creature?").setRequired(true)
        .addChoices(
          new Choice("list", "list"),
          new Choice("disable", "disable")
        )
    )

  /** Commands registered in normal guilds. */
  val commands: List[SlashCommandData] = List(setupCommand, removeCommand, huntedCommand, alliesCommand, neutralsCommand, fullblessCommand, filterCommand, exivaCommand, helpCommand, repairCommand, onlineCombineCommand, boostedCommand, galthenCommand)

  /** Commands registered in the bot-owner guilds (adds /admin). */
  val adminCommands: List[SlashCommandData] = commands :+ adminCommand
}
