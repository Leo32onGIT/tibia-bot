package com.tibiabot.web

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.respawn.{RespawnOwnership, RespawnService}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.Guild

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** [[RespawnActionPort]] against the real service.
 *
 *  Everything here funnels through [[withActableGuild]], which is the one place
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
  ownership: RespawnOwnership
)(implicit blocking: ExecutionContext) extends RespawnActionPort with StrictLogging {

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

  /** The name to record against a claim. Resolved here rather than taken from
   *  the request, so it cannot be spoofed by whoever is posting — the audit log
   *  and the thread both show it. Falls back to the id, which is always true
   *  even if unfriendly, rather than to a blank. */
  private def displayName(userId: String): String =
    try Option(discordGateway.retrieveUser(userId)).map(_.getName).filter(_.nonEmpty).getOrElse(userId)
    catch { case NonFatal(_) => userId }

  def claim(guildId: String, userId: String, characterName: String,
            code: String, minutes: Option[Int]): Future[ActionResult] =
    withActableGuild(guildId) { guild =>
      RespawnActions.describe(
        respawnService.claim(guild, userId, displayName(userId), characterName, code, minutes))
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
          respawnService.addSchedule(guild, respawn, userId, displayName(userId), characterName,
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

  def bookings(guildId: String, userId: String): List[BookingView] =
    respawnService.scheduleListing(guildId, Some(userId)).map { case (schedule, respawn) =>
      view(schedule, respawn, guildId)
    }

  def bookingsOn(guildId: String, code: String): List[BookingView] =
    respawnService.resolve(guildId, code).toList.flatMap { respawn =>
      respawnService.schedulesForRespawn(guildId, respawn.id).map(view(_, respawn, guildId))
    }

  /** One spawn's window, expanded into blocks. The gathering is here; the
   *  deciding is in [[JdaRespawnActions.assembleCalendar]], which is pure. */
  def calendar(guildId: String, code: String,
               from: java.time.ZonedDateTime, to: java.time.ZonedDateTime): Option[CalendarView] =
    respawnService.resolve(guildId, code).map { respawn =>
      JdaRespawnActions.assembleCalendar(
        respawn,
        respawnService.status(guildId, respawn)._1,
        // Anchored at the window's own start rather than at `now`, so a grid
        // showing earlier in the week still draws what was booked then.
        respawnService.reservationsFor(guildId, respawn.id, from),
        respawnService.schedulesForRespawn(guildId, respawn.id),
        from, to)
    }

  /** A schedule as the calendar draws it.
   *
   *  The state comes from the slot the schedule has actually produced, not from
   *  the schedule itself — asked and confirmed are properties of an occurrence,
   *  and a rule that has not materialised one yet is simply booked. */
  private def view(schedule: com.tibiabot.domain.RespawnSchedule,
                   respawn: com.tibiabot.domain.Respawn, guildId: String): BookingView = {
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
      startsAt = schedule.nextStartAtOrAfter(java.time.ZonedDateTime.now()).getOrElse(schedule.anchorAt),
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
          respawnService.reassignClaim(guild, respawn.id, toUserId, displayName(toUserId)) match {
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
                       from: java.time.ZonedDateTime,
                       to: java.time.ZonedDateTime): CalendarView = {
    val hunting = active.toList.flatMap { claim =>
      for { start <- claim.startsAt; end <- claim.endsAt if start.isBefore(to) && end.isAfter(from) }
        yield CalendarSlot(None, claim.userId, label(claim.userName, claim.characterName),
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

    val written = booked.flatMap(slot => slot.scheduleId.map(_ -> slot.startsAt.toInstant)).toSet
    val predicted = schedules.flatMap { schedule =>
      schedule.occurrencesBetween(from, to)
        .filterNot(start => written.contains(schedule.id -> start.toInstant))
        .map(start => CalendarSlot(Some(schedule.id), schedule.userId,
          label(schedule.userName, schedule.characterName),
          start, schedule.endOf(start), RespawnBoardEntry.Booked,
          schedule.repeats, schedule.daysOfWeek, predicted = true))
    }

    CalendarView(respawn.code, respawn.name, respawn.creature,
      (hunting ++ booked ++ predicted).sortBy(_.startsAt.toInstant))
  }

  /** Who a block belongs to, as a person would say it: the character when there
   *  is one, since that is what the rest of the team recognises. */
  private def label(userName: String, characterName: String): String =
    if (characterName.nonEmpty) characterName else userName
}

