package com.tibiabot.web

import akka.actor.Scheduler
import com.tibiabot.persistence.RedisCache
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Performs a write by asking the bot that actually runs the guild's respawns.
 *
 *  Writes the command to Redis and waits for that process to answer. Nothing
 *  blocks: the wait is a scheduled re-read, so no thread is held for however
 *  long the other side takes to notice.
 *
 *  Deliberately says nothing about permissions. The issuing process has already
 *  decided who may do what — it resolved the visitor's tier against the live
 *  guild — and re-deciding it on the far side would need the executing bot to
 *  resolve a member it may have no reason to know about. What crosses is
 *  "perform this, as this person", and the boundary is trusted because both
 *  ends are this bot's own processes on a private Redis.
 */
final class RelayedRespawnActions(
  cache: RedisCache,
  scheduler: Scheduler,
  timeout: FiniteDuration = RelayedRespawnActions.DefaultTimeout,
  pollEvery: FiniteDuration = RelayedRespawnActions.DefaultPoll,
  newId: () => String = () => java.util.UUID.randomUUID().toString
)(implicit ec: ExecutionContext) extends RespawnActionPort with StrictLogging {

  private def send(guildId: String, actorId: String, action: String,
                   params: Map[String, String]): Future[ActionResult] = {
    val command = RespawnCommand(newId(), guildId, action, actorId,
      // Blank values are dropped rather than sent: absent and empty mean
      // different things on the far side (no character given vs a character
      // called nothing), and only one of them is ever meant.
      params.filter { case (_, value) => value != null && value.trim.nonEmpty })
    // The request outlives the wait, so a consumer that picks it up a moment
    // after we give up still finds a complete command rather than half of one.
    cache.setEx(RespawnCommand.requestKey(guildId, command.id), command.toJson, timeout * 2)
      .flatMap(_ => awaitReply(command.id, deadline = System.nanoTime() + timeout.toNanos))
      .recover {
        case NonFatal(e) =>
          logger.warn(s"Could not hand '$action' to the bot running guild '$guildId': ${e.getMessage}")
          RelayedRespawnActions.Undeliverable
      }
  }

  private def awaitReply(id: String, deadline: Long): Future[ActionResult] =
    cache.get(RespawnCommand.replyKey(id)).flatMap {
      case Some(raw) =>
        Future.successful(RespawnCommand.resultFromJson(raw).getOrElse {
          logger.warn(s"Reply to command '$id' could not be read")
          RelayedRespawnActions.Unclear
        })
      case None if System.nanoTime() < deadline =>
        akka.pattern.after(pollEvery, scheduler)(awaitReply(id, deadline))
      case None =>
        // Timing out says nothing about whether it happened: the other process
        // may be slow rather than absent, and the command is still sitting there
        // to be picked up. So the wording must not claim nothing changed —
        // telling somebody their claim failed when it is about to succeed is
        // worse than telling them to look.
        logger.warn(s"No answer to command '$id' within $timeout")
        Future.successful(RelayedRespawnActions.NoAnswer)
    }

  def claim(guildId: String, userId: String, characterName: String,
            code: String, minutes: Option[Int]): Future[ActionResult] =
    send(guildId, userId, RespawnCommand.Claim,
      Map("code" -> code, "character" -> characterName) ++ minutes.map(m => "minutes" -> m.toString))

  def release(guildId: String, userId: String, code: Option[String]): Future[ActionResult] =
    send(guildId, userId, RespawnCommand.Release, code.map("code" -> _).toMap)

  def extend(guildId: String, userId: String, extraMinutes: Int): Future[ActionResult] =
    send(guildId, userId, RespawnCommand.Extend, Map("minutes" -> extraMinutes.toString))

  def book(guildId: String, userId: String, characterName: String, code: String,
           firstStart: java.time.ZonedDateTime, durationMinutes: Int, daysOfWeek: Int): Future[ActionResult] =
    send(guildId, userId, RespawnCommand.Book, Map(
      "code" -> code,
      "character" -> characterName,
      // An instant, so the two processes need not agree a timezone.
      "startsAt" -> firstStart.toInstant.toString,
      "minutes" -> durationMinutes.toString,
      "days" -> daysOfWeek.toString))

  def cancelBooking(guildId: String, userId: String, scheduleId: Long): Future[ActionResult] =
    send(guildId, userId, RespawnCommand.CancelBooking, Map("scheduleId" -> scheduleId.toString))

  def forceLeave(guildId: String, actorId: String, code: String): Future[ActionResult] =
    send(guildId, actorId, RespawnCommand.ForceLeave, Map("code" -> code))

  def reassign(guildId: String, actorId: String, code: String, toUserId: String): Future[ActionResult] =
    send(guildId, actorId, RespawnCommand.Reassign, Map("code" -> code, "toUserId" -> toUserId))

  def grantStamina(guildId: String, actorId: String, targetUserId: String, minutes: Int): Future[ActionResult] =
    send(guildId, actorId, RespawnCommand.GrantStamina,
      Map("userId" -> targetUserId, "minutes" -> minutes.toString))

  /** Reads never relay — every bot shares the guild's database, so this
   *  implementation is only ever used for writes and these are unreachable. */
  def bookings(guildId: String, userId: String): List[BookingView] = Nil
  def bookingsOn(guildId: String, code: String): List[BookingView] = Nil
  def calendar(guildId: String, code: String,
               from: java.time.ZonedDateTime, to: java.time.ZonedDateTime): Option[CalendarView] = None
}

object RelayedRespawnActions {
  /** Long enough for a consumer polling every second to notice and finish a
   *  claim; short enough that a browser is not left hanging on a bot that is
   *  down. */
  val DefaultTimeout: FiniteDuration = 8.seconds
  val DefaultPoll: FiniteDuration = 250.millis

  /** Every one of these is deliberately vague about whether the write happened,
   *  because we genuinely do not know. Claiming otherwise is the failure that
   *  costs somebody their spawn. */
  val NoAnswer: ActionResult = ActionResult(ok = false,
    "The bot running this server did not answer in time. It may still go through — check the board in a moment.")

  val Undeliverable: ActionResult = ActionResult(ok = false,
    "Could not reach the bot running this server. Nothing has been done; try again shortly.")

  val Unclear: ActionResult = ActionResult(ok = false,
    "The answer came back unreadable. Check the board before trying again.")
}
