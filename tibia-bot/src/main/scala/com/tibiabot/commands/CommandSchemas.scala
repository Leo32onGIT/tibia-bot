package com.tibiabot.commands

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData, SubcommandData, SubcommandGroupData}
import net.dv8tion.jda.api.interactions.commands.{DefaultMemberPermissions, OptionType}

/** Slash-command schema (shape) definitions, extracted verbatim from BotApp.
 *  Pure JDA command-builder data — no behaviour, no external coupling. */
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
      new SubcommandData("boosted", "Repost the boosted boss/creature message in every discord"),
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

  val patreonCommand: SlashCommandData = Commands.slash("patreon", "View or manage your own Patreon seats")

  val boostedCommand: SlashCommandData = Commands.slash("boosted", "Turn off these notifications or filter them")
    .addOptions(
      new OptionData(OptionType.STRING, "option", "Would you like to add/remove a boss or creature?").setRequired(true)
        .addChoices(
          new Choice("list", "list"),
          new Choice("disable", "disable")
        )
    )

  /** The respawn claim system's only slash command.
   *
   *  Everything else it does is a button or a modal on the spawns forum, on the
   *  card for the spawn being acted on. Stamina is the exception: it belongs to
   *  the member rather than to any one spawn, so there is no card for it to live
   *  on. */
  val staminaCommand: SlashCommandData =
    Commands.slash("stamina", "Show your claim stamina and what's using it")

  /** A member's own bookings, across every spawn. The Book button on a spawn's
   *  card only ever shows that spawn's, so there is nowhere else to see them all
   *  — and nowhere else to clear them in one go. */
  val bookingsCommand: SlashCommandData =
    Commands.slash("bookings", "Show the respawn slots you have booked")

  /** Visible immediately when the bot joins a guild, before any world's been
   *  set up — /setup itself, /help (how do I use this bot, including how to
   *  run /setup in the first place), and galthen/boosted/patreon (personal,
   *  self-service commands unrelated to any specific world). */
  val initialCommands: List[SlashCommandData] = List(setupCommand, helpCommand, galthenCommand, boostedCommand, patreonCommand)

  /** Only meaningful once at least one world is tracked in the guild — added
   *  on top of initialCommands once /setup first succeeds there. remove/
   *  repair move here too: both act on a world's channels, which don't
   *  exist until /setup has run at least once. */
  val worldConfigCommands: List[SlashCommandData] = List(removeCommand, repairCommand, huntedCommand, alliesCommand, neutralsCommand, fullblessCommand, filterCommand, exivaCommand, onlineCombineCommand, staminaCommand, bookingsCommand)

  /** Commands registered in normal guilds once a world has been set up. */
  val commands: List[SlashCommandData] = initialCommands ++ worldConfigCommands

  /** Commands registered in the bot-owner guilds (adds /admin). */
  val adminCommands: List[SlashCommandData] = commands :+ adminCommand

  /** The bot's own support Discords — also reused by BotApp's inactive-guild
   *  prune sweep, which must never auto-leave either of these. */
  val supportGuildIds: Set[Long] = Set(867319250708463628L, 1082484147492237515L)

  /** The one bot identity allowed to register slash commands in each of
   *  these guilds — any other identity sharing the guild (a shared-world-
   *  cycle secondary, a local DEV/test bot, or anything else) must stay
   *  out, since registering there would just add a duplicate, redundant
   *  command set alongside the intended owner's. Keyed by guild id, valued
   *  by that owner's Discord user id (a standard bot's application id and
   *  user id are the same snowflake). */
  val restrictedCommandGuildOwners: Map[Long, String] = Map(
    867319250708463628L -> "1193678088165404807" // main support Discord -> Blue
  )

  /** True when `selfUserId` is not the designated owner of a restricted
   *  guild (see `restrictedCommandGuildOwners`) — false for every other
   *  guild, unrestricted by default. */
  def excludedFromCommands(guildId: Long, selfUserId: String): Boolean =
    restrictedCommandGuildOwners.get(guildId).exists(_ != selfUserId)

  /** The single place this decision is made — reused by the boot-time
   *  registration loop, onGuildJoin, and ChannelService's post-/setup
   *  upgrade, so a support guild with no world yet configured (were that to
   *  ever happen) still correctly gets adminCommands, not just commands.
   *  `excludeAll` is decided by the caller via `excludedFromCommands` (this
   *  object deliberately stays decoupled from Config/BotRole/JDA) — when
   *  true, an empty list is returned so the caller's bulk `updateCommands()`
   *  call clears any commands this identity may have previously registered
   *  there, not just skips future registration and leaves stale ones behind.
   *
   *  `respawnEnabled` is the respawn claim system's rollout gate, decided by
   *  the caller from `Config.Respawn.enabled` (this object deliberately stays
   *  decoupled from Config). `/stamina` stays in the lists above regardless, so
   *  the schema is still covered by the routing spec, but it is filtered out of
   *  what actually gets registered while the feature is off — prod and DEV run
   *  the same image, and a command Discord shows but the bot won't service is
   *  worse than no command at all. */
  private def respawnCommandNames(command: SlashCommandData): Boolean =
    command.getName == staminaCommand.getName || command.getName == bookingsCommand.getName

  def commandsFor(guildId: Long, hasWorldConfigured: Boolean, excludeAll: Boolean = false,
                  respawnEnabled: Boolean = false): List[SlashCommandData] = {
    val selected =
      if (excludeAll) Nil
      else if (supportGuildIds.contains(guildId)) adminCommands
      else if (hasWorldConfigured) commands
      else initialCommands
    if (respawnEnabled) selected else selected.filterNot(respawnCommandNames)
  }
}
