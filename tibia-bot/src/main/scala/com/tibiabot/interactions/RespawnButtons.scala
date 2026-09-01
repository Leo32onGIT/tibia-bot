package com.tibiabot.interactions

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.respawn.{ClaimOutcome, ConfirmOutcome, LogScope, OfferOutcome, ReleaseOutcome, RespawnButtonId, RespawnThreads, SlotAnswer}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import com.tibiabot.presentation.Names

/** The buttons on a respawn's forum post, its board, and the DMs the system sends:
 *  Claim, Next, Leave, Config, and the handover Claim/Cancel pair.
 *
 *  Separated from [[ButtonHandler]]'s if/else chain rather than a branch per
 *  button — this family shares an id format and a permission model, so routing on
 *  the `respawn:` prefix keeps that chain at one branch however many buttons the
 *  feature grows. Every reply is ephemeral: a spawn post is a shared card, and the
 *  service updates the card and the DMs itself.
 *
 *  ==Acknowledging in time==
 *  Discord drops an interaction unacknowledged for three seconds, and most of
 *  these handlers do database work and blocking JDA calls first. So anything
 *  answering with a *message* defers and replies through the hook. Branches
 *  opening a *modal* cannot defer — `replyModal` must be the first response — so
 *  they answer directly and keep their pre-modal work to a lookup or two. */
object RespawnButtons extends StrictLogging {

  def handles(componentId: String): Boolean = RespawnButtonId.handles(componentId)

  /** What the presser is called in this guild — their nickname where they have
   *  one, their display name otherwise. Empty in a DM, where there is no member
   *  and so no guild name to take; the row then reads as the account name. */
  private def nicknameOf(event: net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent): String =
    Option(event.getMember).map(_.getEffectiveName).getOrElse("")


