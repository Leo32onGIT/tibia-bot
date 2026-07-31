package com.tibiabot.interactions

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.respawn.{ClaimOutcome, OfferOutcome, ReleaseOutcome, RespawnButtonId, RespawnThreads, SlotAnswer}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

/** The buttons on a respawn's forum post, its board, and the DMs the system
 *  sends: Claim, Next, Leave, Config, and the handover Claim/Cancel pair.
 *
 *  Separated from [[ButtonHandler]]'s if/else chain rather than adding a branch
 *  per button — this family shares an id format and a permission model, so
 *  routing on the `respawn:` prefix keeps that chain at one branch however many
 *  buttons the feature grows.
 *
 *  Every reply is ephemeral: a spawn post is a shared card, and one person's
 *  click shouldn't add noise the whole thread has to scroll past. The card and
 *  the DMs are updated by the service itself.
 *
 *  ==Acknowledging in time==
 *  Discord drops an interaction that isn't acknowledged within three seconds,
 *  and most of these handlers do database work and blocking JDA calls (creating
 *  or reviving a forum thread, sending a DM) before they have anything to say.
 *  So anything answering with a *message* defers first and replies through the
 *  hook. Branches that open a *modal* cannot defer — `replyModal` has to be the
 *  first response to an interaction — so they answer directly and keep their
 *  pre-modal work to a lookup or two.
 */
object RespawnButtons extends StrictLogging {

  def handles(componentId: String): Boolean = RespawnButtonId.handles(componentId)

  /** Actions that always end in a modal, and so must not be deferred. The two
   *  Config buttons are absent deliberately: what they open depends on whether
   *  the presser is a moderator, so they decide after a single role lookup. */
  private val ModalActions: Set[String] =
    Set("claim", "mysettings", "claimrules", "timers", "selfcfg", "holdercfg", "schedule", "booknew",
        "givestamina")

