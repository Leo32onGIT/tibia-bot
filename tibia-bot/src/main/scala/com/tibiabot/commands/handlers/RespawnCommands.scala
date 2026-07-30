package com.tibiabot.commands.handlers

import com.tibiabot.commands.Permissions
import com.tibiabot.domain.{Respawn, RespawnSettings}
import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.respawn.{ClaimOutcome, ReleaseOutcome}
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Handles `/respawn` — the respawn claim system's slash surface.
 *
 *  The handler stays thin on purpose: it parses options, calls
 *  `BotApp.respawnService`, and renders the outcome. Every rule (who may claim,
 *  stamina, queue limits, thread updates) lives in the service so the buttons in
 *  `interactions.RespawnButtons` get identical behaviour without duplicating it.
 */
object RespawnCommands {

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    if (guild == null) {
      reply(event, s"${Config.noEmoji} `/respawn` only works inside a server.")
      return
    }
    if (!Config.Respawn.enabled) {
      reply(event, s"${Config.noEmoji} The respawn claim system isn't enabled on this bot.")
      return
    }

    val options = Options.of(event)
    val group = Option(event.getSubcommandGroup)
    val subcommand = Option(event.getSubcommandName).getOrElse("")

    group match {
      case Some("admin") => handleAdmin(event, subcommand, options)
      case _             => handleMember(event, subcommand, options)
    }
  }

  // --- member commands ----------------------------------------------------

  private def handleMember(event: SlashCommandInteractionEvent, subcommand: String,
                           options: Map[String, String]): Unit = {
    val guild = event.getGuild
    val service = BotApp.respawnService
    val user = event.getUser

    subcommand match {
      case "claim" =>
        val duration = options.get("duration").flatMap(toInt)
        val outcome = service.claim(guild, user.getId, user.getName,
          options.getOrElse("character", ""), options.getOrElse("spawn", ""), duration)
        replyEmbed(event, renderClaim(outcome))

      case "next" =>
        // `next` is `claim` on a spawn that's taken; going through the same call
        // means a spawn that just went free is claimed outright rather than
        // dropping the user into an empty queue.
        val outcome = service.claim(guild, user.getId, user.getName,
          options.getOrElse("character", ""), options.getOrElse("spawn", ""), None)
        replyEmbed(event, renderClaim(outcome))

      case "release" =>
        val outcome = service.release(guild, user.getId, options.get("spawn").filter(_.nonEmpty))
        replyEmbed(event, Embeds.response(renderRelease(outcome)))

      case "extend" =>
        options.get("minutes").flatMap(toInt) match {
          case None => reply(event, s"${Config.noEmoji} Tell me how many minutes to add.")
          case Some(minutes) =>
            service.extend(guild, user.getId, minutes) match {
              case Right((respawn, newEnd)) =>
                reply(event, s"${Config.yesEmoji} **${respawn.displayName}** is yours until " +
                  s"<t:${newEnd.toInstant.getEpochSecond}:R>.")
              case Left(ClaimOutcome.UnknownSpawn(_)) =>
                reply(event, s"${Config.noEmoji} You aren't holding a respawn right now.")
              case Left(other) =>
                replyEmbed(event, renderClaim(other))
            }
        }

      case "status" =>
        service.settings(guild.getId) match {
          case None => reply(event, notConfiguredText)
          case Some(config) =>
            service.resolve(guild.getId, options.getOrElse("spawn", "")) match {
              case None => reply(event, unknownSpawnText(options.getOrElse("spawn", "")))
              case Some(respawn) =>
                val (active, queue) = service.status(guild.getId, respawn)
                val reservations = service.reservationsFor(guild.getId, respawn.id)
                val link = threadMention(respawn)
                replyEmbed(event, RespawnEmbeds.statusEmbed(respawn, active, queue, reservations, config,
                  service.imageFor(respawn), link))
            }
        }

      case "list" =>
        replyEmbed(event, RespawnEmbeds.activeClaimsList(service.activeClaims(guild.getId)))

      case "log" =>
        // Moderator-gated: it's an audit tool, and it names who held a spawn and
        // how each hold ended. Nothing here is secret — the thread shows most of
        // it — so opening this up later is a one-line change.
        if (!Permissions.callerIsModerator(event, BotApp.moderatorRoleId(guild.getId))) {
          reply(event, s"${Config.noEmoji} `/respawn log` needs the **Manage Server** permission, " +
            s"or the **${Permissions.ModeratorRoleName}** role.")
        } else service.resolve(guild.getId, options.getOrElse("spawn", "")) match {
          case None => reply(event, unknownSpawnText(options.getOrElse("spawn", "")))
          case Some(respawn) =>
            val limit = options.get("limit").flatMap(toInt).getOrElse(10)
            replyEmbed(event, RespawnEmbeds.claimHistoryEmbed(respawn,
              service.claimHistory(guild.getId, respawn.id, limit)))
        }

      case "stamina" =>
        service.settings(guild.getId) match {
          case None => reply(event, notConfiguredText)
          case Some(config) =>
            val tank = service.stamina(guild.getId, user.getId, config)
            val open = service.openClaimsForUser(guild.getId, user.getId)
            replyEmbed(event, RespawnEmbeds.staminaEmbed(tank, open, service.nextStaminaReset()))
        }

      case "schedules" =>
        val everyone = options.get("everyone").contains("true")
        val moderator = Permissions.callerIsModerator(event, BotApp.moderatorRoleId(guild.getId))
        if (everyone && !moderator) {
          reply(event, s"${Config.noEmoji} Showing everyone's bookings needs the **Manage Server** " +
            s"permission, or the **${Permissions.ModeratorRoleName}** role.")
        } else {
          val owner = if (everyone) None else Some(user.getId)
          val entries = service.scheduleListing(guild.getId, owner)
          val embed = RespawnEmbeds.schedulesEmbed(entries, java.time.ZonedDateTime.now(), everyone)
          // A cancel button each, up to the five Discord allows in a row. Beyond
          // that the button is on the respawn's own post anyway.
          val buttons = entries.take(5).map { case (schedule, respawn) =>
            net.dv8tion.jda.api.components.buttons.Button
              .danger(com.tibiabot.respawn.RespawnButtonId.cancelSchedule(schedule.id),
                respawn.code.take(20))
          }
          if (buttons.isEmpty) replyEmbed(event, embed)
          else event.getHook.sendMessageEmbeds(embed)
            .setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(buttons.asJava))
            .queue()
        }

      case other =>
        reply(event, s"${Config.noEmoji} Unknown `/respawn` subcommand: `$other`.")
    }
  }

  // --- admin commands -----------------------------------------------------

  private def handleAdmin(event: SlashCommandInteractionEvent, subcommand: String,
                          options: Map[String, String]): Unit = {
    if (!Permissions.callerIsModerator(event, BotApp.moderatorRoleId(event.getGuild.getId))) {
      reply(event, s"${Config.noEmoji} `/respawn admin` needs the **Manage Server** permission, " +
        s"or the **${Permissions.ModeratorRoleName}** role.")
      return
    }

    val guild = event.getGuild
    val guildId = guild.getId
    val service = BotApp.respawnService

    subcommand match {
      case "setup" =>
        val result = BotApp.channelService.createSpawnsForum(guild)
        reply(event, result)

      case "seed" =>
        service.settings(guildId) match {
          case None => reply(event, notConfiguredText)
          case Some(_) =>
            val added = service.importSeed(guildId)
            val total = service.listRespawns(guildId).size
            reply(event, s"${Config.yesEmoji} Imported **$added** new respawns — this server's catalogue " +
              s"now has **$total**.\nEntries you'd already added or edited were left alone.")
        }

      case "add" =>
        val code = options.getOrElse("code", "")
        val name = options.getOrElse("name", "")
        if (code.isEmpty || name.isEmpty) reply(event, s"${Config.noEmoji} A respawn needs both a code and a name.")
        else {
          val existing = service.resolve(guildId, code)
          if (existing.exists(_.code.equalsIgnoreCase(code)))
            reply(event, s"${Config.noEmoji} **$code** is already in the catalogue as " +
              s"**${existing.get.displayName}**. Use `/respawn admin edit` to change it.")
          else {
            val added = service.addRespawn(guildId, code, name, options.getOrElse("creature", ""),
              options.getOrElse("region", ""), options.getOrElse("world", ""),
              options.getOrElse("mapper", ""), event.getUser.getId)
            reply(event, s"${Config.yesEmoji} Added **${added.displayName}** to the catalogue.")
          }
        }

      case "edit" =>
        withRespawn(event, options) { respawn =>
          // Only the options actually supplied are changed — the repository
          // COALESCEs a missing field to its current value.
          service.editRespawn(guildId, respawn.id,
            options.get("name").filter(_.nonEmpty),
            options.get("creature"),
            options.get("world"),
            options.get("mapper"))
          service.settings(guildId).foreach { config =>
            service.resolve(guildId, respawn.code).foreach(service.refreshThread(guild, _, config))
          }
          reply(event, s"${Config.yesEmoji} Updated **${respawn.displayName}**.")
        }

      case "remove" =>
        withRespawn(event, options) { respawn =>
          service.removeRespawn(guildId, respawn.id)
          reply(event, s"${Config.yesEmoji} Removed **${respawn.displayName}** from the catalogue.\n" +
            "Its forum post is left in place — delete it yourself if you don't want the history.")
        }

      case "clear" =>
        withRespawn(event, options) { respawn =>
          val wasHeld = service.adminClear(guild, respawn)
          if (wasHeld) reply(event, s"${Config.yesEmoji} **${respawn.displayName}** has been forced free. " +
            "The holder got their unused stamina back and the queue was cleared.")
          else reply(event, s"${Config.yesEmoji} **${respawn.displayName}** wasn't claimed; its queue is cleared.")
        }

      case "config" =>
        service.updateSettings(guildId,
          options.get("duration").flatMap(toInt),
          options.get("max-duration").flatMap(toInt),
          options.get("queue-limit").flatMap(toInt),
          options.get("stamina").flatMap(toInt),
          options.get("warn").flatMap(toInt),
          options.get("handover").flatMap(toInt)) match {
          case Left(problem)  => reply(event, s"${Config.noEmoji} $problem")
          case Right(updated) => reply(event, renderConfig(updated))
        }

      case other =>
        reply(event, s"${Config.noEmoji} Unknown `/respawn admin` subcommand: `$other`.")
    }
  }

  private def withRespawn(event: SlashCommandInteractionEvent, options: Map[String, String])
                         (body: Respawn => Unit): Unit = {
    val query = options.getOrElse("spawn", "")
    BotApp.respawnService.resolve(event.getGuild.getId, query) match {
      case Some(respawn) => body(respawn)
      case None          => reply(event, unknownSpawnText(query))
    }
  }

  // --- rendering ----------------------------------------------------------

  private def renderClaim(outcome: ClaimOutcome): MessageEmbed = outcome match {
    case ClaimOutcome.Claimed(respawn, claim) =>
      val ends = claim.endsAt.map(e => s"<t:${e.toInstant.getEpochSecond}:R>").getOrElse("soon")
      Embeds.response(s"${Config.yesEmoji} **${respawn.displayName}** is yours until $ends.")

    case ClaimOutcome.Queued(respawn, _, position) =>
      Embeds.response(s"${Config.yesEmoji} You're **#$position** in the queue for " +
        s"**${respawn.displayName}**.\nI'll DM you when it's your turn — you'll have a few minutes " +
        "to confirm before it passes to the next person.")

    case ClaimOutcome.AlreadyHolding(respawn, claim) =>
      if (claim.isActive)
        Embeds.response(s"${Config.noEmoji} You're already on **${respawn.displayName}**. " +
          "Use `/respawn extend` for more time or `/respawn release` to hand it over.")
      else
        Embeds.response(s"${Config.noEmoji} You're already queued for **${respawn.displayName}** " +
          s"at **#${claim.queuePosition}**.")

    case ClaimOutcome.QueueFull(respawn, limit) =>
      Embeds.response(s"${Config.noEmoji} The queue for **${respawn.displayName}** is full ($limit waiting).")

    case ClaimOutcome.NoStamina(respawn, needed, tank, resetsAt) =>
      Embeds.response(s"${Config.noEmoji} Not enough claim stamina for " +
        s"**${respawn.displayName}**.\nThat claim needs **${RespawnEmbeds.humanDuration(needed)}** but you " +
        s"have **${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** left.\n" +
        s"Your tank refills at server save <t:${resetsAt.toInstant.getEpochSecond}:R>.")

    case ClaimOutcome.UnknownSpawn(query) =>
      Embeds.response(unknownSpawnText(query))

    case ClaimOutcome.BadDuration(requested, max) =>
      Embeds.response(s"${Config.noEmoji} **$requested** minutes isn't a valid claim length — " +
        s"claims run between 5 minutes and ${RespawnEmbeds.humanDuration(max)}.")

    case ClaimOutcome.NotConfigured =>
      Embeds.response(notConfiguredText)
  }

  private def renderRelease(outcome: ReleaseOutcome): String = outcome match {
    case ReleaseOutcome.Released(respawn, refunded, offered) =>
      val handover = offered
        .map(claim => s"\n<@${claim.userId}> is next in line and has been asked if they want it. " +
          "It stays yours until they answer, so nobody else can take it in the meantime.")
        .getOrElse("\nIt's free again.")
      val refund =
        if (refunded > 0) s" You got **${RespawnEmbeds.humanDuration(refunded)}** of stamina back."
        else ""
      s"${Config.yesEmoji} You've released **${respawn.displayName}**.$refund$handover"

    case ReleaseOutcome.LeftQueue(respawn) =>
      s"${Config.yesEmoji} You've left the queue for **${respawn.displayName}**."

    case ReleaseOutcome.AlreadyHandingOver(spawnName) =>
      s"${Config.noEmoji} **$spawnName** is already being handed over — " +
        "I'm waiting on the next person in line to answer."

    case ReleaseOutcome.NothingHeld =>
      s"${Config.noEmoji} You aren't holding or queued for any respawn."

    case ReleaseOutcome.NotConfigured =>
      notConfiguredText
  }

  private def renderConfig(settings: RespawnSettings): String = {
    val stamina =
      if (settings.staminaMinutes <= 0) "unlimited"
      else s"${RespawnEmbeds.humanDuration(settings.staminaMinutes)} per day"
    val warn = if (settings.warnMinutes <= 0) "off" else s"${settings.warnMinutes}m before the end"
    s"${Config.yesEmoji} Respawn settings updated.\n" +
      s"**Default claim:** ${RespawnEmbeds.humanDuration(settings.defaultDurationMinutes)}\n" +
      s"**Maximum claim:** ${RespawnEmbeds.humanDuration(settings.maxDurationMinutes)}\n" +
      s"**Queue limit:** ${settings.queueLimit}\n" +
      s"**Stamina:** $stamina\n" +
      s"**Warning:** $warn\n" +
      s"**Handover window:** ${RespawnEmbeds.humanDuration(settings.handoverMinutes)}"
  }

  /** A jump link to the spawn's forum post, once it has one — spawns that have
   *  never been claimed have no post yet. */
  private def threadMention(respawn: Respawn): Option[String] =
    if (respawn.threadId.isEmpty || respawn.threadId == "0") None else Some(s"<#${respawn.threadId}>")

  private val notConfiguredText: String =
    s"${Config.noEmoji} The respawn claim system isn't set up on this server yet.\n" +
      "Someone with **Manage Server** can run `/respawn admin setup`."

  private def unknownSpawnText(query: String): String =
    if (query.trim.isEmpty) s"${Config.noEmoji} Tell me which respawn you mean."
    else s"${Config.noEmoji} I don't know a respawn matching **$query**.\n" +
      "Pick one from the autocomplete list, or add it with `/respawn admin add`."

  private def toInt(value: String): Option[Int] = scala.util.Try(value.trim.toInt).toOption

  private def reply(event: SlashCommandInteractionEvent, text: String): Unit =
    replyEmbed(event, Embeds.response(text))

  private def replyEmbed(event: SlashCommandInteractionEvent, embed: MessageEmbed): Unit =
    event.getHook.sendMessageEmbeds(embed).queue()

  /** Feed the `spawn` option's autocomplete. Kept here next to the command it
   *  serves; BotListener just routes the event in.
   *
   *  Discord allows at most 25 choices and expects a reply within its normal
   *  interaction budget, so this ranks against the guild's catalogue in memory
   *  rather than querying per keystroke with a LIKE. */
  def autocompleteChoices(guildId: String, input: String): List[(String, String)] = {
    val candidates = BotApp.respawnService.autocompleteCandidates(guildId)
    com.tibiabot.respawn.RespawnCatalogue.rankMatches(candidates, input, 25)
      .map { case (code, name) => (s"$code — $name", code) }
  }

  /** Java-friendly view of [[autocompleteChoices]] for the JDA reply call. */
  def autocompleteChoicesAsJava(guildId: String, input: String)
    : java.util.List[net.dv8tion.jda.api.interactions.commands.Command.Choice] =
    autocompleteChoices(guildId, input).map { case (label, value) =>
      // Discord rejects choice names over 100 characters outright, which would
      // fail the whole autocomplete reply rather than just that entry.
      new net.dv8tion.jda.api.interactions.commands.Command.Choice(label.take(100), value)
    }.asJava
}