  def handle(event: ButtonInteractionEvent): Unit = {
    val parsed = RespawnButtonId.parse(event.getComponentId)

    // Already acknowledged by BotListener, on the event thread, unless this
    // press opens a modal — the Responder just has to answer the same way.
    val respond = new Responder(event, deferred = !RespawnButtonId.opensModal(event.getComponentId))

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

      case Some(RespawnButtonId.LogButton(scope, page)) =>
        handleLogButton(event, scope, page)

      // Opens a modal, so nothing has been deferred and nothing may be: the form
      // has to be this interaction's first response.
      case Some(RespawnButtonId.LogFindButton) =>
        val guild = event.getGuild
        if (guild == null) respond.text(s"${Config.noEmoji} That only works inside a server.")
        else if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
        else event.replyModal(RespawnModals.logFindModal).queue()

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
                respond.text(s"${Config.yesEmoji} ${RespawnEmbeds.spawnLink(respawn)} stays yours — " +
                  "I've let them know you're hunting it.")
                clearOfferButtons(event)
              case SlotAnswer.Passed(respawn, toUserName, toNickname) =>
                respond.text(s"${Config.yesEmoji} ${RespawnEmbeds.spawnLink(respawn)} has gone to " +
                  s"${Names.user(toNickname, toUserName)} for that slot. " +
                  "Your booking still stands for the days after.")
                clearOfferButtons(event)
              case SlotAnswer.PassedUnclaimed(respawn) =>
                respond.text(s"${Config.yesEmoji} You've given up that slot on " +
                  s"${RespawnEmbeds.spawnLink(respawn)} — the hunt they'd booked around it no longer fits, " +
                  "so it's simply free now. Your booking still stands for the days after.")
                clearOfferButtons(event)
              case SlotAnswer.NotYours =>
                respond.text(s"${Config.noEmoji} That slot isn't yours.")
              case SlotAnswer.Gone =>
                respond.text(s"${Config.noEmoji} That's already been answered or has expired.")
                clearOfferButtons(event)
            }
        }

      // "I'm here" — Confirm on a booking reminder, or Take Claim once that
      // booking has started. Both from a DM, so the guild travels in the id.
      case Some(RespawnButtonId.ConfirmSlotButton(guildId, claimId)) =>
        Option(event.getJDA.getGuildById(guildId)) match {
          case None => respond.text(s"${Config.noEmoji} That server is no longer reachable.")
          case Some(slotGuild) =>
            BotApp.respawnService.confirmSlot(slotGuild, event.getUser.getId, claimId) match {
              case ConfirmOutcome.Settled(respawn, _) =>
                respond.text(s"${Config.yesEmoji} ${RespawnEmbeds.spawnLink(respawn)} is settled — nobody can " +
                  "ask you for it now, and it'll start on its own with nothing left to answer.")
                clearOfferButtons(event)
              case ConfirmOutcome.Taken(respawn, _) =>
                respond.text(s"${Config.yesEmoji} ${RespawnEmbeds.spawnLink(respawn)} is yours — enjoy the hunt.")
                clearOfferButtons(event)
              case ConfirmOutcome.Already(respawn) =>
                respond.text(s"${Config.yesEmoji} You've already confirmed ${RespawnEmbeds.spawnLink(respawn)}.")
                clearOfferButtons(event)
              case ConfirmOutcome.NotYours =>
                respond.text(s"${Config.noEmoji} That booking isn't yours.")
              case ConfirmOutcome.Gone =>
                respond.text(s"${Config.noEmoji} That hunt has already been given up — you didn't " +
                  "take the claim in time, so it's gone to whoever was next.")
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
      val now = java.time.ZonedDateTime.now()
      val embed = RespawnEmbeds.schedulesEmbed(entries, now,
        givenUp = BotApp.respawnService.daysGivenUp(guild.getId, now))
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

  /** A page of the claim log, opened from a moderator panel or turned by its own
   *  buttons. Rewrites the message it was pressed on rather than stacking a fresh
   *  ephemeral log per click — hence `deferEdit` (see RespawnButtonId.ackFor) and
   *  `editOriginal` for every answer, refusals included.
   *
   *  Moderator-only, re-checked here rather than trusted from the panel: an
   *  ephemeral message persists, and the role can be taken away while it sits
   *  open. */
  private def handleLogButton(event: ButtonInteractionEvent, scope: LogScope, page: Int): Unit = {
    val guild = event.getGuild
    def refuse(text: String): Unit =
      event.getHook.editOriginalEmbeds(com.tibiabot.presentation.Embeds.response(text))
        .setComponents().queue()

    if (guild == null) refuse(s"${Config.noEmoji} That only works inside a server.")
    else if (!RespawnModals.moderates(guild, event.getMember)) refuse(notModeratorText)
    else logView(guild, scope, page) match {
      case Left(problem) => refuse(problem)
      case Right((embed, row)) =>
        event.getHook.editOriginalEmbeds(embed).setComponents(row).queue()
    }
  }

  /** One page of the log as a message: the embed, and the row that pages it.
   *
   *  Shared with the Find form's submission, so a log arrived at by searching is
   *  built exactly like one arrived at by pressing Log — same folding, same
   *  buttons, same footer. `Left` is a reason it cannot be shown, which only a
   *  spawn scope can produce: a member who has left is still a member whose
   *  hunts happened, but a spawn dropped from the catalogue has no name to
   *  print.
   *
   *  Moderator permission is the caller's business — both callers check it
   *  against the live member rather than trusting the message they were
   *  pressed on. */
  private[interactions] def logView(guild: Guild, scope: LogScope,
                                    page: Int): Either[String, (MessageEmbed, ActionRow)] = {
    val service = BotApp.respawnService
    val guildId = guild.getId
    val catalogue = service.listRespawns(guildId)
    // Every scope now renders the same way, so every scope needs the names the
    // group headers are written from — a spawn's log included, where the one name
    // it needs is the one the heading used to be built from.
    val allNames = catalogue.map(r => r.id -> r.displayName).toMap
    val scoped: Either[String, (Option[String], Map[Long, String])] = scope match {
      case LogScope.Everything => Right((None, allNames))
      // No heading: this log is a single group and that group is already headed
      // with the spawn's name. In the title as well it would be the same name
      // twice on a card that says nothing else.
      case LogScope.Spawn(id) =>
        catalogue.find(_.id == id)
          .toRight(s"${Config.noEmoji} That respawn is no longer in the catalogue.")
          .map(respawn => (None, Map(respawn.id -> respawn.displayName)))
      // Cache-only, and deliberately not fetched: a name is all this is for, and
      // a REST call per page turn to decorate a title is not worth it. Somebody
      // who has left the server falls back to the plain id, which is still the
      // right log. This one keeps its heading, since the spawns on its group
      // headers are not what it is scoped to.
      case LogScope.Member(userId) =>
        Right((Some(Option(guild.getMemberById(userId)).map(_.getEffectiveName).getOrElse(userId)),
          allNames))
    }
    scoped.map { case (what, names) =>
      val logPage = service.claimLog(guildId, scope, page)
      (RespawnEmbeds.claimLog(what, logPage, names, service.LogMaxPages),
        RespawnThreads.logButtons(scope, logPage))
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

      // Straight to the form rather than a panel first, matching Claim beside
      // it: there is no spawn yet to list this member's bookings for. Managing
      // existing bookings stays on the spawn's own post, where the Book button
      // opens that panel.
      case "book" => event.replyModal(RespawnModals.boardScheduleModal(guild.getId)).queue()

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
            case Some(settings) => deferredRespond.embed(
              RespawnEmbeds.serverSettingsEmbed(settings),
              Some(RespawnThreads.boardModeratorButtons(settings.autoClaim)))
          }
        }

      case "mysettings" =>
        event.replyModal(RespawnModals.configModal(guild.getId, event.getUser.getId)).queue()

      case "claimrules" =>
        // Re-checked here, not trusted from the panel: that message persists and
        // could be clicked long after the role was taken away.
        if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
        else event.replyModal(RespawnModals.claimRulesModal(guild.getId)).queue()

      // Flips rather than asks, so the whole setting is one press. Redraws the
      // panel it was pressed on rather than sending another one: the toggle's
      // own label and the embed's Autoclaim field both live there, and both are
      // stale the moment this is written — see RespawnButtonId.ackFor, which is
      // where the deferEdit that makes this an edit is decided.
      //
      // Only the success path edits. A refusal goes out as a follow-up through
      // `respond`, which leaves the panel alone: somebody who has just lost the
      // role should be told so, not have the settings they were reading replaced
      // by the sentence saying they may not change them.
      case "autoclaim" =>
        if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
        else {
          val service = BotApp.respawnService
          val before = service.settings(guild.getId)
          val wanted = !before.exists(_.autoClaim)
          service.setAutoClaim(guild.getId, wanted) match {
            case Left(problem) => respond.text(s"${Config.noEmoji} $problem")
            case Right(updated) =>
              // Logged like the rest of the panel's settings, because it is one:
              // a rule binding everybody who hunts here, changed by one person.
              // The toggle flips, so this normally always has something to say —
              // but it goes through the same diff as the form, which keeps it
              // quiet if the value it was pushed to was already the value.
              RespawnModals.logSettingsChange(guild, event.getUser.getName, before, updated)
              event.getHook.editOriginalEmbeds(RespawnEmbeds.serverSettingsEmbed(updated))
                .setComponents(RespawnThreads.boardModeratorButtons(updated.autoClaim))
                .queue()
          }
        }

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
                  service.claim(guild, user.getId, user.getName, nicknameOf(event), "", respawn.code, None)))

              case "config" =>
                // Not deferred yet — see ModalActions.
                if (!RespawnModals.moderates(guild, event.getMember)) {
                  // The length of their hunt here when they have one; otherwise
                  // their own defaults, which is what Config means on the board.
                  // Offering the hunt-length form to somebody with no hunt is a
                  // dead end — there is nothing to pre-fill it with and nothing
                  // for a submitted answer to land on.
                  if (service.openClaimsForUser(guildId, user.getId).exists(_._2.respawnId == respawn.id))
                    event.replyModal(RespawnModals.durationModal(guildId, user.getId, respawn)).queue()
                  else
                    event.replyModal(RespawnModals.configModal(guildId, user.getId)).queue()
                } else {
                  // Moderators get a panel first: these actions change somebody
                  // else's hunt, so showing whose before offering them matters.
                  event.deferReply(true).queue()
                  val deferredRespond = new Responder(event, deferred = true)
                  val holder = service.holderOf(guildId, respawn.id)
                  val ownClaim = service.openClaimsForUser(guildId, user.getId).exists(_._2.respawnId == respawn.id)
                  // Always a panel now: Config reaches this on a free spawn too,
                  // and the panel's own embed says whether anybody is on it.
                  val queueSize = service.status(guildId, respawn)._2.size
                  deferredRespond.embed(
                    RespawnEmbeds.spawnModeratorPanel(respawn, holder, queueSize,
                      service.settings(guildId)),
                    Some(RespawnThreads.spawnModeratorButtons(respawn.id, holder.isDefined, ownClaim)))
                }

              case "schedule" =>
                // One panel for everybody — same title, same state, same list of
                // who has what, same two buttons. A moderator looking at a spawn
                // is asking the same question as anybody else, and so is somebody
                // with nothing booked here: what is already spoken for, before
                // deciding what to ask for.
                //
                // Book used to open the form outright for that last case. It
                // saved a press at the cost of the answer — you were typing a
                // start time with no idea which ones were taken, which is what
                // this panel exists to tell you. The form is one press away, and
                // now it is one press away from knowing.
                event.deferReply(true).queue()
                val deferredRespond = new Responder(event, deferred = true)
                val now = java.time.ZonedDateTime.now()
                val mine = service.schedulesForUser(guildId, user.getId).filter(_.respawnId == respawn.id)
                val reservations = service.reservationsFor(guildId, respawn.id, now)
                // The rules with no slot written yet, exactly as the claim card
                // derives them — a repeating booking whose next evening is
                // already a row is on the panel through that row, and adding
                // its rule as well would list the one booking twice.
                val written = reservations.flatMap(_.scheduleId).toSet
                val upcoming = service.schedulesForRespawn(guildId, respawn.id)
                  .filterNot(rule => written.contains(rule.id))
                deferredRespond.embed(
                  RespawnEmbeds.bookingPanel(respawn, mine, user.getId, reservations,
                    service.holderOf(guildId, respawn.id), now, service.imageFor(respawn),
                    service.daysGivenUp(guildId, now, respawnId = Some(respawn.id)), upcoming,
                    Config.yesEmoji, Config.noEmoji),
                  Some(RespawnThreads.spawnBookingButtons(guildId, respawn.id, respawn.code, mine.size)))

              case "booknew" =>
                // Straight to the form: they are looking at the panel that
                // listed what they already have, so there is nothing to show
                // them first.
                event.replyModal(RespawnModals.scheduleModal(guildId, respawn)).queue()

              case "holdercfg" =>
                if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
                else event.replyModal(RespawnModals.holderDurationModal(guildId, respawn)).queue()

              // Only reachable from the moderator panel, but checked here as
              // well: a panel can sit open long after the role that opened it
              // was taken away, and the id is guessable by anybody who has seen
              // one.
              case "spawnmax" =>
                if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
                else event.replyModal(RespawnModals.spawnMaxModal(guildId, respawn)).queue()

              case "selfcfg" =>
                event.replyModal(RespawnModals.durationModal(guildId, user.getId, respawn)).queue()

              case "forceleave" =>
                if (!RespawnModals.moderates(guild, event.getMember)) respond.text(notModeratorText)
                else service.forceLeave(guild, respawn) match {
                  case None =>
                    respond.text(s"${Config.noEmoji} Nobody is on ${RespawnEmbeds.spawnLink(respawn)}.")
                  case Some(holder) =>
                    respond.text(s"${Config.yesEmoji} Freed ${RespawnEmbeds.spawnLink(respawn)} from " +
                      s"${Names.user(holder.nickname, holder.userName)}. They keep their unused stamina, and whoever is next " +
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
        respond.text(s"${Config.yesEmoji} You've passed on ${RespawnEmbeds.spawnLink(respawn)} and left its queue.")
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
   *  Every outcome that knows which spawn it is about names it as a link to that
   *  spawn's post, which is what the board's Claim used to append a jump link
   *  for: a member who typed a code and has never seen the post still has one
   *  click to it, and it is on the name rather than on a bare URL underneath. */
  private[interactions] def claimOutcomeEmbed(outcome: ClaimOutcome): MessageEmbed = {
    val text = outcome match {
      case ClaimOutcome.Claimed(respawn, claim) =>
        val ends = claim.endsAt.map(e => s"<t:${e.toInstant.getEpochSecond}:R>").getOrElse("soon")
        s"${Config.yesEmoji} ${RespawnEmbeds.spawnLink(respawn)} is yours until $ends."

      case ClaimOutcome.Queued(respawn, _, position) =>
        s"${Config.yesEmoji} You're **#$position** in the queue for ${RespawnEmbeds.spawnLink(respawn)}. " +
          "I'll DM you when it's your turn."

      case ClaimOutcome.AlreadyHolding(respawn, claim) =>
        if (claim.isActive) s"${Config.noEmoji} You're already on ${RespawnEmbeds.spawnLink(respawn)}."
        else s"${Config.noEmoji} You're already queued for ${RespawnEmbeds.spawnLink(respawn)} " +
          s"at **#${claim.queuePosition}**."

      case ClaimOutcome.Shortened(respawn, claim, _, reservedFrom) =>
        // Deliberately not "38m rather than the 2h you asked for": what they now
        // have is the useful part, and the booking below explains it without
        // making the reply about what they didn't get.
        val ends = claim.endsAt.map(e => s"<t:${e.toInstant.getEpochSecond}:R>").getOrElse("soon")
        val starts = reservedFrom
          .map(from => s"at <t:${from.toInstant.getEpochSecond}:t>")
          .getOrElse("then")
        s"${Config.yesEmoji} ${RespawnEmbeds.spawnLink(respawn)} is yours until $ends.\n" +
          s"A booked slot starts $starts, so that's all you are able to claim."

      case ClaimOutcome.Reserved(respawn, from) =>
        s"${Config.noEmoji} ${RespawnEmbeds.spawnLink(respawn)} is booked from " +
          s"<t:${from.toInstant.getEpochSecond}:t>, which leaves too little time to be worth " +
          "starting a hunt now. Press **Next** to line up for it instead."

      case ClaimOutcome.JustTaken(respawn) =>
        s"${Config.noEmoji} Somebody claimed ${RespawnEmbeds.spawnLink(respawn)} a moment before you. " +
          "Press **Next** to line up behind them."

      case ClaimOutcome.QueueFull(respawn, limit) =>
        s"${Config.noEmoji} The queue for ${RespawnEmbeds.spawnLink(respawn)} is full ($limit waiting)."

      case ClaimOutcome.NoStamina(respawn, needed, tank, resetsAt) =>
        s"${Config.noEmoji} ${RespawnEmbeds.spawnLink(respawn)} needs **${RespawnEmbeds.humanDuration(needed)}** but you " +
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
    Embeds.response(text)
  }

  private def renderRelease(outcome: ReleaseOutcome): String = outcome match {
    case ReleaseOutcome.Released(respawn, refunded, offered) =>
      val refund = if (refunded > 0) s"\nYou got **${RespawnEmbeds.humanDuration(refunded)}** of stamina back." else ""
      val handover = offered
        .map(claim => s"\n\n${Names.user(claim.nickname, claim.userName)} has been asked if they want it — it stays yours until they answer.")
        .getOrElse("")
      s"${Config.yesEmoji} You've released ${RespawnEmbeds.spawnLink(respawn)}.$refund$handover"
    case ReleaseOutcome.LeftQueue(respawn) =>
      s"${Config.yesEmoji} You've left the queue for ${RespawnEmbeds.spawnLink(respawn)}."
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