  def handle(event: ButtonInteractionEvent): Unit = {
    val parsed = RespawnButtonId.parse(event.getComponentId)

    // Whether this press can be acknowledged up front, decided before any work.
    val opensModal = parsed match {
      case Some(RespawnButtonId.BoardButton(what))         => what == "config" || ModalActions.contains(what)
      case Some(RespawnButtonId.SpawnButton(action, _))     => action == "config" || ModalActions.contains(action)
      case _                                                => false
    }
    if (!opensModal) event.deferReply(true).queue()
    val respond = new Responder(event, deferred = !opensModal)

    if (!Config.Respawn.enabled) {
      respond.text(s"${Config.noEmoji} The respawn claim system isn't enabled on this bot.")
      return
    }

    parsed match {
      case None =>
        // A button from an older deploy whose id format no longer parses.
        // Ignoring it beats throwing: the post is long-lived, and the card is
        // rewritten with working buttons the next time anything happens on it.
        logger.debug(s"Ignoring unparseable respawn button id '${event.getComponentId}'")
        respond.text(s"${Config.noEmoji} That button is out of date — the post's other buttons " +
          "still work, and this one will once somebody claims or leaves this respawn.")

      // Handover offers are answered in a DM, where there is no event guild —
      // the guild id travels in the button id instead (see RespawnButtonId).
      case Some(RespawnButtonId.OfferButton(accept, guildId, claimId)) =>
        Option(event.getJDA.getGuildById(guildId)) match {
          case None =>
            respond.text(s"${Config.noEmoji} That server is no longer reachable, so this offer has expired.")
          case Some(offerGuild) =>
            val outcome =
              if (accept) BotApp.respawnService.acceptOffer(offerGuild, event.getUser.getId, claimId)
              else BotApp.respawnService.declineOffer(offerGuild, event.getUser.getId, claimId)
            replyOffer(event, respond, outcome)
        }

      // The board's buttons are what let the whole system be driven from the
      // forum: a spawn nobody has claimed has no post, so no Claim button of its
      // own.
      case Some(RespawnButtonId.BoardButton(what)) =>
        handleBoardButton(event, respond, what)

      // A spawn button pressed from a DM: no event guild, so it names its own.
      case Some(RespawnButtonId.DmSpawnButton(action, guildId, respawnId)) =>
        Option(event.getJDA.getGuildById(guildId)) match {
          case None =>
            respond.text(s"${Config.noEmoji} That server is no longer reachable.")
          case Some(dmGuild) =>
            BotApp.respawnService.listRespawns(guildId).find(_.id == respawnId) match {
              case None => respond.text(s"${Config.noEmoji} That respawn is no longer in the catalogue.")
              case Some(respawn) => action match {
                case "leave" =>
                  respond.text(renderRelease(
                    BotApp.respawnService.release(dmGuild, event.getUser.getId, Some(respawn.code))))
                case other =>
                  logger.warn(s"Unknown respawn DM button action '$other' in guild '$guildId'")
                  respond.text(s"${Config.noEmoji} That button doesn't do anything.")
              }
            }
        }

      // The owner of a booked slot answering, from a DM — so the guild travels
      // in the id, as with every other DM button.
      case Some(RespawnButtonId.SlotAnswerButton(keep, guildId, claimId)) =>
        Option(event.getJDA.getGuildById(guildId)) match {
          case None => respond.text(s"${Config.noEmoji} That server is no longer reachable.")
          case Some(slotGuild) =>
            val answer =
              if (keep) BotApp.respawnService.keepSlot(slotGuild, event.getUser.getId, claimId)
              else BotApp.respawnService.passSlot(slotGuild, event.getUser.getId, claimId,
                com.tibiabot.domain.RespawnClaim.Outcome.GivenUp)
            answer match {
              case SlotAnswer.Kept(respawn) =>
                respond.text(s"${Config.yesEmoji} **${respawn.displayName}** stays yours — " +
                  "I've let them know you're hunting it.")
                clearOfferButtons(event)
              case SlotAnswer.Passed(respawn, toUserId) =>
                respond.text(s"${Config.yesEmoji} **${respawn.displayName}** has gone to " +
                  s"<@$toUserId> for that slot. Your booking still stands for the days after.")
                clearOfferButtons(event)
              case SlotAnswer.PassedUnclaimed(respawn) =>
                respond.text(s"${Config.yesEmoji} You've given up that slot on " +
                  s"**${respawn.displayName}** — the hunt they'd booked around it no longer fits, " +
                  "so it's simply free now. Your booking still stands for the days after.")
                clearOfferButtons(event)
              case SlotAnswer.NotYours =>
                respond.text(s"${Config.noEmoji} That slot isn't yours.")
              case SlotAnswer.Gone =>
                respond.text(s"${Config.noEmoji} That's already been answered or has expired.")
                clearOfferButtons(event)
            }
        }

      // Cancelling carries the *schedule* id, not a respawn id.
      case Some(RespawnButtonId.SpawnButton("unschedule", scheduleId)) =>
        val guild = event.getGuild
        if (guild == null) respond.text(s"${Config.noEmoji} That button only works inside a server.")
        else BotApp.respawnService.findSchedule(guild.getId, scheduleId) match {
          case None => respond.text(s"${Config.noEmoji} That booking is already gone.")
          case Some(schedule) if schedule.userId != event.getUser.getId &&
                                 !RespawnModals.moderates(guild, event.getMember) =>
            respond.text(s"${Config.noEmoji} That's somebody else's booking.")
          case Some(_) =>
            BotApp.respawnService.cancelSchedule(guild, scheduleId) match {
              case None => respond.text(s"${Config.noEmoji} That booking is already gone.")
              case Some(_) => respond.text(s"${Config.yesEmoji} Booking cancelled. " +
                "Slots that hadn't started yet have been released.")
            }
        }

      // Every booking on one spawn, whoever owns it — the moderator's Cancel All.
      case Some(RespawnButtonId.SpawnButton("unschedulesall", respawnId)) =>
        val guild = event.getGuild
        if (guild == null) respond.text(s"${Config.noEmoji} That button only works inside a server.")
        else if (!RespawnModals.moderates(guild, event.getMember))
          respond.text(notModeratorText)
        else BotApp.respawnService.cancelAllBookingsOn(guild, respawnId) match {
          case 0 => respond.text(s"${Config.noEmoji} There are no bookings on that respawn.")
          case many => respond.text(s"${Config.yesEmoji} Cleared **$many** booking(s) from that " +
            "respawn. Slots that hadn't started yet have been released.")
        }

      // Every booking the presser has on one spawn, so this one carries a
      // *respawn* id where the case above carries a schedule id.
      case Some(RespawnButtonId.SpawnButton("unschedules", respawnId)) =>
        val guild = event.getGuild
        if (guild == null) respond.text(s"${Config.noEmoji} That button only works inside a server.")
        else BotApp.respawnService.cancelBookingsOn(guild, respawnId, event.getUser.getId) match {
          case 0 => respond.text(s"${Config.noEmoji} You have no bookings on that respawn.")
          case 1 => respond.text(s"${Config.yesEmoji} Booking cancelled. " +
            "Slots that hadn't started yet have been released.")
          case many => respond.text(s"${Config.yesEmoji} All **$many** of your bookings on that " +
            "respawn are cancelled. Slots that hadn't started yet have been released.")
        }

      case Some(RespawnButtonId.SpawnButton(action, respawnId)) =>
        handleSpawnButton(event, respond, action, respawnId)
    }
  }

