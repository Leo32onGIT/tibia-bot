package com.tibiabot.setup

import com.tibiabot.Config
import com.tibiabot.app.StreamSupervisor
import com.tibiabot.boosted.BoostedService
import com.tibiabot.paywall.PaywallService
import com.tibiabot.domain.{Discords, Worlds}
import com.tibiabot.persistence.{DiscordConfigRepository, SchemaInitializer, WorldConfigRepository}
import com.tibiabot.presentation.Embeds.BrandColor
import com.tibiabot.state.StreamState
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer
import net.dv8tion.jda.api.entities.channel.concrete.{Category, TextChannel}
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.{Guild, Message, MessageEmbed, Role}
import net.dv8tion.jda.api.events.guild.{GuildJoinEvent, GuildLeaveEvent}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.{EmbedBuilder, Permission}

import java.awt.Color
import java.time.ZonedDateTime
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import com.tibiabot.presentation.Names

/** createChannels' result: an embed always, plus confirm/cancel buttons only
 *  when it's prompting to reassign a paused world's seat (see
 *  ChannelCommands.setup). */
final case class SetupResult(embed: MessageEmbed, buttons: List[Button] = Nil)

/** Per-guild channel/role setup lifecycle, extracted from BotApp. Holds the
 *  guild-join/leave handlers plus the /setup, /repair and /remove channel
 *  management (`createChannels`, `repairChannel`, `removeChannels`), all of
 *  which read/write `streamState` directly. State mutation for join/leave
 *  itself stays in BotApp via the `forgetGuild` callback.
 *
 *  @param forgetGuild         drops a guild's in-memory state (worldsData/discordsData)
 *  @param sharedConfigGuilds  guilds whose database is shared with another bot, so it must NOT be dropped on leave
 *  @param startBot            BotApp's bootstrap routine (touches nearly every state map); kept as a callback rather than moved/duplicated
 *  @param serverSaveExtraEmbeds the Rashid/Dream Courts/Drome embeds appended after the boosted embeds; stays in BotApp (Dream Scar/Drome state), passed as a callback
 *  @param syncPatreonBeforeCheck refreshes the Patreon snapshot the `/setup` paywall gate reads; throttled and time-bounded by the caller (BotApp.syncPatreonMembersForSetup), so this may legitimately do nothing
 */
