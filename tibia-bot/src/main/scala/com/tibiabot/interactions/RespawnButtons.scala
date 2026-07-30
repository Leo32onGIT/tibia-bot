package com.tibiabot.interactions

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.respawn.{ClaimOutcome, OfferOutcome, ReleaseOutcome, RespawnButtonId, RespawnThreads}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

/** The buttons on a respawn's forum post: Claim, Next, Leave queue, Release.
 *
 *  Separated from [[ButtonHandler]]'s if/else chain rather than adding four more
 *  branches to it — this family shares an id format and a permission model, and
 *  routing on the `respawn:` prefix keeps the chain at one branch no matter how
 *  many buttons the feature grows.
 *
 *  Every reply is ephemeral: a spawn post is a shared card, and a click by one
 *  person shouldn't add noise the whole thread has to scroll past. The thread's
 *  card and its public notices are updated by the service itself.
 */
object RespawnButtons extends StrictLogging {

  def handles(componentId: String): Boolean = RespawnButtonId.handles(componentId)

  def handle(event: ButtonInteractionEvent): Unit = {
    if (!Config.Respawn.enabled) {
      reply(event, s"${Config.noEmoji} The respawn claim system isn't enabled on this bot.")
      return
    }
    RespawnButtonId.parse(event.getComponentId) match {
      case None =>
        // A button from an older deploy whose id format no longer parses.
        // Ignoring it beats throwing: the post is long-lived and the user can
        // always fall back to the slash command.
        logger.debug(s"Ignoring unparseable respawn button id '${event.getComponentId}'")
        reply(event, s"${Config.noEmoji} That button is out of date — use `/respawn` instead.")

      // Handover offers are answered in a DM, where there is no event guild —
      // the guild id travels in the button id instead (see RespawnButtonId).
      case Some(RespawnButtonId.OfferButton(accept, guildId, claimId)) =>
        Option(event.getJDA.getGuildById(guildId)) match {
          case None =>
            reply(event, s"${Config.noEmoji} That server is no longer reachable, so this offer has expired.")
          case Some(offerGuild) =>
            val outcome =
              if (accept) BotApp.respawnService.acceptOffer(offerGuild, event.getUser.getId, claimId)
              else BotApp.respawnService.declineOffer(offerGuild, event.getUser.getId, claimId)
            replyOffer(event, outcome)
        }

      // The board post's buttons are what let the whole system be driven from the
      // forum: a spawn nobody has claimed has no post, so no Claim button of its
      // own. Both open a modal, so neither may defer the interaction first.
      case Some(RespawnButtonId.BoardButton(what)) =>
        val guild = event.getGuild
        if (guild == null) reply(event, s"${Config.noEmoji} That button only works inside a server.")
        else if (BotApp.respawnService.settings(guild.getId).isEmpty)
          reply(event, s"${Config.noEmoji} The respawn claim system isn't set up here.")
        else what match {
          case "claim" => event.replyModal(RespawnModals.claimModal).queue()

          case "config" =>
            // A moderator gets a choice, because there are two things Config could
            // mean for them. Everybody else goes straight to their own settings —
            // no extra click for the common case.
            if (RespawnModals.moderates(guild, event.getMember)) {
              BotApp.respawnService.settings(guild.getId) match {
                case None => reply(event, s"${Config.noEmoji} The respawn claim system isn't set up here.")
                case Some(settings) =>
                  event.replyEmbeds(RespawnEmbeds.serverSettingsEmbed(settings))
                    .setComponents(RespawnThreads.boardModeratorButtons)
                    .setEphemeral(true)
                    .queue()
              }
            } else {
              event.replyModal(RespawnModals.configModal(guild.getId, event.getUser.getId)).queue()
            }

          case "mysettings" =>
            event.replyModal(RespawnModals.configModal(guild.getId, event.getUser.getId)).queue()

          case "claimrules" | "timers" =>
            // Re-checked here, not trusted from the panel: the panel message
            // persists and could be clicked after the role was taken away.
            if (!RespawnModals.moderates(guild, event.getMember)) replyNotModerator(event)
            else if (what == "claimrules") event.replyModal(RespawnModals.claimRulesModal(guild.getId)).queue()
            else event.replyModal(RespawnModals.timersModal(guild.getId)).queue()

          case other =>
            logger.warn(s"Unknown respawn board button '$other'")
            reply(event, s"${Config.noEmoji} That button doesn't do anything.")
        }

      // A spawn button pressed from a DM: no event guild, so it names its own.
      case Some(RespawnButtonId.DmSpawnButton(action, guildId, respawnId)) =>
        Option(event.getJDA.getGuildById(guildId)) match {
          case None =>
            reply(event, s"${Config.noEmoji} That server is no longer reachable.")
          case Some(dmGuild) =>
            BotApp.respawnService.listRespawns(guildId).find(_.id == respawnId) match {
              case None => reply(event, s"${Config.noEmoji} That respawn is no longer in the catalogue.")
              case Some(respawn) => action match {
                case "leave" =>
                  reply(event, renderRelease(
                    BotApp.respawnService.release(dmGuild, event.getUser.getId, Some(respawn.code))))
                case other =>
                  logger.warn(s"Unknown respawn DM button action '$other' in guild '$guildId'")
                  reply(event, s"${Config.noEmoji} That button doesn't do anything.")
              }
            }
        }

      case Some(RespawnButtonId.SpawnButton(action, respawnId)) =>
        handleSpawnButton(event, action, respawnId)
    }
  }