  /** A moderator stepping from the whole-server list to their own, which is the
   *  list an ordinary member gets from /bookings — cancel button and all. */
  private def ownBookings(event: ButtonInteractionEvent, respond: Responder): Unit = {
    val guild = event.getGuild
    if (guild == null) respond.text(s"${Config.noEmoji} That button only works inside a server.")
    else {
      val entries = BotApp.respawnService.scheduleListing(guild.getId, Some(event.getUser.getId))
      val embed = RespawnEmbeds.schedulesEmbed(entries, java.time.ZonedDateTime.now())
      respond.embed(embed, if (entries.isEmpty) None else Some(RespawnThreads.bookingsButtons(entries.size)))
    }
  }

  /** Clear every booking the presser has anywhere in the guild, from /bookings.
   *  Board-shaped because it names no spawn — there is nothing for the id to
   *  carry. */
  private def cancelAllBookings(event: ButtonInteractionEvent, respond: Responder): Unit = {
    val guild = event.getGuild
    if (guild == null) respond.text(s"${Config.noEmoji} That button only works inside a server.")
    else BotApp.respawnService.cancelAllBookings(guild, event.getUser.getId) match {
      case 0 => respond.text(s"${Config.noEmoji} You have no bookings to cancel.")
      case 1 => respond.text(s"${Config.yesEmoji} Booking cancelled. " +
        "Slots that hadn't started yet have been released.")
      case many => respond.text(s"${Config.yesEmoji} All **$many** of your bookings are cancelled. " +
        "Slots that hadn't started yet have been released.")
    }
  }

  private def handleBoardButton(event: ButtonInteractionEvent, respond: Responder, what: String): Unit = {
    if (what == "cancelall") { cancelAllBookings(event, respond); return }
    if (what == "mybookings") { ownBookings(event, respond); return }
    if (what == "givestamina") {
      if (event.getGuild == null) respond.text(s"${Config.noEmoji} That button only works inside a server.")
      else if (!RespawnModals.moderates(event.getGuild, event.getMember)) respond.text(notModeratorText)
      else event.replyModal(RespawnModals.giveStaminaModal(event.getGuild.getId)).queue()
      return
    }
    val guild = event.getGuild
    if (guild == null) respond.text(s"${Config.noEmoji} That button only works inside a server.")
    else if (BotApp.respawnService.settings(guild.getId).isEmpty)
      respond.text(s"${Config.noEmoji} The respawn claim system isn't set up here.")
    else what match {
      case "claim" => event.replyModal(RespawnModals.claimModal).queue()

      case "config" =>
        // A moderator gets a choice, because Config could mean two things for
        // them. Everybody else goes straight to their own settings — no extra
        // click for the common case. Nothing has been deferred yet, so the
        // moderator branch defers itself before gathering the panel's contents.
        if (!RespawnModals.moderates(guild, event.getMember)) {
          event.replyModal(RespawnModals.configModal(guild.getId, event.getUser.getId)).queue()
        } else {
          event.deferReply(true).queue()
          val deferredRespond = new Responder(event, deferred = true)
          BotApp.respawnService.settings(guild.getId) match {
            case None => deferredRespond.text(s"${Config.noEmoji} The respawn claim system isn't set up here.")
            case Some(settings) =>
              deferredRespond.embed(RespawnEmbeds.serverSettingsEmbed(settings),
                Some(RespawnThreads.boardModeratorButtons))
          }
        }

      case "mysettings" =>
        event.replyModal(RespawnModals.configModal(guild.getId, event.getUser.getId)).queue()

      case "claimrules" | "timers" =>
        // Re-checked here, not trusted from the panel: that message persists and
        // could be clicked long after the role was taken away.
        if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
        else if (what == "claimrules") event.replyModal(RespawnModals.claimRulesModal(guild.getId)).queue()
        else event.replyModal(RespawnModals.timersModal(guild.getId)).queue()

      case other =>
        logger.warn(s"Unknown respawn board button '$other'")
        respond.text(s"${Config.noEmoji} That button doesn't do anything.")
    }
  }

