package com.tibiabot.web

import scala.concurrent.Future

/** Sends each write to whichever implementation can actually perform it.
 *
 *  A guild whose respawns this bot runs is done here and now; anything else is
 *  handed to the process that does run it. The route above never learns which
 *  happened — the difference is latency and nothing else, so the same page code
 *  serves every guild.
 *
 *  Reads always stay local. Every bot shares the guild's database, so a booking
 *  list is the same wherever it is read from, and relaying it would add a
 *  round trip to answer a question we can already answer.
 */
final class RoutingRespawnActions(
  local: RespawnActionPort,
  relay: RespawnActionPort,
  ownsGuild: String => Boolean
) extends RespawnActionPort {

  private def port(guildId: String): RespawnActionPort =
    if (ownsGuild(guildId)) local else relay

  def claim(guildId: String, userId: String, characterName: String,
            code: String, minutes: Option[Int]): Future[ActionResult] =
    port(guildId).claim(guildId, userId, characterName, code, minutes)

  def release(guildId: String, userId: String, code: Option[String]): Future[ActionResult] =
    port(guildId).release(guildId, userId, code)

  def extend(guildId: String, userId: String, extraMinutes: Int): Future[ActionResult] =
    port(guildId).extend(guildId, userId, extraMinutes)

  def book(guildId: String, userId: String, characterName: String, code: String,
           firstStart: java.time.ZonedDateTime, durationMinutes: Int, daysOfWeek: Int): Future[ActionResult] =
    port(guildId).book(guildId, userId, characterName, code, firstStart, durationMinutes, daysOfWeek)

  def cancelBooking(guildId: String, userId: String, scheduleId: Long): Future[ActionResult] =
    port(guildId).cancelBooking(guildId, userId, scheduleId)

  def forceLeave(guildId: String, actorId: String, code: String): Future[ActionResult] =
    port(guildId).forceLeave(guildId, actorId, code)

  def reassign(guildId: String, actorId: String, code: String, toUserId: String): Future[ActionResult] =
    port(guildId).reassign(guildId, actorId, code, toUserId)

  def grantStamina(guildId: String, actorId: String, targetUserId: String, minutes: Int): Future[ActionResult] =
    port(guildId).grantStamina(guildId, actorId, targetUserId, minutes)

  def addSpawn(guildId: String, actorId: String, code: String, region: String,
               name: String, creature: String): Future[ActionResult] =
    port(guildId).addSpawn(guildId, actorId, code, region, name, creature)

  def removeSpawn(guildId: String, actorId: String, code: String): Future[ActionResult] =
    port(guildId).removeSpawn(guildId, actorId, code)

  def bookings(guildId: String, userId: String): List[BookingView] = local.bookings(guildId, userId)
  def calendar(guildId: String, code: String,
               from: java.time.ZonedDateTime, to: java.time.ZonedDateTime): Option[CalendarView] =
    local.calendar(guildId, code, from, to)
  def bookingsOn(guildId: String, code: String): List[BookingView] = local.bookingsOn(guildId, code)
}