  private def handleSpawnButton(event: ButtonInteractionEvent, action: String, respawnId: Long): Unit = {
    val guild = event.getGuild
    if (guild == null) {
      reply(event, s"${Config.noEmoji} That button only works inside a server.")
    } else {
      val service = BotApp.respawnService
      val user = event.getUser
      val guildId = guild.getId

      service.settings(guildId) match {
        case None => reply(event, s"${Config.noEmoji} The respawn claim system isn't set up here.")
        case Some(_) =>
          service.listRespawns(guildId).find(_.id == respawnId) match {
            case None =>
              reply(event, s"${Config.noEmoji} That respawn is no longer in the catalogue.")

            case Some(respawn) => action match {
              case "claim" | "next" =>
                // Both buttons go through the same claim call: whether the
                // clicker ends up holding the spawn or queued behind it is a
                // function of the spawn's state right now, not of which
                // button the card happened to be showing when they clicked.
                val outcome = service.claim(guild, user.getId, user.getName, "", respawn.code, None)
                replyClaim(event, outcome)

              case "config" =>
                // Opens a modal or a panel, so this branch must not have deferred.
                if (!RespawnModals.moderates(guild, event.getMember)) {
                  event.replyModal(RespawnModals.durationModal(guildId, user.getId, respawn)).queue()
                } else {
                  // Moderators get a panel first: the actions here change somebody
                  // else's hunt, so showing whose before offering them matters.
                  val holder = service.holderOf(guildId, respawn.id)
                  val ownClaim = service.openClaimsForUser(guildId, user.getId).exists(_._2.respawnId == respawn.id)
                  RespawnThreads.spawnModeratorButtons(respawn.id, holder.isDefined, ownClaim) match {
                    case None =>
                      reply(event, s"${Config.noEmoji} Nobody is on **${respawn.displayName}**, " +
                        "and you have no claim on it either.")
                    case Some(buttons) =>
                      val queueSize = service.status(guildId, respawn)._2.size
                      event.replyEmbeds(RespawnEmbeds.spawnModeratorPanel(respawn, holder, queueSize))
                        .setComponents(buttons)
                        .setEphemeral(true)
                        .queue()
                  }
                }

              case "holdercfg" =>
                if (!RespawnModals.moderates(guild, event.getMember)) replyNotModerator(event)
                else event.replyModal(RespawnModals.holderDurationModal(guildId, respawn)).queue()

              case "selfcfg" =>
                event.replyModal(RespawnModals.durationModal(guildId, user.getId, respawn)).queue()

              case "forceleave" =>
                if (!RespawnModals.moderates(guild, event.getMember)) replyNotModerator(event)
                else service.forceLeave(guild, respawn) match {
                  case None =>
                    reply(event, s"${Config.noEmoji} Nobody is on **${respawn.displayName}**.")
                  case Some(holder) =>
                    reply(event, s"${Config.yesEmoji} Freed **${respawn.displayName}** from <@${holder.userId}>. " +
                      "They keep their unused stamina, and whoever is next has been offered it.")
                }

              case "leave" | "release" =>
                val outcome = service.release(guild, user.getId, Some(respawn.code))
                reply(event, renderRelease(outcome))

              case other =>
                logger.warn(s"Unknown respawn button action '$other' in guild '$guildId'")
                reply(event, s"${Config.noEmoji} That button doesn't do anything.")
            }
          }
      }
    }
  }

