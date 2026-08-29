package com.tibiabot.web

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.respawn.{RespawnOwnership, RespawnService}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.{Guild, User}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** [[RespawnActionPort]] against the real service.
 *
 *  Everything here funnels through `withActableGuild`, which is the one place
 *  the two preconditions a write has beyond permission are checked: this bot has
 *  to be *in* the guild to resolve it at all, and it has to be the identity that
 *  runs that guild's respawns. Several bot identities can share a guild and only
 *  the one that built its forum runs the lifecycle, so acting from the wrong one
 *  would write claims that the bot actually sending the reminders knows nothing
 *  about.
 */
final class JdaRespawnActions(
  discordGateway: DiscordGateway,
  respawnService: RespawnService,
  ownership: RespawnOwnership,
  /** Where a calendar's rows come from. Defaults to reading them per request;
   *  the dashboard passes a cache that reads a guild's rows once and answers
   *  every spawn and every week from them — see CalendarSnapshotCache. */
  calendarRowsOf: (String, java.time.ZonedDateTime, java.time.ZonedDateTime) => CalendarRows = null
)(implicit blocking: ExecutionContext) extends RespawnActionPort with StrictLogging {

  private val rowsOf: (String, java.time.ZonedDateTime, java.time.ZonedDateTime) => CalendarRows =
    if (calendarRowsOf ne null) calendarRowsOf else respawnService.calendarRows

  /** Runs `act` only if this bot can legitimately act on the guild. Anything
   *  else answers [[RespawnActionPort.Unavailable]] — not a permission failure,
   *  which is why it reads as "do this in Discord" rather than "you can't".
   *
   *  Exceptions are caught and logged rather than propagated: a JDA hiccup
   *  mid-write should tell the person it did not work, not hand them a 500. */
  private def withActableGuild(guildId: String)(act: Guild => ActionResult): Future[ActionResult] =
    // Everything inside is blocking — JDA REST calls and database round trips —
    // so it runs on a pool of its own rather than on whichever thread served the
    // request.
    Future {
      Option(discordGateway.guildById(guildId)) match {
        case None => RespawnActionPort.Unavailable
        case Some(guild) =>
          respawnService.settings(guildId) match {
            case Some(settings) if ownership.ownsRespawns(guild, settings) =>
              try act(guild)
              catch {
                case NonFatal(e) =>
                  logger.warn(s"Dashboard action failed in guild '$guildId': ${e.getMessage}", e)
                  ActionResult(ok = false, "Something went wrong doing that. Nothing has changed.")
              }
            case Some(_) => RespawnActionPort.Unavailable
            case None =>
              ActionResult(ok = false, "The respawn system is not set up on this server.")
          }
      }
    }(blocking)

  /** Whether this bot is the one that runs `guildId`'s respawns — the same
   *  question `withActableGuild` asks, exposed so the router and the command
   *  consumer decide it exactly the same way rather than each reimplementing it. */
  def ownsGuild(guildId: String): Boolean =
    Option(discordGateway.guildById(guildId)).exists { guild =>
      respawnService.settings(guildId).exists(ownership.ownsRespawns(guild, _))
    }

  /** The account name to record against a claim. Resolved here rather than
   *  taken from the request, so it cannot be spoofed by whoever is posting —
   *  the audit log and the thread both show it. Falls back to the id, which is
   *  always true even if unfriendly, rather than to a blank. */
  private def displayName(userId: String): String =
    try Option(discordGateway.retrieveUser(userId)).map(accountName).getOrElse(userId)
    catch { case NonFatal(_) => userId }

  private def accountName(user: User): String =
    Option(user.getName).filter(_.nonEmpty).getOrElse(user.getId)

  /** Both names a claim row is written with: the account, and what to call this
   *  person on top of it.
   *
   *  The guild's nickname where it has the member cached, and otherwise the
   *  account's own display name — which every user has, so unlike the member
   *  cache this cannot come back blank. That fallback is the whole point: the
   *  bot builds its JDA with `createDefault` and no GUILD_MEMBERS intent, so the
   *  member cache is a miss for nearly everybody, and every claim and booking
   *  made from the dashboard was being written with no second name at all —
   *  landing on the card as a bare account name while the same person booking
   *  through Discord, where the interaction carries their member, got both.
   *
   *  It costs nothing: `retrieveUser` is the call [[displayName]] already made.
   *  Retrieving the *member* instead would let the server's own nickname win
   *  every time rather than only when cached, but at a REST round trip per
   *  write for a name many guilds do not set.
   */
  private def namesFor(guild: Guild, userId: String): (String, String) =
    try {
      Option(discordGateway.retrieveUser(userId)) match {
        case None => (userId, "")
        case Some(user) =>
          (accountName(user),
            Option(guild.getMemberById(userId)).map(_.getEffectiveName).getOrElse(user.getEffectiveName))
      }
    } catch { case NonFatal(_) => (userId, "") }


  def claim(guildId: String, userId: String, characterName: String,
            code: String, minutes: Option[Int]): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      val (name, nickname) = namesFor(guild, userId)
      RespawnActions.describe(
        respawnService.claim(guild, userId, name, nickname, characterName, code, minutes))
    }

  def release(guildId: String, userId: String, code: Option[String]): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      RespawnActions.describe(respawnService.release(guild, userId, code))
    }

  def extend(guildId: String, userId: String, extraMinutes: Int): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.extend(guild, userId, extraMinutes) match {
        case Right((respawn, endsAt)) =>
          ActionResult(ok = true, s"${respawn.displayName} now runs until ${endsAt.toInstant}.")
        case Left(outcome) => RespawnActions.describe(outcome)
      }
    }

  def book(guildId: String, userId: String, characterName: String, code: String,
           firstStart: java.time.ZonedDateTime, durationMinutes: Int, daysOfWeek: Int): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.resolve(guildId, code) match {
        case None => ActionResult(ok = false, s"No spawn matches '$code'.")
        case Some(respawn) =>
          val (name, nickname) = namesFor(guild, userId)
          respawnService.addSchedule(guild, respawn, userId, name, nickname, characterName,
            firstStart, durationMinutes, daysOfWeek) match {
            case Right(com.tibiabot.respawn.ScheduleResult.Booked(schedule)) =>
              ActionResult(ok = true,
                s"${respawn.displayName} booked — ${schedule.repeatLabel}, ${schedule.durationMinutes} minutes.")
            // Not a refusal: the clash has been put to the slot's owner, and the
            // booking lands only if they say they are not hunting it.
            case Right(com.tibiabot.respawn.ScheduleResult.Requested(_, slot, deadline)) =>
              ActionResult(ok = true,
                s"That time is ${slot.userName}'s. They have been asked whether they are actually " +
                  s"hunting it, and have until ${deadline.toInstant} to answer.")
            case Left(reason) => ActionResult(ok = false, reason)
          }
      }
    }

  def cancelBooking(guildId: String, userId: String, scheduleId: Long): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      // Ownership is checked here rather than trusting the id from the page: a
      // schedule id is a small integer and guessing one must not let somebody
      // cancel a booking that is not theirs.
      respawnService.findSchedule(guildId, scheduleId).filter(_.userId == userId) match {
        case None => ActionResult(ok = false, "That booking is not yours, or has already gone.")
        case Some(_) =>
          respawnService.cancelSchedule(guild, scheduleId) match {
            case Some(_) => ActionResult(ok = true, "Booking cancelled.")
            case None    => ActionResult(ok = false, "That booking has already gone.")
          }
      }
    }

  def bookings(guildId: String, userId: String): List[BookingView] = {
    val givenUp = respawnService.daysGivenUp(guildId, java.time.ZonedDateTime.now())
    respawnService.scheduleListing(guildId, Some(userId)).map { case (schedule, respawn) =>
      view(schedule, respawn, guildId, givenUp)
    }
  }

  def bookingsOn(guildId: String, code: String): List[BookingView] =
    respawnService.resolve(guildId, code).toList.flatMap { respawn =>
      val givenUp = respawnService.daysGivenUp(guildId, java.time.ZonedDateTime.now(),
        respawnId = Some(respawn.id))
      respawnService.schedulesForRespawn(guildId, respawn.id).map(view(_, respawn, guildId, givenUp))
    }

  /** One spawn's window, expanded into blocks. The gathering is here; the
   *  deciding is in [[JdaRespawnActions.assembleCalendar]], which is pure. */
  def calendar(guildId: String, code: String,
               from: java.time.ZonedDateTime, to: java.time.ZonedDateTime): Option[CalendarView] =
    respawnService.resolve(guildId, code).map { respawn =>
      // One read of the guild, sliced to this spawn. Every panel open on this
      // guild in the next few seconds is answered from the same rows.
      val rows = rowsOf(guildId, from, to)
      val schedules = rows.schedules.getOrElse(respawn.id, Nil)
      val scheduleIds = schedules.map(_.id).toSet
      JdaRespawnActions.assembleCalendar(
        respawn,
        rows.active.get(respawn.id),
        // Anchored at the window's own start rather than at `now`, so a grid
        // showing earlier in the week still draws what was booked then — the
        // rows may reach further back than this window does.
        rows.reservations.getOrElse(respawn.id, Nil)
          .filter(_.startsAt.exists(!_.isBefore(from)))
          .sortBy(_.startsAt.map(_.toInstant).getOrElse(java.time.Instant.MIN)),
        schedules,
        rows.givenUp.filter { case (id, _) => scheduleIds.contains(id) },
        from, to,
        // Only where there is a past to read. A week nobody has reached yet
        // holds no finished claims, and asking the database to confirm that
        // once per week per spawn visited is a query bought for nothing. Left
        // out of the snapshot deliberately: it is the one part of a calendar
        // that cannot go stale, and the one no other reader wants.
        if (from.isBefore(java.time.ZonedDateTime.now())) respawnService.historyFor(guildId, respawn.id, from, to)
        else Nil)
    }

  /** A schedule as the calendar draws it.
   *
   *  The state comes from the slot the schedule has actually produced, not from
   *  the schedule itself — asked and confirmed are properties of an occurrence,
   *  and a rule that has not materialised one yet is simply booked. */
  private def view(schedule: com.tibiabot.domain.RespawnSchedule,
                   respawn: com.tibiabot.domain.Respawn, guildId: String,
                   givenUp: Map[Long, Set[java.time.Instant]]): BookingView = {
    val slot = respawnService.reservationsFor(guildId, respawn.id)
      .find(_.scheduleId.contains(schedule.id))
    val state = slot match {
      case Some(s) if s.requesterUserId.isDefined => com.tibiabot.respawn.RespawnBoardEntry.Asked
      case Some(s) if s.askedAt.isDefined         => com.tibiabot.respawn.RespawnBoardEntry.Confirmed
      case _                                      => com.tibiabot.respawn.RespawnBoardEntry.Booked
    }
    BookingView(
      scheduleId = schedule.id,
      code = respawn.code,
      spawnName = respawn.name,
      owner = if (schedule.characterName.nonEmpty) schedule.characterName else schedule.userName,
      ownerId = schedule.userId,
      // The next occurrence rather than the anchor, since a weekly booking's
      // anchor may be weeks behind and means nothing to somebody planning.
      startsAt = schedule
        .nextStartAtOrAfter(java.time.ZonedDateTime.now(), givenUp.getOrElse(schedule.id, Set.empty))
        .getOrElse(schedule.anchorAt),
      durationMinutes = schedule.durationMinutes,
      daysOfWeek = schedule.daysOfWeek,
      repeats = schedule.repeats,
      state = state
    )
  }

  def forceLeave(guildId: String, actorId: String, code: String): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.resolve(guildId, code) match {
        case None => ActionResult(ok = false, s"No spawn matches '$code'.")
        case Some(respawn) =>
          respawnService.forceLeave(guild, respawn) match {
            case Some(holder) =>
              logger.info(s"Dashboard: '$actorId' moved '${holder.userId}' off ${respawn.code} in guild '$guildId'")
              ActionResult(ok = true, s"${holder.userName} has been moved off ${respawn.displayName}.")
            case None => ActionResult(ok = false, s"Nobody is holding ${respawn.displayName}.")
          }
      }
    }

  def reassign(guildId: String, actorId: String, code: String, toUserId: String): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.resolve(guildId, code) match {
        case None => ActionResult(ok = false, s"No spawn matches '$code'.")
        case Some(respawn) =>
          val (toName, toNickname) = namesFor(guild, toUserId)
          respawnService.reassignClaim(guild, respawn.id, toUserId, toName, toNickname) match {
            case Right((spawn, claim)) =>
              logger.info(s"Dashboard: '$actorId' reassigned ${spawn.code} to '$toUserId' in guild '$guildId'")
              ActionResult(ok = true, s"${spawn.displayName} now belongs to ${claim.userName}.")
            case Left(reason) => ActionResult(ok = false, reason)
          }
      }
    }

  def grantStamina(guildId: String, actorId: String, targetUserId: String, minutes: Int): Future[ActionResult] =
    withActableGuild(guildId) { _ =>
      respawnService.settings(guildId) match {
        case None => ActionResult(ok = false, "The respawn system is not set up on this server.")
        case Some(settings) if settings.staminaMinutes <= 0 =>
          ActionResult(ok = false, "Stamina is switched off on this server, so there is nothing to grant.")
        case Some(settings) =>
          val tank = respawnService.grantStamina(guildId, targetUserId, minutes, settings)
          logger.info(s"Dashboard: '$actorId' granted $minutes stamina to '$targetUserId' in guild '$guildId'")
          ActionResult(ok = true,
            s"${displayName(targetUserId)} now has ${tank.remainingMinutes} minutes left today.")
      }
    }

  def addSpawn(guildId: String, actorId: String, code: String, region: String,
               name: String, creature: String): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.settings(guildId) match {
        case None => ActionResult(ok = false, "The respawn system is not set up on this server.")
        case Some(settings) =>
          respawnService.addCustomSpawn(guildId, actorId, code, region, name, creature) match {
            case Left(reason) => ActionResult(ok = false, reason)
            case Right(added) =>
              // The board post *is* the list of codes — a spawn nobody can read
              // off it is one nobody will ever claim — so the picture is put
              // right here rather than waiting for the next restart.
              respawnService.redrawBoardIfChanged(guild, settings)
              ActionResult(ok = true, s"Added ${added.displayName}.")
          }
      }
    }

  def setSpawnMax(guildId: String, actorId: String, code: String,
                  minutes: Option[Int]): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.resolve(guildId, code) match {
        case None => ActionResult(ok = false, "That respawn isn't in the catalogue.")
        case Some(respawn) => respawnService.setSpawnMaxDuration(guild, respawn, minutes) match {
          case Left(reason) => ActionResult(ok = false, reason)
          case Right(updated) => updated.maxDurationMinutes match {
            case None =>
              ActionResult(ok = true, s"${updated.displayName} follows the server's maximum again.")
            case Some(value) =>
              ActionResult(ok = true,
                s"Claims on ${updated.displayName} can now run up to " +
                  s"${com.tibiabot.presentation.RespawnEmbeds.humanDuration(value)}.")
          }
        }
      }
    }

  def extendHolder(guildId: String, actorId: String, code: String, extraMinutes: Int): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.resolve(guildId, code) match {
        case None => ActionResult(ok = false, s"No spawn matches '$code'.")
        case Some(respawn) =>
          respawnService.extendHolder(guild, respawn, extraMinutes) match {
            case Left(reason) => ActionResult(ok = false, reason)
            case Right((claim, endsAt)) =>
              logger.info(s"Dashboard: '$actorId' extended '${claim.userId}' on ${respawn.code} " +
                s"by $extraMinutes minutes in guild '$guildId'")
              ActionResult(ok = true, s"${respawn.displayName} now runs until ${endsAt.toInstant}.")
          }
      }
    }

  def dropSlot(guildId: String, actorId: String, code: String,
               startsAt: java.time.ZonedDateTime): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.resolve(guildId, code) match {
        case None => ActionResult(ok = false, s"No spawn matches '$code'.")
        case Some(respawn) =>
          respawnService.dropSlot(guild, respawn, startsAt) match {
            case Left(reason) => ActionResult(ok = false, reason)
            case Right(owner) =>
              logger.info(s"Dashboard: '$actorId' took $owner's ${respawn.code} slot at " +
                s"${startsAt.toInstant} off the calendar in guild '$guildId'")
              ActionResult(ok = true, s"The booking for ${respawn.displayName} has been removed.")
          }
      }
    }

  def reassignSlot(guildId: String, actorId: String, code: String,
                   startsAt: java.time.ZonedDateTime, toUserId: String): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.resolve(guildId, code) match {
        case None => ActionResult(ok = false, s"No spawn matches '$code'.")
        case Some(respawn) =>
          val (toName, toNickname) = namesFor(guild, toUserId)
          respawnService.reassignSlot(guild, respawn, startsAt, toUserId, toName, toNickname) match {
            case Left(reason) => ActionResult(ok = false, reason)
            case Right(from) =>
              logger.info(s"Dashboard: '$actorId' moved $from's ${respawn.code} slot at " +
                s"${startsAt.toInstant} to '$toUserId' in guild '$guildId'")
              ActionResult(ok = true,
                s"That slot on ${respawn.displayName} is now $toName's.")
          }
      }
    }

  def editSlot(guildId: String, actorId: String, code: String,
               startsAt: java.time.ZonedDateTime, minutes: Int): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.resolve(guildId, code) match {
        case None => ActionResult(ok = false, s"No spawn matches '$code'.")
        case Some(respawn) =>
          respawnService.editSlot(guild, respawn, startsAt, minutes) match {
            case Left(reason) => ActionResult(ok = false, reason)
            case Right(edit) =>
              logger.info(s"Dashboard: '$actorId' set ${edit.owner}'s ${respawn.code} " +
                s"${if (edit.live) "hunt" else "slot"} at ${startsAt.toInstant} to ${edit.minutes}m " +
                s"in guild '$guildId'")
              // That it now reaches into the next booking is said on the way
              // past rather than left to be discovered: the write has happened,
              // and this is the one moment somebody is looking at the answer.
              //
              // Whose booking is not. The panel answers about the slot that was
              // selected, and the grid beside the message already says whose
              // every block on it is.
              val overrun = edit.cutInto
                .map(_ => " It now runs into the next booking, which will be cut short.")
                .getOrElse("")
              // Which of the two it was, because the panel acts on both and
              // "the booking" and "the claim" are the words the grid and the
              // card already use for them.
              val what = if (edit.live) "claim" else "booking"
              ActionResult(ok = true,
                s"The $what for ${respawn.displayName} now runs for " +
                  s"${com.tibiabot.presentation.RespawnEmbeds.humanDuration(edit.minutes)}.$overrun")
          }
      }
    }

  def removeSpawn(guildId: String, actorId: String, code: String): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      respawnService.settings(guildId) match {
        case None => ActionResult(ok = false, "The respawn system is not set up on this server.")
        case Some(settings) =>
          respawnService.removeCustomSpawn(guildId, code) match {
            case Left(reason) => ActionResult(ok = false, reason)
            case Right(removed) =>
              logger.info(s"Dashboard: '$actorId' removed ${removed.code} from guild '$guildId'")
              // The post goes with the code. Left behind, it would offer a Claim
              // button for a spawn nothing can resolve any more.
              com.tibiabot.respawn.RespawnThreads.deleteThread(guild, settings, removed.threadId)
              respawnService.redrawBoardIfChanged(guild, settings)
              ActionResult(ok = true, s"Removed ${removed.displayName}.")
          }
      }
    }
}

