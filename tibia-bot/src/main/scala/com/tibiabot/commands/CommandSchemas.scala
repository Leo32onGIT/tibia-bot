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

  val setupCommand: SlashCommandData = Commands.slash("setup", "Setup a world to be tracked")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(new OptionData(OptionType.STRING, "world", "The world you want to track")
    .setRequired(true))

  val removeCommand: SlashCommandData = Commands.slash("remove", "Remove a world from being tracked")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(new OptionData(OptionType.STRING, "world", "The world you want to remove")
    .setRequired(true))

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

  val fullblessCommand: SlashCommandData = Commands.slash("fullbless", "Modify the level at which enemy fullblesses poke")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(
      new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true),
      new OptionData(OptionType.INTEGER, "level", "The minimum level you want to set for fullbless pokes").setRequired(true)
        .setMinValue(1)
        .setMaxValue(4000)
    )

  val leaderboardsCommand: SlashCommandData = Commands.slash("leaderboards", "Modify the level at which enemy fullblesses poke")
    .addOptions(
      new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
    )

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

  val helpCommand: SlashCommandData = Commands.slash("help", "Resend the welcome message & basic getting started information")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))

  val repairCommand: SlashCommandData = Commands.slash("repair", "Repair & recreate channels that have been deleted for a specific world")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
      .addOptions(
        new OptionData(OptionType.STRING, "world", "What world are you trying to recreate channels for?").setRequired(true),
      )

  val galthenCommand: SlashCommandData = Commands.slash("galthen", "Use this to set a galthen satchel cooldown timer")
    .addSubcommands(
      new SubcommandData("satchel", "Use this to set a galthen satchel cooldown timer")
      .addOptions(
        new OptionData(OptionType.STRING, "character", "What character/tag is this for?")
      )
    )

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

  val boostedCommand: SlashCommandData = Commands.slash("boosted", "Turn off these notifications or filter them")
    .addOptions(
      new OptionData(OptionType.STRING, "option", "Would you like to add/remove a boss or creature?").setRequired(true)
        .addChoices(
          new Choice("list", "list"),
          new Choice("disable", "disable")
        )
    )

  /** Visible immediately when the bot joins a guild, before any world's been
   *  set up — /setup itself, /help (how do I use this bot, including how to
   *  run /setup in the first place), and galthen/boosted (personal,
   *  self-service commands unrelated to any specific world). */
  val initialCommands: List[SlashCommandData] = List(setupCommand, helpCommand, galthenCommand, boostedCommand)

  /** Only meaningful once at least one world is tracked in the guild — added
   *  on top of initialCommands once /setup first succeeds there. remove/
   *  repair move here too: both act on a world's channels, which don't
   *  exist until /setup has run at least once. */
  val worldConfigCommands: List[SlashCommandData] = List(removeCommand, repairCommand, huntedCommand, alliesCommand, neutralsCommand, fullblessCommand, filterCommand, exivaCommand, onlineCombineCommand)

  /** Commands registered in normal guilds once a world has been set up. */
  val commands: List[SlashCommandData] = initialCommands ++ worldConfigCommands

  /** Commands registered in the bot-owner guilds (adds /admin). */
  val adminCommands: List[SlashCommandData] = commands :+ adminCommand

  private val supportGuildIds: Set[Long] = Set(867319250708463628L, 1082484147492237515L)

  /** The single place this decision is made — reused by the boot-time
   *  registration loop, onGuildJoin, and ChannelService's post-/setup
   *  upgrade, so a support guild with no world yet configured (were that to
   *  ever happen) still correctly gets adminCommands, not just commands. */
  def commandsFor(guildId: Long, hasWorldConfigured: Boolean): List[SlashCommandData] =
    if (supportGuildIds.contains(guildId)) adminCommands
    else if (hasWorldConfigured) commands
    else initialCommands
}
