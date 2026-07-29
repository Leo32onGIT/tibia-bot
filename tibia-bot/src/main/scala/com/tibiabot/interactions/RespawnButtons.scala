package com.tibiabot.interactions

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.respawn.{ClaimOutcome, OfferOutcome, ReleaseOutcome, RespawnButtonId}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
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

  private def replyClaim(event: ButtonInteractionEvent, outcome: ClaimOutcome): Unit = outcome match {
    case ClaimOutcome.Claimed(respawn, claim) =>
      val ends = claim.endsAt.map(e => s"<t:${e.toInstant.getEpochSecond}:R>").getOrElse("soon")
      reply(event, s"${Config.yesEmoji} **${respawn.displayName}** is yours until $ends.")

    case ClaimOutcome.Queued(respawn, _, position) =>
      reply(event, s"${Config.yesEmoji} You're **#$position** in the queue for **${respawn.displayName}**. " +
        "I'll DM you when it's your turn.")

    case ClaimOutcome.AlreadyHolding(respawn, claim) =>
      if (claim.isActive) reply(event, s"${Config.noEmoji} You're already on **${respawn.displayName}**.")
      else reply(event, s"${Config.noEmoji} You're already queued at **#${claim.queuePosition}**.")

    case ClaimOutcome.QueueFull(respawn, limit) =>
      reply(event, s"${Config.noEmoji} The queue for **${respawn.displayName}** is full ($limit waiting).")

    case ClaimOutcome.NoStamina(_, needed, tank, resetsAt) =>
      reply(event, s"${Config.noEmoji} That claim needs **${RespawnEmbeds.humanDuration(needed)}** but you have " +
        s"**${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** of stamina left. " +
        s"It refills at server save <t:${resetsAt.toInstant.getEpochSecond}:R>.")

    case ClaimOutcome.UnknownSpawn(_) =>
      reply(event, s"${Config.noEmoji} That respawn is no longer in the catalogue.")

    case ClaimOutcome.BadDuration(_, max) =>
      reply(event, s"${Config.noEmoji} Claims can't run longer than " +
        s"${RespawnEmbeds.humanDuration(max)}.")

    case ClaimOutcome.NotConfigured =>
      reply(event, s"${Config.noEmoji} The respawn claim system isn't set up here.")
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

  private def reply(event: ButtonInteractionEvent, text: String): Unit =
    event.replyEmbeds(Embeds.response(text)).setEphemeral(true).queue()
}