object JdaRespawnActions {
  import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSchedule}
  import com.tibiabot.respawn.RespawnBoardEntry

  /** One spawn's window turned into blocks, from the three things that can put
   *  one on the grid.
   *
   *  A live hunt, which is the only block that is happening rather than
   *  promised. The slots already written down, which are the ones that know
   *  whether their owner has been asked. And the occurrences a standing rule
   *  will produce but which have not been booked into rows yet — drawn as well,
   *  because a week showing only the materialised ones would look empty next
   *  Tuesday and let somebody plan straight into a weekly booking.
   *
   *  A predicted occurrence is dropped when a row already exists for it, so the
   *  same slot is never drawn twice. The row wins because it knows things the
   *  rule cannot: who has been asked, and whether the window was handed over.
   *
   *  Pure, so the whole expansion — including the one that spans local midnight
   *  and the one that has been asked about — can be checked without a database.
   */
  def assembleCalendar(respawn: Respawn,
                       active: Option[RespawnClaim],
                       reservations: List[RespawnClaim],
                       schedules: List[RespawnSchedule],
                       /** Days each rule has given up, keyed by schedule. */
                       givenUp: Map[Long, Set[java.time.Instant]],
                       from: java.time.ZonedDateTime,
                       to: java.time.ZonedDateTime,
                       /** Claims that have already finished in this window. */
                       history: List[RespawnClaim] = Nil): CalendarView = {
    val hunting = active.toList.flatMap { claim =>
      for { start <- claim.startsAt; end <- claim.endsAt if start.isBefore(to) && end.isAfter(from) }
        yield CalendarSlot(None, claim.userId, label(claim.userName, claim.characterName),
          claim.userName, claim.nickname,
          start, end, RespawnBoardEntry.Claimed,
          repeats = false, daysOfWeek = RespawnSchedule.OneOff, predicted = false)
    }

    val booked = reservations.flatMap { slot =>
      slot.startsAt.filter(_.isBefore(to)).map { start =>
        val schedule = slot.scheduleId.flatMap(id => schedules.find(_.id == id))
        CalendarSlot(
          scheduleId = slot.scheduleId,
          ownerId = slot.userId,
          owner = label(slot.userName, slot.characterName),
          account = slot.userName,
          nickname = slot.nickname,
          startsAt = start,
          endsAt = start.plusMinutes(slot.durationMinutes.toLong),
          // A slot somebody has been asked about is `asked` until it is
          // answered, and `confirmed` once it has been — the same three words
          // the board uses, so a spawn does not change vocabulary between the
          // two pages.
          state =
            if (slot.requesterUserId.isDefined) RespawnBoardEntry.Asked
            else if (slot.askedAt.isDefined) RespawnBoardEntry.Confirmed
            else RespawnBoardEntry.Booked,
          repeats = schedule.exists(_.repeats),
          daysOfWeek = schedule.map(_.daysOfWeek).getOrElse(RespawnSchedule.OneOff),
          predicted = false)
      }
    }

    // A rule stops predicting a day for one of two reasons: the day is already
    // on the grid, or it is over.
    //
    // Only the first used to be checked, and only through the reservations —
    // which is the bug that put two names on one evening. A day handed to
    // somebody else is no longer a reservation of the rule that produced it,
    // and a day being hunted has become the live claim, so in both cases
    // nothing suppressed the rule and it drew its old owner again beside
    // whoever had actually taken the evening.
    //
    // The two are kept apart rather than read off the occurrence rows wholesale,
    // because "written down" and "drawn" are not the same set: a slot starting
    // exactly on the window's edge has a row but no block, and letting its row
    // silence the rule would leave that evening on the grid as nothing at all.
    val drawn = booked.flatMap(slot => slot.scheduleId.map(_ -> slot.startsAt.toInstant)) ++
      (for { claim <- active.toList; id <- claim.scheduleId; start <- claim.startsAt }
        yield id -> start.toInstant)
    val over = givenUp.toList.flatMap { case (id, days) => days.map(id -> _) }
    val written = (drawn ++ over).toSet
    val predicted = schedules.flatMap { schedule =>
      schedule.occurrencesBetween(from, to)
        .filterNot(start => written.contains(schedule.id -> start.toInstant))
        .map(start => CalendarSlot(Some(schedule.id), schedule.userId,
          label(schedule.userName, schedule.characterName),
          schedule.userName, schedule.nickname,
          start, schedule.endOf(start), RespawnBoardEntry.Booked,
          schedule.repeats, schedule.daysOfWeek, predicted = true))
    }

    // What has already happened here, drawn between the two ends of what was
    // actually held.
    //
    // Not past what it was due to run, which `endedAt` alone does not respect:
    // a slot whose whole window went by while the bot was down is closed by the
    // sweep that later notices it (see RespawnService's missedReservations), so
    // its `endedAt` is when it was found rather than when the evening was over.
    // Drawn to that, an evening nobody had reached from its start to whenever
    // the bot next ran — hours of grid for a hunt that never happened.
    //
    // Nor past when it really stopped, which is the other half of the same
    // rule: a hunt given up after twenty minutes was twenty minutes, and a
    // block drawn to its deadline would be drawing an evening nobody had
    // either. So the end is the earlier of the two.
    val finished = history.flatMap { claim =>
      claim.startsAt.filter(start => start.isBefore(to)).flatMap { start =>
        val due = claim.endsAt.getOrElse(start.plusMinutes(claim.durationMinutes.toLong))
        val ended = claim.endedAt.filter(_.isBefore(due)).getOrElse(due)
        // A row that finished at or before it began never happened, and gets no
        // block. Most of them are a booking given up before its evening came
        // round: cancelling one leaves a history row that keeps the future start
        // it was made for, so this is the one kind of history that is not in the
        // past at all.
        //
        // This used to be drawn a minute tall instead, on the reasoning that
        // invisible is worse than absent. Absent is better: the stub sat on a
        // slot that was free and every reader that measures the grid took it for
        // a booking, so the evening somebody had *cancelled* refused to be
        // booked again — by them or by anybody — and offered to be cancelled a
        // second time. Nothing is lost by leaving it out. The row stays in the
        // database, and the claim log is where it is read, which is the surface
        // that can say "booking cancelled" in words rather than as a mark on a
        // timetable.
        if (!ended.isAfter(start)) None
        else Some(CalendarSlot(
          scheduleId = claim.scheduleId,
          ownerId = claim.userId,
          owner = label(claim.userName, claim.characterName),
          account = claim.userName,
          nickname = claim.nickname,
          startsAt = start,
          endsAt = ended,
          state = if (wasHunted(claim)) RespawnBoardEntry.Claimed else RespawnBoardEntry.Booked,
          repeats = false,
          daysOfWeek = RespawnSchedule.OneOff,
          predicted = false,
          past = true,
          hunted = wasHunted(claim),
          note = historyNote(claim)))
      }
    }

    CalendarView(respawn.code, respawn.name, respawn.creature,
      (finished ++ hunting ++ booked ++ predicted).sortBy(_.startsAt.toInstant))
  }

  /** Whether anybody was actually on the spawn.
   *
   *  Read off the outcome rather than off the status, because both kinds of row
   *  finish the same way: an evening hunted to its end and an evening nobody
   *  turned up for are both closed rows with a window on them. The outcomes
   *  below are the ones that can only be reached from a hunt in progress —
   *  everything else closed a booking that never started.
   *
   *  A row from before outcomes were recorded has none. Those are read as
   *  hunted: an old row with a start and an end is a hunt that happened, and
   *  calling it a no-show would be inventing an accusation. */
  private[web] def wasHunted(claim: RespawnClaim): Boolean = claim.outcome match {
    case None => true
    case Some(outcome) => outcome == RespawnClaim.Outcome.Completed ||
      outcome == RespawnClaim.Outcome.Released ||
      outcome == RespawnClaim.Outcome.Forced ||
      outcome == RespawnClaim.Outcome.Cleared ||
      outcome == RespawnClaim.Outcome.TakenOver ||
      outcome == RespawnClaim.Outcome.Unconfirmed
  }

  /** What became of an evening, said the way somebody who was not there would
   *  ask about it. Empty for the ordinary case — a hunt that ran its time needs
   *  no explaining, and a note on every block would be noise on all of them. */
  private[web] def historyNote(claim: RespawnClaim): String = claim.outcome match {
    case Some(RespawnClaim.Outcome.Released)     => "given up early"
    case Some(RespawnClaim.Outcome.Forced)       => "ended by a moderator"
    case Some(RespawnClaim.Outcome.Cleared)      => "spawn was cleared"
    case Some(RespawnClaim.Outcome.TakenOver)    => "handed over"
    case Some(RespawnClaim.Outcome.Unconfirmed)  => "never confirmed"
    case Some(RespawnClaim.Outcome.Missed)       => "never started"
    case Some(RespawnClaim.Outcome.GivenUp)      => "given up when asked"
    case Some(RespawnClaim.Outcome.NoAnswer)     => "no answer, passed on"
    case Some(RespawnClaim.Outcome.Merged)       => "folded into a hunt already running"
    case Some(RespawnClaim.Outcome.ScheduleCancelled) => "booking cancelled"
    case Some(RespawnClaim.Outcome.SlotRemoved)  => "taken off the day"
    case Some(RespawnClaim.Outcome.SlotMoved)    => "given to somebody else"
    case _                                       => ""
  }

  /** Who a block belongs to, as a person would say it: the character when there
   *  is one, since that is what the rest of the team recognises. */
  private def label(userName: String, characterName: String): String =
    if (characterName.nonEmpty) characterName else userName
}