  private def handleSpawnButton(event: ButtonInteractionEvent, respond: Responder,
                                action: String, respawnId: Long): Unit = {
    val guild = event.getGuild
    if (guild == null) {
      respond.text(s"${Config.noEmoji} That button only works inside a server.")
    } else {
      val service = BotApp.respawnService
      val user = event.getUser
      val guildId = guild.getId

      service.settings(guildId) match {
        case None => respond.text(s"${Config.noEmoji} The respawn claim system isn't set up here.")
        case Some(_) =>
          service.listRespawns(guildId).find(_.id == respawnId) match {
            case None =>
              respond.text(s"${Config.noEmoji} That respawn is no longer in the catalogue.")

            case Some(respawn) => action match {
              case "claim" | "next" =>
                // Both buttons go through the same claim call: whether the
                // clicker ends up holding the spawn or queued behind it is a
                // function of the spawn's state right now, not of which button
                // the card happened to be showing when they clicked.
                respond.embed(claimOutcomeEmbed(
                  service.claim(guild, user.getId, user.getName, "", respawn.code, None)))

              case "config" =>
                // Not deferred yet — see ModalActions.
                if (!RespawnModals.moderates(guild, event.getMember)) {
                  event.replyModal(RespawnModals.durationModal(guildId, user.getId, respawn)).queue()
                } else {
                  // Moderators get a panel first: these actions change somebody
                  // else's hunt, so showing whose before offering them matters.
                  event.deferReply(true).queue()
                  val deferredRespond = new Responder(event, deferred = true)
                  val holder = service.holderOf(guildId, respawn.id)
                  val ownClaim = service.openClaimsForUser(guildId, user.getId).exists(_._2.respawnId == respawn.id)
                  RespawnThreads.spawnModeratorButtons(respawn.id, holder.isDefined, ownClaim) match {
                    case None =>
                      deferredRespond.text(s"${Config.noEmoji} Nobody is on **${respawn.displayName}**, " +
                        "and you have no claim on it either.")
                    case Some(buttons) =>
                      val queueSize = service.status(guildId, respawn)._2.size
                      deferredRespond.embed(
                        RespawnEmbeds.spawnModeratorPanel(respawn, holder, queueSize), Some(buttons))
                  }
                }

              case "schedule" =>
                // One panel for everybody — same title, same state, same list of
                // who has what. A moderator looking at a spawn is asking the same
                // question as anybody else; only the buttons under it differ.
                //
                // Not deferred only in the one case that opens a modal: a member
                // with nothing booked here, who wants the form rather than a
                // panel telling them so.
                val mine = service.schedulesForUser(guildId, user.getId).filter(_.respawnId == respawn.id)
                val moderator = RespawnModals.moderates(guild, event.getMember)
                if (mine.isEmpty && !moderator) {
                  event.replyModal(RespawnModals.scheduleModal(guildId, respawn)).queue()
                } else {
                  event.deferReply(true).queue()
                  val deferredRespond = new Responder(event, deferred = true)
                  val now = java.time.ZonedDateTime.now()
                  val buttons =
                    if (moderator)
                      RespawnThreads.moderatorSpawnBookingButtons(respawn.id,
                        service.schedulesForRespawn(guildId, respawn.id).size)
                    else RespawnThreads.scheduleButtons(mine, respawn.id)
                  deferredRespond.embed(
                    RespawnEmbeds.bookingPanel(respawn, mine, user.getId,
                      service.reservationsFor(guildId, respawn.id, now),
                      service.holderOf(guildId, respawn.id), now, service.imageFor(respawn)),
                    Some(buttons))
                }

              case "booknew" =>
                // Straight to the form: they are looking at the panel that
                // listed what they already have, so there is nothing to show
                // them first.
                event.replyModal(RespawnModals.scheduleModal(guildId, respawn)).queue()

              case "holdercfg" =>
                if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
                else event.replyModal(RespawnModals.holderDurationModal(guildId, respawn)).queue()

              case "selfcfg" =>
                event.replyModal(RespawnModals.durationModal(guildId, user.getId, respawn)).queue()

              case "forceleave" =>
                if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
                else service.forceLeave(guild, respawn) match {
                  case None =>
                    respond.text(s"${Config.noEmoji} Nobody is on **${respawn.displayName}**.")
                  case Some(holder) =>
                    respond.text(s"${Config.yesEmoji} Freed **${respawn.displayName}** from " +
                      s"<@${holder.userId}>. They keep their unused stamina, and whoever is next " +
                      "has been offered it.")
                }

              case "leave" | "release" =>
                respond.text(renderRelease(service.release(guild, user.getId, Some(respawn.code))))

              case other =>
                logger.warn(s"Unknown respawn button action '$other' in guild '$guildId'")
                respond.text(s"${Config.noEmoji} That button doesn't do anything.")
            }
          }
      }
    }
  }