final class ChannelService(
  streamSupervisor: StreamSupervisor,
  schemaInitializer: SchemaInitializer,
  worldConfigRepository: WorldConfigRepository,
  discordConfigRepository: DiscordConfigRepository,
  streamState: StreamState,
  boostedService: BoostedService,
  paywallService: PaywallService,
  respawnService: com.tibiabot.respawn.RespawnService,
  botUser: String,
  startBot: (Option[Guild], Option[String]) => Unit,
  serverSaveExtraEmbeds: String => List[MessageEmbed],
  syncPatreonBeforeCheck: () => Unit,
  forgetGuild: String => Unit,
  sharedConfigGuilds: Set[String]
)(implicit ex: ExecutionContextExecutor) extends StrictLogging {

  private def createConfigDatabase(guild: Guild): Unit = schemaInitializer.initGuild(guild.getId, guild.getName)

  private def worldConfig(guild: Guild): List[Worlds] =
    worldConfigRepository.listWorlds(guild.getId)

  private def worldCreateConfig(guild: Guild, world: String, alliesChannel: String, enemiesChannel: String, neutralsChannels: String, levelsChannel: String, deathsChannel: String, category: String, fullblessRole: String, nemesisRole: String, allyPkRole: String, masslogRole: String, fullblessChannel: String, nemesisChannel: String, activityChannel: String): Unit =
    worldConfigRepository.createWorld(guild.getId, world, alliesChannel, enemiesChannel, neutralsChannels, levelsChannel, deathsChannel, category, fullblessRole, nemesisRole, allyPkRole, masslogRole, fullblessChannel, nemesisChannel, activityChannel)

  private def worldRetrieveConfig(guild: Guild, world: String): Map[String, String] =
    worldConfigRepository.retrieveWorld(guild.getId, world)

  private def discordRetrieveConfig(guild: Guild): Map[String, String] =
    discordConfigRepository.getConfig(guild.getId)

  private def discordCreateConfig(guild: Guild, guildName: String, guildOwner: String, adminCategory: String, adminChannel: String, boostedChannel: String, boostedMessageId: String, created: ZonedDateTime): Unit =
    discordConfigRepository.create(guild.getId, guildName, guildOwner, adminCategory, adminChannel, boostedChannel, boostedMessageId, created)

  private def discordUpdateConfig(guild: Guild, adminCategory: String, adminChannel: String, boostedChannel: String, boostedMessage: String, lastWorld: String): Unit =
    discordConfigRepository.update(guild.getId, adminCategory, adminChannel, boostedChannel, boostedMessage, lastWorld)

  def worldRepairConfig(guild: Guild, world: String, tableName: String, newValue: String): Unit =
    worldConfigRepository.updateWorldString(guild.getId, world, tableName, newValue)

  private def worldRemoveConfig(guild: Guild, query: String): Unit =
    worldConfigRepository.removeWorld(guild.getId, query)

  private def updateAdminChannel(inputId: String, channelId: String): Unit = {
    streamState.modifyDiscordsData(dd => dd.view.mapValues(_.map {
      case discord @ Discords(id, _, _, _) if id == inputId =>
        discord.copy(adminChannel = channelId)
      case other => other
    }).toMap)
  }

  private def updateBoostedChannel(inputId: String, channelId: String): Unit = {
    streamState.modifyDiscordsData(dd => dd.view.mapValues(_.map {
      case discord @ Discords(id, _, _, _) if id == inputId =>
        discord.copy(boostedChannel = channelId)
      case other => other
    }).toMap)
  }

  /** The bot's own override on its "Violent Bot" category.
   *
   *  MANAGE_PERMISSIONS is the load-bearing one. Discord only lets you write a
   *  channel override if you either already hold every permission in it or hold
   *  Manage Permissions *explicitly on that channel* — so without this, granting
   *  the moderator role its access to the spawns forum fails, and takes the whole
   *  forum setup down with it. The thread permissions are here for the same
   *  reason: the forum override hands those to the moderator role, and you cannot
   *  grant what you do not have.
   *
   *  Non-fatal. A bot invited without Manage Roles cannot grant itself Manage
   *  Permissions either, and a guild in that position should still get its
   *  channels — it just needs the permission adding by hand before the spawns
   *  forum will set up.
   */
  private def setCategoryBotPerms(category: Category, botRole: Role): Unit =
    try {
      category.upsertPermissionOverride(botRole)
        .grant(Permission.VIEW_CHANNEL)
        .grant(Permission.MESSAGE_SEND)
        .grant(Permission.MESSAGE_SEND_IN_THREADS)
        .grant(Permission.MESSAGE_HISTORY)
        .grant(Permission.MANAGE_THREADS)
        .grant(Permission.MANAGE_CHANNEL)
        .grant(Permission.MANAGE_MESSAGES)
        .grant(Permission.MANAGE_PERMISSIONS)
        .complete()
    } catch {
      case ex: Throwable =>
        logger.warn(s"Could not give the bot full permissions on its category in guild " +
          s"'${category.getGuild.getId}' — the spawns forum will not set up until someone " +
          s"grants Manage Roles by hand", ex)
    }

  /** The @everyone override on the bot's "Violent Bot" category.
   *
   *  Members may see it — that is how they read the notifications channel and the
   *  spawns forum — but may not open posts or threads of their own. The spawns
   *  forum deliberately inherits from here rather than carrying its own override,
   *  so this is where the "one post per respawn, created by the bot" rule is
   *  actually enforced.
   *
   *  One helper for all four creation sites, so a new one can't quietly forget
   *  the deny. `visible` is false on the path that hides the category entirely. */
  private def setCategoryPublicPerms(category: Category, publicRole: Role, visible: Boolean): Unit = {
    val action = category.upsertPermissionOverride(publicRole)
      .grant(Permission.CREATE_PUBLIC_THREADS)
    (if (visible) action.grant(Permission.VIEW_CHANNEL) else action.deny(Permission.VIEW_CHANNEL)).queue()
  }

  /** Reuse the guild's existing role of this name, or create it with the given
   *  colour. Used by /setup only; /repair looks up roles by stored id instead
   *  and creates its own replacements inline if they're missing. */
  /** Create (or adopt) the guild's moderator role and remember its id.
   *
   *  Reuses an existing role of the same name, so a server that already made one
   *  by hand — or is being repaired — keeps whatever members it already has
   *  rather than getting a second, empty role. */
  private def ensureModeratorRole(guild: Guild): Option[Role] =
    try {
      val role = getOrCreateRole(guild, com.tibiabot.commands.Permissions.ModeratorRoleName, new Color(114, 137, 218))
      discordConfigRepository.setModeratorRole(guild.getId, role.getId)
      Some(role)
    } catch {
      case ex: Throwable =>
        // Never fail a /setup over this — the world's channels are the point, and
        // the commands stay usable by anyone with Manage Server regardless.
        logger.warn(s"Could not create the moderator role in guild '${guild.getId}'", ex)
        None
    }

  /** The guild's stored moderator role, if it still exists. */
  private def moderatorRole(guild: Guild): Option[Role] =
    discordRetrieveConfig(guild).get("moderator_role")
      .filter(id => id.nonEmpty && id != "0")
      .flatMap(id => Option(guild.getRoleById(id)))

  private def getOrCreateRole(guild: Guild, name: String, color: Color): Role = {
    val existing = guild.getRolesByName(name, true)
    if (!existing.isEmpty) existing.get(0)
    else guild.createRole().setName(name).setColor(color).complete()
  }

  /** Apply the standard per-world channel/category permissions: grant the bot
   *  the channel-management set and deny @everyone the ability to post. Used
   *  for the world category and each world channel by /setup, /repair, and
   *  /onlinelist whenever they (re)create world channels. */
  def grantWorldPerms(entity: IPermissionContainer, botRole: Role, publicRole: Role): Unit = {
    entity.upsertPermissionOverride(botRole)
      .grant(Permission.VIEW_CHANNEL)
      .grant(Permission.MESSAGE_SEND)
      .grant(Permission.MESSAGE_MENTION_EVERYONE)
      .grant(Permission.MESSAGE_EMBED_LINKS)
      .grant(Permission.MESSAGE_HISTORY)
      .grant(Permission.MANAGE_CHANNEL)
      .complete()
    entity.upsertPermissionOverride(publicRole).deny(Permission.MESSAGE_SEND).complete()
  }

  /** Post a channel's intro/help embed (the "this channel shows ..." message)
   *  if the channel exists. Used for the levels/deaths/activity channels. */
  private def postChannelIntro(channel: TextChannel, description: String): Unit =
    if (channel != null) {
      val embed = new EmbedBuilder()
      embed.setDescription(description)
      embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Sign_(Library).gif")
      embed.setColor(BrandColor)
      channel.sendMessageEmbeds(embed.build()).queue()
    }

  /** Post the Galthen's Satchel cooldown-tracker embed + button into a guild's
   *  notifications channel (done on every /setup and /repair of that channel). */
  private def postGalthenTracker(channel: TextChannel): Unit = {
    val galthenEmbed = new EmbedBuilder()
    galthenEmbed.setColor(BrandColor)
    galthenEmbed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nManage your cooldowns here:")
    galthenEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
    channel.sendMessageEmbeds(galthenEmbed.build()).addComponents(ActionRow.of(
      Button.primary("galthen default", "Cooldowns").withEmoji(Emoji.fromFormatted(Config.satchelEmoji))
    )).queue()
  }

  /** Build the boosted boss + creature + server-save embeds and post them to a
   *  guild's notifications channel with the server-save button, storing the
   *  message id (used by /repair to confirm the message still exists, and by
   *  the daily scheduler to delete-and-replace it). Used when /setup or
   *  /repair (re)creates the notifications channel. */
  private def postBoostedNotifications(channel: TextChannel, guild: Guild, world: String): Unit = {
    val combinedFutures: Future[List[MessageEmbed]] = for {
      bossEmbed <- boostedService.boostedBossEmbed()
      creatureEmbed <- boostedService.boostedCreatureEmbed()
    } yield List(bossEmbed, creatureEmbed)

    combinedFutures.map { embeds =>
      val allEmbeds = embeds ++ serverSaveExtraEmbeds(world)
      channel
        .sendMessageEmbeds(allEmbeds.asJava)
        .setComponents(ActionRow.of(Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(Config.letterEmoji))))
        .queue(
          (message: Message) => discordUpdateConfig(guild, "", "", "", message.getId, world),
          (e: Throwable) => logger.warn(s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':", e)
        )
    }
  }

  /** The role-subscription buttons under the fullbless/notifications embed. */
  def fullblessRoleButtons: List[Button] = List(
    Button.success("fullbless", " ").withEmoji(Emoji.fromFormatted(Config.inqEmoji)),
    Button.primary("nemesis", " ").withEmoji(Emoji.fromFormatted(Config.bossEmoji)),
    Button.danger("allypk", " ").withEmoji(Emoji.fromFormatted(Config.hazardEmoji)),
    Button.secondary("masslog", " ").withEmoji(Emoji.fromFormatted(Config.masslogEmoji))
  )

  /** The "the bot will poke" role-notification embed for a world. Built by
   *  /setup (initial post), /fullbless (edits the existing message) and
   *  /repair (reposts it). `level` is a String because /repair reads it
   *  straight out of the stored world config; it is only ever interpolated. */
  def fullblessRoleEmbed(world: String, fullblessRoleId: String, nemesisRoleId: String, allyPkRoleId: String, masslogRoleId: String, level: String): MessageEmbed =
    new EmbedBuilder()
      .setTitle(s":crossed_swords: $world :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$world")
      .setThumbnail("https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Phantasmal_Ooze.gif")
      .setColor(BrandColor)
      .setFooter("Add or remove yourself from the role using the buttons below:")
      .setDescription(s"The bot will poke:\n${Config.inqEmoji}<@&$fullblessRoleId> If an enemy fullblesses and is over level `$level`\n${Config.bossEmoji}<@&$nemesisRoleId> If anyone dies to a rare boss\n${Config.hazardEmoji}<@&$allyPkRoleId> If an ally gets pked\n${Config.masslogEmoji}<@&$masslogRoleId> If enemies masslog on **$world**")
      .build()

  /** What a seed sync did, in words. Silent when nothing changed, so a repair
   *  run for some other reason doesn't report a catalogue that is already right.
   *  A code left alone because somebody is on it is always mentioned — it is the
   *  one part that needs coming back to. */
  private def seedSummary(sync: com.tibiabot.persistence.SeedSync): String = {
    val parts = List(
      Option(sync.added).filter(_ > 0).map(n => s"added **$n**"),
      Option(sync.updated).filter(_ > 0).map(n => s"renamed **$n**"),
      Option(sync.retired).filter(_ > 0).map(n => s"retired **$n**")
    ).flatten
    val held = if (sync.inUse > 0)
      s" **${sync.inUse}** dropped from the list are still in use, so I've left them."
    else ""
    if (parts.isEmpty) s"The catalogue was already up to date.$held"
    else s"Catalogue: ${parts.mkString(", ")}.$held"
  }

  /** What the respawn system needs that this bot was not given.
   *
   *  Checked up front rather than discovered halfway through: the system is a
   *  forum, a set of channel overrides, and threads it archives and un-archives.
   *  Finding out at the third step leaves a guild holding a channel the bot
   *  cannot manage.
   *
   *  Bots added before this feature existed were invited with a narrower set, so
   *  this is the ordinary case for an existing server rather than an error — the
   *  answer is a fresh invite, not a retry.
   */
  private[setup] def missingRespawnPermissions(guild: Guild): List[Permission] =
    List(Permission.MANAGE_CHANNEL, Permission.MANAGE_ROLES, Permission.MANAGE_THREADS)
      .filterNot(permission => guild.getSelfMember.hasPermission(permission))

  /** Said the same way wherever it is needed: what is missing, and that the fix
   *  is re-inviting the bot rather than trying again. */
  private[setup] def respawnPermissionHelp(missing: List[Permission]): String = {
    val named = missing.map(permission => s"**${permission.getName}**").mkString(", ")
    s"${Config.noEmoji} I can't set up the respawn claim system here — I'm missing $named.\n" +
      "That's normal for a server that added me before the feature existed: the invite " +
      "didn't ask for these. Grab a fresh invite from the " +
      "[Violent Bot support Discord](https://discord.gg/SWMq9Pz8ud) and re-add me, then run " +
      "`/repair` to finish the job. Everything else keeps working in the meantime."
  }

  /** Create the respawn system's `📅・sᴘᴀᴡɴs` forum and its pinned board post in
   *  the guild's admin category, seeding the catalogue on the way.
   *
   *  Idempotent by design: if the forum still exists this reports that and
   *  changes nothing, so it doubles as the repair path. Returns the text to
   *  show the caller.
   *
   *  Kept here rather than in RespawnService because it needs the guild's admin
   *  category, which is `discord_info` state this class already owns — and
   *  because `/setup` and `/repair` call it for exactly the same reason they
   *  create the notifications channel.
   */
  def createSpawnsForum(guild: Guild): String = {
    if (!Config.Respawn.enabled) {
      return s"${Config.noEmoji} The respawn claim system isn't enabled on this bot."
    }
    // Checked before anything is created, so a guild is never left holding a
    // half-built forum it cannot manage.
    val missing = missingRespawnPermissions(guild)
    if (missing.nonEmpty) {
      logger.info(s"Skipping respawn setup on guild '${guild.getId}' — missing ${missing.mkString(", ")}")
      return respawnPermissionHelp(missing)
    }
    val discordConfig = discordRetrieveConfig(guild)
    if (discordConfig.isEmpty) {
      return s"${Config.noEmoji} Run `/setup <world>` first — the respawn forum lives in the bot's category, " +
        "which doesn't exist yet."
    }

    // Settings are created on first setup and reused afterwards, so a repair
    // never resets a guild's tuned durations/limits back to the bot defaults.
    val settings = respawnService.settings(guild.getId).getOrElse {
      val defaults = respawnService.defaultSettings
      respawnService.saveSettings(guild.getId, defaults)
      defaults
    }

    try {
      // Re-assert the category's overrides. The @everyone one because the forum
      // inherits from it, and the bot's because Manage Permissions there is what
      // lets the moderator override below be written at all — both migrate
      // categories created before those rules existed.
      Option(guild.getCategoryById(discordConfig.getOrElse("admin_category", "0"))).foreach { category =>
        setCategoryBotPerms(category, guild.getBotRole)
        setCategoryPublicPerms(category, guild.getPublicRole, visible = true)
      }

      com.tibiabot.respawn.RespawnThreads.findForum(guild, settings) match {
        case Some(existing) =>
          // Re-assert the moderator role's access every time: the role may have
          // been created after the forum was, or its override removed by hand.
          ensureModeratorRole(guild).foreach(
            com.tibiabot.respawn.RespawnThreads.grantModeratorAccess(existing, _))
          // Migrate forums created with an explicit @everyone override back to
          // inheriting from the category.
          com.tibiabot.respawn.RespawnThreads.inheritPublicPermissions(existing, guild.getPublicRole)
          // Un-archive and unlock the board so its buttons work — boards created
          // before that was understood were locked, which greyed them out for
          // everyone without Manage Threads.
          com.tibiabot.respawn.RespawnThreads.refreshBoard(guild, settings)
          // The channel survived; the board post may not have.
          // Bring the catalogue in line with the bundled file — added codes,
          // renamed ones, and ones the file has dropped. Only rows that came from
          // the seed are touched. This is the only way an edit to respawns.json
          // reaches a guild that already exists, now that there are no catalogue
          // commands.
          val sync = respawnService.syncSeed(guild.getId)

          val boardMissing = com.tibiabot.respawn.RespawnThreads
            .resolveThread(guild, existing, settings.boardThread).isEmpty
          if (boardMissing) {
            val boardId = com.tibiabot.respawn.RespawnThreads.postBoard(existing, settings,
              respawnService.listRespawns(guild.getId))
            respawnService.updateChannels(guild.getId, existing.getId, boardId)
            s"${Config.yesEmoji} The <#${existing.getId}> channel already exists — its board post was " +
              s"missing, so I've recreated it. ${seedSummary(sync)}"
          } else {
            val redrawn = com.tibiabot.respawn.RespawnThreads
              .redrawBoard(guild, settings, respawnService.listRespawns(guild.getId))
            if (redrawn)
              s"${Config.yesEmoji} Redrew the board on <#${existing.getId}>. ${seedSummary(sync)}"
            else
              s"${Config.noEmoji} <#${existing.getId}> already exists; I couldn't redraw its board."
          }

        case None =>
          var adminCategory = guild.getCategoryById(discordConfig.getOrElse("admin_category", "0"))
          if (adminCategory == null) {
            val newAdminCategory = guild.createCategory("Violent Bot").complete()
            setCategoryBotPerms(newAdminCategory, guild.getBotRole)
            setCategoryPublicPerms(newAdminCategory, guild.getPublicRole, visible = true)
            discordUpdateConfig(guild, newAdminCategory.getId, "", "", "", "")
            adminCategory = newAdminCategory
          }

          // Seeded before the forum is built, not after: the board post *is* the
          // catalogue now, so a forum created against an empty table would post
          // an empty board and only fill in if somebody deleted and repaired it.
          val seeded = respawnService.importSeed(guild.getId)

          val (forumId, boardId) =
            com.tibiabot.respawn.RespawnThreads.createForum(guild, adminCategory, settings,
              ensureModeratorRole(guild), respawnService.listRespawns(guild.getId))
          respawnService.updateChannels(guild.getId, forumId, boardId)

          val adminChannel = guild.getTextChannelById(discordConfig.getOrElse("admin_channel", "0"))
          com.tibiabot.presentation.AdminLog.post(adminChannel,
            s"The respawn claim system was set up — see <#$forumId>.",
            "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Hammer.gif")

          s":gear: Created <#$forumId> with **$seeded** respawns in the catalogue.\n" +
            "Every code is listed on its board post — claim one with the buttons there."
      }
    } catch {
      case e: net.dv8tion.jda.api.exceptions.PermissionException =>
        logger.warn(s"Respawn forum setup on guild '${guild.getId}' aborted on a missing permission: ${e.getMessage}")
        s"${Config.noEmoji} I couldn't create the respawn forum because I'm missing a required permission. " +
          "Grant me **Manage Channels**, **Manage Permissions** and **Manage Threads**, then try again."
      case e: Exception =>
        logger.warn(s"Respawn forum setup on guild '${guild.getId}' failed before completing", e)
        s"${Config.noEmoji} Something went wrong creating the respawn forum. Wait a moment, then run " +
          "`/respawn admin setup` again."
    }
  }

  /** Retire the respawn forum when `/remove` takes the guild's last world.
   *
   *  Unlike the command-log and notifications channels, the forum is **kept** —
   *  it holds the server's hunt history, and deleting that silently isn't worth
   *  the tidiness. Instead it is archived, renamed, lifted out of the bot's
   *  category and made read-only, while the bot drops all of its own respawn
   *  data (claims, catalogue, settings).
   *
   *  Two consequences worth knowing:
   *   - Because the catalogue goes, a later `/setup` builds a *new* forum from
   *     the bundled seed; the retired one stays behind as history under its own
   *     name, so the two never collide.
   *   - This is deliberately *not* gated on `Config.Respawn.enabled`. The flag
   *     can be switched off after a guild already has a forum, and teardown
   *     still has to leave that channel in a sane state.
   */
  def retireSpawnsForum(guild: Guild): Unit = {
    try {
      respawnService.settings(guild.getId).foreach { settings =>
        com.tibiabot.respawn.RespawnThreads.findForum(guild, settings).foreach { forum =>
          com.tibiabot.respawn.RespawnThreads.retireForum(guild, forum,
            "Violent Bot is no longer tracking a world on this server, so respawn claims have been turned off.\n\n" +
              "This channel has been kept as a read-only archive of previous claims. " +
              "Delete it whenever you like — the bot won't touch it again.\n\n" +
              "Running `/setup` for a world later will create a fresh spawns channel.")
        }
        respawnService.teardown(guild.getId)
      }
    } catch {
      case ex: Throwable =>
        // Never fail a /remove over this — the world's own channels are the
        // point, and a half-retired forum is fixable by hand.
        logger.warn(s"Could not retire the respawn forum on guild '${guild.getId}'", ex)
    }
  }

  def createChannels(event: SlashCommandInteractionEvent): SetupResult = {
    val world: String = com.tibiabot.domain.WorldName.formal(event.getInteraction.getOptions.asScala.find(_.getName == "world").map(_.getAsString).getOrElse("").trim())
    var buttons: List[Button] = Nil
    // The role/category/channel/permission creation below is a long sequence of
    // blocking .complete() calls. If any one throws (missing permission, Discord
    // error, channel cap) the server is left half-built and the slash interaction
    // would otherwise hang with no reply — so report it cleanly and point at /repair.
    val embedText = try {
      // Subscribing is what someone does immediately *before* running this, so
      // the periodic sync is exactly the wrong thing to make them wait on —
      // refresh the snapshot the check below reads first. Throttled and
      // time-bounded on the other side, and swallows its own failures, so the
      // worst case here is that the check answers from data that was already
      // good enough before this line existed.
      syncPatreonBeforeCheck()
      if (!paywallService.callerIsSubscribed(event.getUser.getId)) {
        // Both halves matter and neither is guessable: an active pledge alone
        // isn't enough, because Patreon only tells us who a patron is on
        // Discord once they've connected the two accounts themselves.
        s"${Config.noEmoji} `/setup` requires an active **Patreon** subscription.\nJoin as a paid member on [Patreon](https://patreon.com/violentbot) and [connect your Discord account](https://www.patreon.com/settings/apps/discord) to `/setup` and use the bot.\n\n" +
        "[Website](https://violentbot.xyz) | [Discord](https://discord.gg/SWMq9Pz8ud) | [Patreon](https://patreon.com/violentbot)"
      } else if (Config.worldList.contains(world)) {
      val guild = event.getGuild

      // no-op if the guild's database already exists (initGuild checks first)
      createConfigDatabase(guild)

      // touch the worlds config so listWorlds runs its ALTER TABLE column
      // migrations on older databases before /setup reads or writes the table
      worldConfig(guild)

      // Answered before any role or channel work is attempted. A world that is
      // already configured needs none of it, and on a bot missing Manage Roles
      // the role creation below throws a PermissionException — which would bury
      // the seat prompts under a "grant me permissions" reply, for a server that
      // only ever needed a database write.
      val worldConfigData = worldRetrieveConfig(guild, world)
      if (worldConfigData.nonEmpty) {
        if (!paywallService.isActive(guild.getId, world)) {
          // channels already exist, but tracking is paused — offer to hand the
          // seat to this caller instead of the plain "already been setup" reply
          if (paywallService.canReassignSeat(event.getUser.getId, guild.getId, world)) {
            buttons = List(
              Button.success(s"paywall_reassign_yes_$world", "Take over tracking"),
              Button.secondary("paywall_reassign_no", "Cancel")
            )
            // A paused world with no seat is a legacy setup that ran out its
            // grace period, not a lapsed subscription — there was never one to
            // lapse, so don't claim there was.
            val reason =
              if (paywallService.hasSeat(guild.getId, world)) "the Patreon subscription tied to it lapsed"
              else "it isn't tied to a Patreon subscription"
            s":warning: Tracking for **$world** is currently paused — $reason.\nYou currently hold an active Patreon subscription. Take over this world's seat and resume tracking?"
          } else {
            s"${Config.noEmoji} Tracking for **$world** is currently paused, and you don't have a free Patreon seat to take it over. Free one up with `/remove` on another world, then try again."
          }
        } else if (!paywallService.hasSeat(guild.getId, world)) {
          // channels exist and tracking is active, but this (guild, world) was
          // never tied to a seat — a legacy setup from before the seat system
          // existed. isActive's grandfather rule leaves it running either way,
          // but offer to claim it onto one of the caller's seats rather than
          // leaving it ungated forever.
          if (paywallService.canAssignSeat(event.getUser.getId, guild.getId, world)) {
            buttons = List(
              Button.success(s"paywall_claim_yes_$world", "Assign as a seat"),
              Button.secondary("paywall_claim_no", "Cancel")
            )
            s":warning: The channels for **$world** already exist, but this world isn't tied to one of your Patreon seats yet.\nAssign this world to a seat now?"
          } else {
            s"${Config.noEmoji} The channels for **$world** have already been setup.\nUse `/repair` if you need to recreate channels for **$world** that you have deleted."
          }
        } else {
          // channels already exist
          logger.info(s"The channels have already been setup on '${guild.getName} - ${guild.getId}'.")
          s"${Config.noEmoji} The channels for **$world** have already been setup.\nUse `/repair` if you need to recreate channels for **$world** that you have deleted."
        }
      } else {
      val botRole = guild.getBotRole
      val fullblessRole = getOrCreateRole(guild, s"$world Fullbless", new Color(0, 156, 70))
      val nemesisRole = getOrCreateRole(guild, s"$world Rare Boss", new Color(164, 76, 230))
      val allyPkRole = getOrCreateRole(guild, s"$world PVP", new Color(220, 0, 0))
      val masslogRole = getOrCreateRole(guild, s"$world Masslog", new Color(219, 175, 72))

      // see if admin channels exist
      val discordConfig = discordRetrieveConfig(guild)
      if (discordConfig.isEmpty) {
        val adminCategory = guild.createCategory("Violent Bot").complete()
        setCategoryBotPerms(adminCategory, botRole)
        setCategoryPublicPerms(adminCategory, guild.getPublicRole, visible = true)
        val adminChannel = guild.createTextChannel("🖥️・ᴄᴏᴍᴍᴀɴᴅ ʟᴏɢ", adminCategory).complete()
        // hide this channel from @everyone; only the bot can view/post
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
        adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
        val guildOwner = if (guild.getOwner == null) "Not Available" else guild.getOwner.getEffectiveName
        discordCreateConfig(guild, guild.getName, guildOwner, adminCategory.getId, adminChannel.getId, "0", "0", ZonedDateTime.now())

        val boostedChannel = guild.createTextChannel("👑・ɴᴏᴛɪғɪᴄᴀᴛɪᴏɴs", adminCategory).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
        boostedChannel.upsertPermissionOverride(guild.getPublicRole).grant(Permission.VIEW_CHANNEL).queue()
        discordUpdateConfig(guild, "", "", boostedChannel.getId, "", world)

        postGalthenTracker(boostedChannel)

        postBoostedNotifications(boostedChannel, guild, world)
      } else {
        var adminCategoryCheck = guild.getCategoryById(discordConfig("admin_category"))
        val adminChannelCheck = guild.getTextChannelById(discordConfig("admin_channel"))
        val boostedChannelCheck = guild.getTextChannelById(discordConfig("boosted_channel"))
        if (adminCategoryCheck == null) {
          // admin category has been deleted
          val adminCategory = guild.createCategory("Violent Bot").complete()
          setCategoryBotPerms(adminCategory, botRole)
          setCategoryPublicPerms(adminCategory, guild.getPublicRole, visible = true)
          discordUpdateConfig(guild, adminCategory.getId, "", "", "", world)
          adminCategoryCheck = adminCategory
        }
        if (adminChannelCheck == null) {
          // admin channel has been deleted
          val adminChannel = guild.createTextChannel("🖥️・ᴄᴏᴍᴍᴀɴᴅ ʟᴏɢ", adminCategoryCheck).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
          adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          discordUpdateConfig(guild, "", adminChannel.getId, "", "", world)
        }
        if (boostedChannelCheck == null) {
          // adminCategoryCheck is guaranteed non-null here (created above if missing)
          val boostedChannel = guild.createTextChannel("👑・ɴᴏᴛɪғɪᴄᴀᴛɪᴏɴs", adminCategoryCheck).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
          boostedChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          discordUpdateConfig(guild, "", "", boostedChannel.getId, "", world)

          postGalthenTracker(boostedChannel)

          postBoostedNotifications(boostedChannel, guild, world)
        }
      }
      if (!paywallService.canAssignSeat(event.getUser.getId, guild.getId, world)) {
        s"${Config.noEmoji} You've used all **${paywallService.effectiveSeatLimit(event.getUser.getId)}** of your Patreon seats. Free one up with `/remove` on another world, then try again."
      } else {
        // captured before worldCreateConfig runs below, since afterward this
        // would never be empty — gates the one-time command-set expansion
        val isFirstWorldForGuild = worldConfig(guild).isEmpty
        val newCategory = guild.createCategory(world).complete()
        grantWorldPerms(newCategory, botRole, guild.getPublicRole)
        val alliesChannel = guild.createTextChannel("📈・ᴏɴʟɪɴᴇ", newCategory).complete()

        val deathsChannel = guild.createTextChannel("💀・ᴅᴇᴀᴛʜs", newCategory).complete()
        val levelsChannel = guild.createTextChannel("💖・ʟᴇᴠᴇʟs", newCategory).complete()
        val activityChannel = guild.createTextChannel("📝・ᴀᴄᴛɪᴠɪᴛʏ", newCategory).complete()

        val publicRole = guild.getPublicRole
        val channelList = List(alliesChannel, levelsChannel, deathsChannel, activityChannel)
        channelList.foreach(grantWorldPerms(_, botRole, publicRole))

        val notificationsConfig = discordRetrieveConfig(guild)
        val notificationsChannel = guild.getTextChannelById(notificationsConfig("boosted_channel"))

        if (notificationsChannel != null) {
          if (notificationsChannel.canTalk()) {

            notificationsChannel.sendMessageEmbeds(fullblessRoleEmbed(world, fullblessRole.getId, nemesisRole.getId, allyPkRole.getId, masslogRole.getId, "250"))
              .setComponents(ActionRow.of(fullblessRoleButtons.asJava))
              .queue()
            }
        }

        val alliesId = alliesChannel.getId
        val enemiesId = "0" //enemiesChannel.getId
        val neutralsId = "0" //neutralsChannel.getId
        val levelsId = levelsChannel.getId
        val deathsId = deathsChannel.getId
        val categoryId = newCategory.getId
        val activityId = activityChannel.getId

        postChannelIntro(guild.getTextChannelById(levelsId), s":speech_balloon: This channel shows levels that have been gained on this world.\n\nYou can filter what appears in this channel using the **`/levels filter`** command.")
        postChannelIntro(guild.getTextChannelById(deathsId), s":speech_balloon: This channel shows deaths that occur on this world.\n\nYou can filter what appears in this channel using the **`/deaths filter`** command.")
        postChannelIntro(guild.getTextChannelById(activityId), s":speech_balloon: This channel shows change activity for *allied* or *enemy* players.\n\nIt will show events when a players **joins** or **leaves** one of these tracked guilds or **changes their name**.")

        worldCreateConfig(guild, world, alliesId, enemiesId, neutralsId, levelsId, deathsId, categoryId, fullblessRole.getId, nemesisRole.getId, allyPkRole.getId, masslogRole.getId, "0", "0", activityId)
        paywallService.assignSeat(event.getUser.getId, event.getUser.getName, guild.getId, world)
        if (isFirstWorldForGuild) {
          val excludeAll = com.tibiabot.commands.CommandSchemas.excludedFromCommands(guild.getIdLong, guild.getJDA.getSelfUser.getId)
          guild.updateCommands().addCommands(com.tibiabot.commands.CommandSchemas.commandsFor(guild.getIdLong, hasWorldConfigured = true, excludeAll, Config.Respawn.enabled).asJava).queue()
        }
        startBot(Some(guild), Some(world))

        // The delegation role, so hunted/allies/respawn management can be handed
        // out without granting Manage Server. Idempotent — adopts an existing
        // role of the same name rather than making a second one. Deliberately not
        // tied to the respawn system below: the same role gates /hunted and
        // /allies, so a guild that cannot have the forum still wants it.
        ensureModeratorRole(guild)

        // Respawn forum, when the feature is switched on for this deployment.
        // Self-guarding and idempotent, so it's safe to call on every /setup:
        // a guild's second world doesn't get a second forum.
        val respawnNote =
          if (!Config.Respawn.enabled) ""
          else missingRespawnPermissions(guild) match {
            // Said out loud rather than only logged. From the server's side the
            // forum is simply absent, and nothing else would explain why — or
            // that re-inviting the bot is what fixes it.
            case missing if missing.nonEmpty => s"\n\n${respawnPermissionHelp(missing)}"
            case _ =>
              try { createSpawnsForum(guild); "" }
              catch {
                case ex: Throwable =>
                  // Never fail a /setup over this — the world's channels are the
                  // point, and `/repair` can finish the forum later.
                  logger.warn(s"Could not create the respawn forum during /setup on guild '${guild.getId}'", ex)
                  ""
              }
          }

        // matches the audit pattern used by /repair and /remove
        val adminChannel = guild.getTextChannelById(discordRetrieveConfig(guild).getOrElse("admin_channel", "0"))
        com.tibiabot.presentation.AdminLog.post(adminChannel, s"${Names.user(event.getUser.getName)} has run `/setup` for the world **$world** and created its channels.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Hammer.gif")

        s":gear: The channels for **$world** have been configured successfully.\n⚠️ *You should probably mute the <#$levelsId> channel*$respawnNote"
        }
      }
      } else {
        s"${Config.noEmoji} This is not a valid World on Tibia."
      }
    } catch {
      case e: net.dv8tion.jda.api.exceptions.PermissionException =>
        logger.warn(s"/setup of '$world' on guild '${event.getGuild.getId}' aborted on a missing permission: ${e.getMessage}")
        s"${Config.noEmoji} I couldn't finish setting up **$world** because I'm missing a required permission. Grant me **Manage Roles**, **Manage Channels** and **Manage Permissions**, then run `/repair $world`."
      case e: Exception =>
        logger.warn(s"/setup of '$world' on guild '${event.getGuild.getId}' failed before completing", e)
        s"${Config.noEmoji} Something went wrong while setting up **$world**, so it may be only partially configured. Wait a moment, then run `/repair $world` (or `/setup` again) to finish."
    }
    // embed reply
    SetupResult(com.tibiabot.presentation.Embeds.response(embedText), buttons)
  }

  def repairChannel(event: SlashCommandInteractionEvent, world: String): MessageEmbed = {
    val worldFormal = com.tibiabot.domain.WorldName.formal(world)
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    embedBuild.setDescription(s"${Config.noEmoji} No action was taken as all channels for **$worldFormal** still exist.")
    val cache: Option[List[Worlds]] = streamState.worldsData.get(guild.getId) match {
      case Some(worlds) =>
        val filteredWorlds = worlds.filter(w => w.name.toLowerCase() == world.toLowerCase())
        if (filteredWorlds.nonEmpty) Some(filteredWorlds)
        else None
      case None => None
    }
    // Like /setup, this recreates roles/channels/overrides through blocking
    // .complete() calls; guard so a mid-way failure reports cleanly instead of
    // hanging the interaction with channels left half-recreated.
    try {
    if (cache.isDefined) {
      // get the bots main roles
      val botRole = guild.getBotRole
      val publicRole = guild.getPublicRole

      // get channel Ids
      val categoryInfo: Option[String] = cache.flatMap(_.headOption.map(_.category))
      val alliesChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.alliesChannel))
      val enemiesChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.enemiesChannel))
      val neutralsChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.neutralsChannel))
      val levelsChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.levelsChannel))
      val deathsChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.deathsChannel))
      val activityChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.activityChannel))
      val fullblessChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.fullblessChannel))
      val onlineCombinedInfo: Option[String] = cache.flatMap(_.headOption.map(_.onlineCombined))

      // get admin ids
      val discordConfig = discordRetrieveConfig(guild)
      var adminCategory = guild.getCategoryById(discordConfig("admin_category"))
      var adminChannel = guild.getTextChannelById(discordConfig("admin_channel"))
      var boostedChannel = guild.getTextChannelById(discordConfig("boosted_channel"))
      val boostedMessage = discordConfig("boosted_messageid")

      // get channel literals
      var category = guild.getCategoryById(categoryInfo.getOrElse("0"))
      val alliesChannel = guild.getTextChannelById(alliesChannelInfo.getOrElse("0"))
      val enemiesChannel = guild.getTextChannelById(enemiesChannelInfo.getOrElse("0"))
      val neutralsChannel = guild.getTextChannelById(neutralsChannelInfo.getOrElse("0"))
      val levelsChannel = guild.getTextChannelById(levelsChannelInfo.getOrElse("0"))
      val deathsChannel = guild.getTextChannelById(deathsChannelInfo.getOrElse("0"))
      val activityChannel = guild.getTextChannelById(activityChannelInfo.getOrElse("0"))
      val onlineCombinedVal = onlineCombinedInfo.getOrElse("true")

      val onlineCombineCheck = onlineCombinedVal == "false" && (enemiesChannel == null || neutralsChannel == null)

      val fullblessChannelId = fullblessChannelInfo.getOrElse("0")
      if (fullblessChannelId == event.getChannel.getId) {
        embedBuild.setDescription(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
        return embedBuild.build()
      }
      if (fullblessChannelId != "0") {
        val fullblessChannel = guild.getTextChannelById(fullblessChannelId)
        try {
          fullblessChannel.delete.queue()
        } catch {
          case ex: Throwable => logger.warn(s"Failed to delete fullbless Channel ID: '${fullblessChannelId}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
        }
        worldRepairConfig(guild, worldFormal, "fullbless_channel", "0")
      }
      // check if any of the world channels need to be recreated
      if (boostedChannel != null) {
        if (boostedChannel.canTalk()) {
          var fullblessMessage = false
          var nemesisMessage = false
          var allyPkMessage = false
          val messages = boostedChannel.getHistory.retrievePast(100).complete().asScala.filter { m =>
            m.getAuthor.getId.equals(botUser) && !m.isEphemeral
          }

          if (messages.nonEmpty) {
            messages.foreach { message =>
              val messageEmbeds = message.getEmbeds
              if (messageEmbeds != null && !messageEmbeds.isEmpty){
                val messageEmbed = messageEmbeds.get(0)
                val messageTitle = messageEmbed.getTitle
                if (messageTitle != null) {
                  if (messageTitle.startsWith(s":crossed_swords: $worldFormal")) {
                    fullblessMessage = true
                  } else if (messageTitle.startsWith(s"${Config.nemesisEmoji} $worldFormal")) {
                    nemesisMessage = true
                  } else if (messageTitle.startsWith(s"${Config.hazardEmoji} $worldFormal")) {
                    allyPkMessage = true
                  }
                }
              }
            }
          }
          val worldConfigData = worldRetrieveConfig(guild, world)
          if (!fullblessMessage){
            val fullblessLevel = worldConfigData("fullbless_level")
            val fullblessRoleCheck = guild.getRoleById(worldConfigData("fullbless_role"))
            val fullblessRole = if (fullblessRoleCheck == null) guild.createRole().setName(s"$worldFormal Fullbless").setColor(new Color(0, 156, 70)).complete() else fullblessRoleCheck
            val nemesisRoleCheck = guild.getRoleById(worldConfigData("nemesis_role"))
            val nemesisRole = if (nemesisRoleCheck == null) guild.createRole().setName(s"$worldFormal Rare Boss").setColor(new Color(164, 76, 230)).complete() else nemesisRoleCheck
            val allyPkRoleCheck = guild.getRoleById(worldConfigData("allypk_role"))
            val allyPkRole = if (allyPkRoleCheck == null) guild.createRole().setName(s"$worldFormal PVP").setColor(new Color(220, 0, 0)).complete() else allyPkRoleCheck
            val masslogRoleCheck = guild.getRoleById(worldConfigData("masslog_role"))
            val masslogRole = if (masslogRoleCheck == null) guild.createRole().setName(s"$worldFormal Masslog").setColor(new Color(219, 175, 72)).complete() else masslogRoleCheck

            // Fullbless Role
            boostedChannel.sendMessageEmbeds(fullblessRoleEmbed(worldFormal, fullblessRole.getId, nemesisRole.getId, allyPkRole.getId, masslogRole.getId, fullblessLevel))
              .setComponents(ActionRow.of(fullblessRoleButtons.asJava))
              .queue()

            // Update role id if it changed
            worldRepairConfig(guild, worldFormal, "fullbless_role", fullblessRole.getId)
            worldRepairConfig(guild, worldFormal, "nemesis_role", nemesisRole.getId)
            worldRepairConfig(guild, worldFormal, "allypk_role", allyPkRole.getId)
            worldRepairConfig(guild, worldFormal, "masslog_role", masslogRole.getId)

            // update the record in worldsData
            if (streamState.worldsData.contains(guild.getId)) {
              val worldsList = streamState.worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(fullblessChannel = "0", fullblessRole = fullblessRole.getId)
                } else {
                  world
                }
              }
              streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
            }
            // update the record in worldsData
            if (streamState.worldsData.contains(guild.getId)) {
              val worldsList = streamState.worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(nemesisChannel = "0", nemesisRole = nemesisRole.getId)
                } else {
                  world
                }
              }
              streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
            }
            // update the record in worldsData
            if (streamState.worldsData.contains(guild.getId)) {
              val worldsList = streamState.worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(allyPkRole = allyPkRole.getId)
                } else {
                  world
                }
              }
              streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
            }
            // update the record in worldsData
            if (streamState.worldsData.contains(guild.getId)) {
              val worldsList = streamState.worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(masslogRole = masslogRole.getId)
                } else {
                  world
                }
              }
              streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
            }
            embedBuild.setDescription(s"${Config.yesEmoji} Missing notification message was recreated.")
          }
          if (boostedMessage != "0") {
            val boostedMessageAction = boostedChannel.retrieveMessageById(boostedMessage)
            try {
              boostedMessageAction.complete()
            } catch {
              case _: Throwable =>
                postBoostedNotifications(boostedChannel, guild, worldFormal)
            }
          }
        } else {
          embedBuild.setDescription(s"${Config.noEmoji} The bot does not have VIEW/SEND permissions for the channel: **${boostedChannel.getName}**.\nI suggest you delete that channel and run the command again.")
        }
      }

      if (alliesChannel == null || onlineCombineCheck || levelsChannel == null || deathsChannel == null || activityChannel == null || adminChannel == null || boostedChannel == null) {
        if (category == null) { // category has been deleted:
          // create the category
          val newCategory = guild.createCategory(world).complete()
          grantWorldPerms(newCategory, botRole, guild.getPublicRole)
          category = newCategory
          worldRepairConfig(guild, worldFormal, "category", newCategory.getId)

          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(category = newCategory.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
        }
        val channelList = ListBuffer[(TextChannel, Boolean)]()
        // create the channels underneath the new/existing category
        if (alliesChannel == null) {
          val alliesName = if (onlineCombinedVal == "false") "🤍・ᴀʟʟɪᴇs" else "📈・ᴏɴʟɪɴᴇ"
          val recreateAlliesChannel = guild.createTextChannel(s"$alliesName", category).complete()
          channelList += ((recreateAlliesChannel, false))
          worldRepairConfig(guild, worldFormal, "allies_channel", recreateAlliesChannel.getId)
          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(alliesChannel = recreateAlliesChannel.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
        }
        if (enemiesChannel == null && onlineCombinedVal == "false") {
          val recreateEnemiesChannel = guild.createTextChannel("☠️・ᴇɴᴇᴍɪᴇs", category).complete()
          channelList += ((recreateEnemiesChannel, false))
          worldRepairConfig(guild, worldFormal, "enemies_channel", recreateEnemiesChannel.getId)
          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(enemiesChannel = recreateEnemiesChannel.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
        }
        if (neutralsChannel == null && onlineCombinedVal == "false") {
          val recreateNeutralsChannel = guild.createTextChannel("📈・ɴᴇᴜᴛʀᴀʟs", category).complete()
          channelList += ((recreateNeutralsChannel, false))
          worldRepairConfig(guild, worldFormal, "neutrals_channel", recreateNeutralsChannel.getId)
          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(neutralsChannel = recreateNeutralsChannel.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
        }
        if (deathsChannel == null) {
          val recreateDeathsChannel = guild.createTextChannel("💀・ᴅᴇᴀᴛʜs", category).complete()
          channelList += ((recreateDeathsChannel, false))
          worldRepairConfig(guild, worldFormal, "deaths_channel", recreateDeathsChannel.getId)
          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(deathsChannel = recreateDeathsChannel.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
        }
        if (levelsChannel == null) {
          val recreateLevelsChannel = guild.createTextChannel("💖・ʟᴇᴠᴇʟs", category).complete()
          channelList += ((recreateLevelsChannel, true))
          worldRepairConfig(guild, worldFormal, "levels_channel", recreateLevelsChannel.getId)
          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(levelsChannel = recreateLevelsChannel.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
        }
        if (activityChannel == null) {
          val recreateActivityChannel = guild.createTextChannel("📝・ᴀᴄᴛɪᴠɪᴛʏ", category).complete()
          channelList += ((recreateActivityChannel, false))
          worldRepairConfig(guild, worldFormal, "activity_channel", recreateActivityChannel.getId)
          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(activityChannel = recreateActivityChannel.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
          // post initial embed in activity channel
          postChannelIntro(recreateActivityChannel, s":speech_balloon: This channel shows change activity for *allied* or *enemy* players.\n\nIt will show events when a players **joins** or **leaves** one of these tracked guilds or **changes their name**.")
        }

        if (boostedChannel == null) {
          if (adminCategory == null) {
            val newAdminCategory = guild.createCategory("Violent Bot").complete()
            newAdminCategory.upsertPermissionOverride(botRole)
              .grant(Permission.VIEW_CHANNEL)
              .grant(Permission.MESSAGE_SEND)
              .complete()
            setCategoryPublicPerms(newAdminCategory, guild.getPublicRole, visible = true)
            adminCategory = newAdminCategory
          }
          // create the channel
          val newBoostedChannel = guild.createTextChannel("👑・ɴᴏᴛɪғɪᴄᴀᴛɪᴏɴs", adminCategory).complete()

          // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
          newBoostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          newBoostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          newBoostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
          newBoostedChannel.upsertPermissionOverride(guild.getPublicRole).grant(Permission.VIEW_CHANNEL).queue()
          boostedChannel = newBoostedChannel
          // update db & cache
          discordUpdateConfig(guild, adminCategory.getId, "", newBoostedChannel.getId, "", worldFormal)
          updateBoostedChannel(guild.getId, newBoostedChannel.getId)

          boostedChannel.upsertPermissionOverride(botRole)
            .grant(Permission.VIEW_CHANNEL)
            .grant(Permission.MESSAGE_SEND)
            .grant(Permission.MESSAGE_EMBED_LINKS)
            .grant(Permission.MESSAGE_HISTORY)
            .grant(Permission.MANAGE_CHANNEL)
            .complete()
          boostedChannel.upsertPermissionOverride(publicRole)
            .deny(Permission.MESSAGE_SEND)
            .complete()

          postGalthenTracker(boostedChannel)

          // Boosted Boss + creature + server-save notifications (use the canonical
          // world name so the Dream Courts lookup resolves)
          postBoostedNotifications(boostedChannel, guild, worldFormal)

          val worldConfigData = worldRetrieveConfig(guild, world)
          val fullblessLevel = worldConfigData("fullbless_level")
          val fullblessRoleCheck = guild.getRoleById(worldConfigData("fullbless_role"))
          val fullblessRole = if (fullblessRoleCheck == null) guild.createRole().setName(s"$worldFormal Fullbless").setColor(new Color(0, 156, 70)).complete() else fullblessRoleCheck
          val nemesisRoleCheck = guild.getRoleById(worldConfigData("nemesis_role"))
          val nemesisRole = if (nemesisRoleCheck == null) guild.createRole().setName(s"$worldFormal Rare Boss").setColor(new Color(164, 76, 230)).complete() else nemesisRoleCheck
          val allyPkRoleCheck = guild.getRoleById(worldConfigData("allypk_role"))
          val allyPkRole = if (allyPkRoleCheck == null) guild.createRole().setName(s"$worldFormal PVP").setColor(new Color(220, 0, 0)).complete() else allyPkRoleCheck
          val masslogRoleCheck = guild.getRoleById(worldConfigData("masslog_role"))
          val masslogRole = if (masslogRoleCheck == null) guild.createRole().setName(s"$worldFormal Masslog").setColor(new Color(219, 175, 72)).complete() else masslogRoleCheck

          // Fullbless Role
          boostedChannel.sendMessageEmbeds(fullblessRoleEmbed(worldFormal, fullblessRole.getId, nemesisRole.getId, allyPkRole.getId, masslogRole.getId, fullblessLevel))
            .setComponents(ActionRow.of(fullblessRoleButtons.asJava))
            .queue()
          // Update role id if it changed
          worldRepairConfig(guild, worldFormal, "fullbless_role", fullblessRole.getId)
          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(fullblessChannel = "0", fullblessRole = fullblessRole.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }

          // Update role id if it changed
          worldRepairConfig(guild, worldFormal, "nemesis_role", nemesisRole.getId)

          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(nemesisChannel = "0", nemesisRole = nemesisRole.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
          // Update role id if it changed
          worldRepairConfig(guild, worldFormal, "allypk_role", allyPkRole.getId)

          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(allyPkRole = allyPkRole.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }

          // Update role id if it changed
          worldRepairConfig(guild, worldFormal, "masslog_role", masslogRole.getId)

          // update the record in worldsData
          if (streamState.worldsData.contains(guild.getId)) {
            val worldsList = streamState.worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(masslogRole = masslogRole.getId)
              } else {
                world
              }
            }
            streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
          }
        }

        // apply required permissions to the new channel(s)
        if (channelList.nonEmpty) {
          channelList.foreach { case (channel, _) =>
            grantWorldPerms(channel, botRole, publicRole)
          }
        }
        // recreate admin channel and/or category
        if (adminChannel == null) {
          if (adminCategory == null) {
            val newAdminCategory = guild.createCategory("Violent Bot").complete()
            newAdminCategory.upsertPermissionOverride(botRole)
              .grant(Permission.VIEW_CHANNEL)
              .grant(Permission.MESSAGE_SEND)
              .complete()
            setCategoryPublicPerms(newAdminCategory, guild.getPublicRole, visible = true)
            adminCategory = newAdminCategory
          }
          // create the channel
          val newAdminChannel = guild.createTextChannel("🖥️・ᴄᴏᴍᴍᴀɴᴅ ʟᴏɢ", adminCategory).complete()
          // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
          newAdminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          newAdminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          newAdminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
          newAdminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          adminChannel = newAdminChannel
          // update db & cache
          discordUpdateConfig(guild, adminCategory.getId, newAdminChannel.getId, "", "", worldFormal)
          updateAdminChannel(guild.getId, newAdminChannel.getId)
        }
        com.tibiabot.presentation.AdminLog.post(adminChannel, s"${Names.user(event.getUser.getName)} has run `/repair` on the world **$worldFormal** and recreated missing channels.\n\nYou may need to rearrange their position within your discord server.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Hammer.gif")
        embedBuild.setDescription(s":gear: The missing channels for **$worldFormal** have been recreated.\nYou may need to rearrange their position within your discord server.")
      }
      // Recreate the moderator role if it was deleted, and re-store its id. Not
      // conditional on the respawn system: it gates /hunted and /allies too.
      ensureModeratorRole(guild)
      // Recreate the respawn forum/board if either has been deleted. Outside
      // the block above because that one only runs when a *world* channel is
      // missing, and the forum is guild-level — it can be deleted on its own.
      if (Config.Respawn.enabled) {
        // A repair is exactly when somebody is asking why the forum isn't there,
        // so the answer goes in the reply rather than only in the log.
        val missing = missingRespawnPermissions(guild)
        if (missing.nonEmpty) {
          logger.info(s"Skipping respawn repair on guild '${guild.getId}' — missing ${missing.mkString(", ")}")
          embedBuild.setDescription(
            s"${Option(embedBuild.getDescriptionBuilder.toString).filter(_.nonEmpty).map(_ + "\n\n").getOrElse("")}" +
              respawnPermissionHelp(missing))
        } else {
          try createSpawnsForum(guild)
          catch {
            case ex: Throwable =>
              logger.warn(s"Could not repair the respawn forum on guild '${guild.getId}'", ex)
          }
        }
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You cannot run a `/repair` on **$worldFormal** because that world has not been `/setup` yet.")
    }
    } catch {
      case e: net.dv8tion.jda.api.exceptions.PermissionException =>
        logger.warn(s"/repair of '$worldFormal' on guild '${guild.getId}' aborted on a missing permission: ${e.getMessage}")
        embedBuild.setDescription(s"${Config.noEmoji} I couldn't finish repairing **$worldFormal** because I'm missing a required permission. Grant me **Manage Roles**, **Manage Channels** and **Manage Permissions**, then run `/repair $world` again.")
      case e: Exception =>
        logger.warn(s"/repair of '$worldFormal' on guild '${guild.getId}' failed before completing", e)
        embedBuild.setDescription(s"${Config.noEmoji} Something went wrong while repairing **$worldFormal**; some channels may still be missing. Wait a moment, then run `/repair $world` again.")
    }
    embedBuild.build()
  }

  /** Delete a world's role if it still exists, logging (not throwing) on failure. */
  private def deleteRoleQuietly(role: Role, roleId: String, guild: Guild): Unit =
    if (role != null) {
      try role.delete().queue()
      catch {
        case ex: Throwable => logger.warn(s"Failed to delete Role ID: '$roleId' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
      }
    }

  def removeChannels(event: SlashCommandInteractionEvent): MessageEmbed = {
    // get guild & world information from the slash interaction
    val world: String = com.tibiabot.domain.WorldName.formal(event.getInteraction.getOptions.asScala.find(_.getName == "world").map(_.getAsString).getOrElse("").trim())
    val embedText = if (Config.worldList.contains(world) || Config.mergedWorlds.contains(world)) {
      val guild = event.getGuild
      val worldConfigData = worldRetrieveConfig(guild, world)
      // Channel/category deletion below goes through blocking .complete() calls;
      // guard so a mid-way failure reports cleanly instead of hanging the
      // interaction with the world left partially removed.
      try {
      if (worldConfigData.nonEmpty) {
        // get channel ids
        val alliesChannelId = worldConfigData("allies_channel")
        val enemiesChannelId = worldConfigData("enemies_channel")
        val neutralsChannelId = worldConfigData("neutrals_channel")
        val levelsChannelId = worldConfigData("levels_channel")
        val deathsChannelId = worldConfigData("deaths_channel")
        val fullblessChannelId = worldConfigData("fullbless_channel")
        val nemesisChannelId = worldConfigData("nemesis_channel")
        val categoryId = worldConfigData("category")
        val activityChannelId = worldConfigData("activity_channel")
        val channelIds = List(alliesChannelId, enemiesChannelId, neutralsChannelId, levelsChannelId, deathsChannelId, fullblessChannelId, nemesisChannelId, activityChannelId)

        // check if command is being run in one of the channels being deleted
        if (channelIds.contains(event.getChannel.getId)) {
          return com.tibiabot.presentation.Embeds.response(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
        }

        val fullblessRoleId = worldConfigData("fullbless_role")
        val nemesisRoleId = worldConfigData("nemesis_role")
        val allyPkRoleId = worldConfigData("allypk_role")
        val masslogRoleId = worldConfigData("masslog_role")

        val fullblessRole = guild.getRoleById(fullblessRoleId)
        val nemesisRole = guild.getRoleById(nemesisRoleId)
        val allyPkRole = guild.getRoleById(allyPkRoleId)
        val masslogRole = guild.getRoleById(masslogRoleId)

        deleteRoleQuietly(fullblessRole, fullblessRoleId, guild)
        deleteRoleQuietly(nemesisRole, nemesisRoleId, guild)
        deleteRoleQuietly(allyPkRole, allyPkRoleId, guild)
        deleteRoleQuietly(masslogRole, masslogRoleId, guild)

        // remove the guild from the world stream, cancelling it if now unused
        streamSupervisor.removeGuildFromWorld(world, guild.getId)

        // delete the channels & category
        channelIds.foreach { channelId =>
          val channel: TextChannel = guild.getTextChannelById(channelId)
          if (channel != null) {
            channel.delete().complete()
          }
        }

        val category = guild.getCategoryById(categoryId)
        if (category != null) {
          category.delete().complete()
        }

        // remove from worldsData
        val updatedWorldsData = streamState.worldsData.get(guild.getId)
          .map(_.filterNot(_.name.toLowerCase() == world.toLowerCase()))
          .map(worlds => streamState.worldsData + (guild.getId -> worlds))
          .getOrElse(streamState.worldsData)
        streamState.modifyWorldsData(_ => updatedWorldsData)

        // remove from discordsData
        streamState.discordsData.get(world)
          .foreach { discords =>
            val updatedDiscords = discords.filterNot(_.id == guild.getId)
            streamState.modifyDiscordsData(_ + (world -> updatedDiscords))
          }

        // update the database
        worldRemoveConfig(guild, world)
        paywallService.releaseSeat(guild.getId, world)

        // If that was the guild's last world, the guild-level command-log and
        // notifications channels (and the "Violent Bot" category) would be left
        // orphaned, so remove them too. Otherwise audit the removal in the
        // command-log channel (which survives).
        val remainingWorlds = updatedWorldsData.get(guild.getId).getOrElse(Nil)
        val discordConfig = discordRetrieveConfig(guild)
        if (remainingWorlds.isEmpty) {
          val boostedChannel = guild.getTextChannelById(discordConfig.getOrElse("boosted_channel", "0"))
          if (boostedChannel != null) boostedChannel.delete().complete()
          val adminChannel = guild.getTextChannelById(discordConfig.getOrElse("admin_channel", "0"))
          if (adminChannel != null) adminChannel.delete().complete()
          // Before the category: the forum is kept, so it has to be moved out
          // deliberately rather than orphaned by the category's deletion.
          retireSpawnsForum(guild)
          val adminCategory = guild.getCategoryById(discordConfig.getOrElse("admin_category", "0"))
          if (adminCategory != null) adminCategory.delete().complete()
        } else {
          val adminChannel = guild.getTextChannelById(discordConfig.getOrElse("admin_channel", "0"))
          com.tibiabot.presentation.AdminLog.post(adminChannel, s"${Names.user(event.getUser.getName)} has run `/remove` on the world **$world** and deleted its channels.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Hammer.gif")
        }

        s":gear: The world **$world** has been removed."
      } else {
        s"${Config.noEmoji} The world **$world** is not configured here."
      }
      } catch {
        case e: net.dv8tion.jda.api.exceptions.PermissionException =>
          logger.warn(s"/remove of '$world' on guild '${guild.getId}' aborted on a missing permission: ${e.getMessage}")
          s"${Config.noEmoji} I couldn't finish removing **$world** because I'm missing a required permission. Grant me **Manage Channels** and **Manage Roles**, then run `/remove $world` again."
        case e: Exception =>
          logger.warn(s"/remove of '$world' on guild '${guild.getId}' failed before completing", e)
          s"${Config.noEmoji} Something went wrong while removing **$world**; some channels may still remain. Wait a moment, then run `/remove $world` again."
      }
    } else {
      s"${Config.noEmoji} This is not a valid World on Tibia."
    }
    // embed reply
    com.tibiabot.presentation.Embeds.response(embedText)
  }

  /** Posts the welcome/help message when the bot joins a new guild. */
  def discordJoin(event: GuildJoinEvent): Unit = {
    val guild = event.getGuild
    val publicChannel = guild.getTextChannelById(guild.getDefaultChannel.getId)
    if (publicChannel != null) {
      if (publicChannel.canTalk() || !Config.prod) {
        val embedBuilder = new EmbedBuilder()
        embedBuilder.setAuthor("Violent Beams", "https://www.tibia.com/community/?subtopic=characters&name=Violent+Beams", "https://github.com/Leo32onGIT.png")
        embedBuilder.setDescription(Config.helpText)
        embedBuilder.setThumbnail(Config.webHookAvatar)
        embedBuilder.setColor(14397256) // orange for bot auto command
        try {
          publicChannel.sendMessageEmbeds(embedBuilder.build()).queue()
        } catch {
          case ex: Throwable => logger.error(s"Failed to send 'New Discord Join' message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
        }
      }
    }
  }

  /** Cleans up after the bot is removed from a guild: forgets the guild's
   *  in-memory state, cancels its world streams, and drops its database —
   *  unless the guild's config is shared with another bot. */
  def discordLeave(event: GuildLeaveEvent): Unit = {
    val guildId = event.getGuild.getId
    forgetGuild(guildId)
    streamSupervisor.removeGuild(guildId)
    logger.info(guildId)
    if (sharedConfigGuilds.contains(guildId)) {
      logger.info("Config is shared between Pulsera Bot, will use as alpha environment will delete when guild wants it deleted")
    } else {
      schemaInitializer.dropGuild(guildId)
    }
  }
}