  private def replyOffer(event: ButtonInteractionEvent, outcome: OfferOutcome): Unit = outcome match {
    case OfferOutcome.Accepted(respawn, claim) =>
      reply(event, s"${Config.yesEmoji} ${RespawnEmbeds.handoverAccepted(respawn, claim)}")
      // The offer DM's buttons stay clickable after answering, so blank them out
      // rather than leaving a live-looking Claim on a spawn already taken.
      scala.util.Try(event.getHook.editOriginalComponents().queue(_ => (), _ => ()))

    case OfferOutcome.Declined(respawn) =>
      reply(event, s"${Config.yesEmoji} You've passed on **${respawn.displayName}** and left its queue.")

    case OfferOutcome.NoStamina(needed, tank, resetsAt) =>
      reply(event, s"${Config.noEmoji} That claim needs **${RespawnEmbeds.humanDuration(needed)}** but you only " +
        s"have **${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** of stamina left, so the respawn has " +
        s"moved on. Your tank refills at server save <t:${resetsAt.toInstant.getEpochSecond}:R>.")

    case OfferOutcome.Gone =>
      reply(event, s"${Config.noEmoji} That offer has already expired or been answered.")

    case OfferOutcome.NotYours =>
      reply(event, s"${Config.noEmoji} That offer wasn't for you.")
  }

  private def replyClaim(event: ButtonInteractionEvent, outcome: ClaimOutcome): Unit =
    event.replyEmbeds(claimOutcomeEmbed(outcome)).setEphemeral(true).queue()

  /** Rendering shared with the board's Claim modal, so pressing Claim on a
   *  spawn's post and typing its code on the board give the same answer.
   *
   *  `extra` appends a jump link to the spawn's thread when the caller has one —
   *  worth having from the board, where the member never saw the post. */
  private[interactions] def claimOutcomeEmbed(outcome: ClaimOutcome, extra: String = ""): MessageEmbed = {
    val text = outcome match {
      case ClaimOutcome.Claimed(respawn, claim) =>
        val ends = claim.endsAt.map(e => s"<t:${e.toInstant.getEpochSecond}:R>").getOrElse("soon")
        s"${Config.yesEmoji} **${respawn.displayName}** is yours until $ends."

      case ClaimOutcome.Queued(respawn, _, position) =>
        s"${Config.yesEmoji} You're **#$position** in the queue for **${respawn.displayName}**. " +
          "I'll DM you when it's your turn."

      case ClaimOutcome.AlreadyHolding(respawn, claim) =>
        if (claim.isActive) s"${Config.noEmoji} You're already on **${respawn.displayName}**."
        else s"${Config.noEmoji} You're already queued for **${respawn.displayName}** " +
          s"at **#${claim.queuePosition}**."

      case ClaimOutcome.QueueFull(respawn, limit) =>
        s"${Config.noEmoji} The queue for **${respawn.displayName}** is full ($limit waiting)."

      case ClaimOutcome.NoStamina(respawn, needed, tank, resetsAt) =>
        s"${Config.noEmoji} **${respawn.displayName}** needs **${RespawnEmbeds.humanDuration(needed)}** but you " +
          s"have **${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** of stamina left. " +
          s"It refills at server save <t:${resetsAt.toInstant.getEpochSecond}:R>."

      case ClaimOutcome.UnknownSpawn(query) =>
        if (query.trim.isEmpty) s"${Config.noEmoji} Tell me which respawn you mean."
        else s"${Config.noEmoji} I don't know a respawn matching **$query**. " +
          "Check the code, or ask an admin to add it."

      case ClaimOutcome.BadDuration(_, max) =>
        s"${Config.noEmoji} Claims can't run longer than ${RespawnEmbeds.humanDuration(max)}."

      case ClaimOutcome.NotConfigured =>
        s"${Config.noEmoji} The respawn claim system isn't set up here."
    }
    Embeds.response(text + extra)
  }

  private def renderRelease(outcome: ReleaseOutcome): String = outcome match {
    case ReleaseOutcome.Released(respawn, refunded, offered) =>
      val refund = if (refunded > 0) s" You got **${RespawnEmbeds.humanDuration(refunded)}** of stamina back." else ""
      val handover = offered
        .map(claim => s" <@${claim.userId}> has been asked if they want it — it stays yours until they answer.")
        .getOrElse("")
      s"${Config.yesEmoji} You've released **${respawn.displayName}**.$refund$handover"
    case ReleaseOutcome.LeftQueue(respawn) =>
      s"${Config.yesEmoji} You've left the queue for **${respawn.displayName}**."
    case ReleaseOutcome.AlreadyHandingOver(spawnName) =>
      s"${Config.noEmoji} **$spawnName** is already being handed over — waiting on the next person to answer."
    case ReleaseOutcome.NothingHeld =>
      s"${Config.noEmoji} You aren't holding or queued for that respawn."
    case ReleaseOutcome.NotConfigured =>
      s"${Config.noEmoji} The respawn claim system isn't set up here."
  }

  private def replyNotModerator(event: ButtonInteractionEvent): Unit =
    reply(event, s"${Config.noEmoji} That needs the **Manage Server** permission, " +
      s"or the **${com.tibiabot.commands.Permissions.ModeratorRoleName}** role.")

  private def reply(event: ButtonInteractionEvent, text: String): Unit =
    event.replyEmbeds(Embeds.response(text)).setEphemeral(true).queue()
}