  private def replyOffer(event: ButtonInteractionEvent, respond: Responder, outcome: OfferOutcome): Unit =
    outcome match {
      case OfferOutcome.Accepted(respawn, claim) =>
        respond.text(s"${Config.yesEmoji} ${RespawnEmbeds.handoverAccepted(respawn, claim)}")
        clearOfferButtons(event)

      case OfferOutcome.Declined(respawn) =>
        respond.text(s"${Config.yesEmoji} You've passed on **${respawn.displayName}** and left its queue.")
        clearOfferButtons(event)

      case OfferOutcome.NoStamina(needed, tank, resetsAt) =>
        respond.text(s"${Config.noEmoji} That claim needs **${RespawnEmbeds.humanDuration(needed)}** but you " +
          s"only have **${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** of stamina left, so the " +
          s"respawn has moved on. Your tank refills at server save " +
          s"<t:${resetsAt.toInstant.getEpochSecond}:R>.")
        clearOfferButtons(event)

      case OfferOutcome.Gone =>
        respond.text(s"${Config.noEmoji} That offer has already expired or been answered.")
        clearOfferButtons(event)

      case OfferOutcome.NotYours =>
        respond.text(s"${Config.noEmoji} That offer wasn't for you.")
    }

  /** Strip the buttons off an answered offer DM, so a live-looking Claim isn't
   *  left on a spawn somebody already took.
   *
   *  Edits the message the button is attached to — `getHook.editOriginal` would
   *  edit this interaction's own reply instead, which is not what needs
   *  changing. Failure is ignored: the offer is already resolved, and the
   *  buttons refuse politely if pressed again. */
  private def clearOfferButtons(event: ButtonInteractionEvent): Unit =
    scala.util.Try(event.getMessage.editMessageComponents().queue(_ => (), _ => ()))

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

      case ClaimOutcome.Shortened(respawn, claim, _, reservedFrom) =>
        // Deliberately not "38m rather than the 2h you asked for": what they now
        // have is the useful part, and the booking below explains it without
        // making the reply about what they didn't get.
        val ends = claim.endsAt.map(e => s"<t:${e.toInstant.getEpochSecond}:R>").getOrElse("soon")
        val starts = reservedFrom
          .map(from => s"at <t:${from.toInstant.getEpochSecond}:t>")
          .getOrElse("then")
        s"${Config.yesEmoji} **${respawn.displayName}** is yours until $ends.\n" +
          s"A booked slot starts $starts, so that's all you are able to claim."

      case ClaimOutcome.Reserved(respawn, from) =>
        s"${Config.noEmoji} **${respawn.displayName}** is booked from " +
          s"<t:${from.toInstant.getEpochSecond}:t>, which leaves too little time to be worth " +
          "starting a hunt now. Press **Next** to line up for it instead."

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

  private val notModeratorText: String =
    s"${Config.noEmoji} That needs the **Manage Server** permission, " +
      s"or the **${com.tibiabot.commands.Permissions.ModeratorRoleName}** role."

  /** Sends a button's answer down whichever channel the interaction is on.
   *
   *  A deferred interaction must answer through its hook and an un-deferred one
   *  through `reply`; using the wrong one fails. Wrapping the choice keeps that
   *  decision in a single place rather than at every one of the ~20 call sites
   *  above, where a modal-opening branch and a message branch sit side by side. */
  private final class Responder(event: ButtonInteractionEvent, deferred: Boolean) {
    def embed(embed: MessageEmbed, components: Option[ActionRow] = None): Unit =
      if (deferred) {
        val action = event.getHook.sendMessageEmbeds(embed).setEphemeral(true)
        components.fold(action.queue())(row => action.setComponents(row).queue())
      } else {
        val action = event.replyEmbeds(embed).setEphemeral(true)
        components.fold(action.queue())(row => action.setComponents(row).queue())
      }

    def text(message: String): Unit = embed(Embeds.response(message))
  }
}
