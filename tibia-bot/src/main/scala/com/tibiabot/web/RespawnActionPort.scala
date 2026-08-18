package com.tibiabot.web

import scala.concurrent.Future

/** The writes the member dashboard can perform, as a seam over the respawn
 *  service.
 *
 *  A seam rather than the service itself for two reasons. The service's methods
 *  take a JDA `Guild`, which the route has no business resolving and no way to
 *  fake in a test. And every one of these has to be refused when *this* bot is
 *  not the one that should be acting on the guild — several bot identities can
 *  share a guild, and only the one that built its respawn forum runs its
 *  lifecycle (see `respawn.RespawnOwnership`). Putting that check behind this
 *  interface means a route cannot forget it.
 *
 *  [[Unavailable]] is that refusal, and it is deliberately distinct from a
 *  permission failure: the visitor did nothing wrong and retrying will not
 *  help, so the page says so rather than implying they lack access. It is also
 *  the seam a Redis relay to the owning bot slots into later, at which point
 *  the case stops being reachable for guilds another bot owns.
 */
trait RespawnActionPort {

  /* Writes answer with a Future because one of the two implementations does not
   * perform them at all: a guild whose respawns another bot runs has its writes
   * handed to that process through Redis, and the answer only arrives once it
   * has done the work. Blocking a request thread on that round trip would park
   * an akka dispatcher for as long as another process takes to notice — so the
   * waiting is expressed rather than hidden.
   *
   * The two reads below stay synchronous: they go to the per-guild database
   * every bot shares, so they never need relaying. */

  /** Take a spawn, or join its queue if somebody has it.
   *
   *  `code` is the spawn code as typed; the service resolves it, so an unknown
   *  one comes back as an ordinary refusal rather than an error. */
  def claim(guildId: String, userId: String, characterName: String,
            code: String, minutes: Option[Int]): Future[ActionResult]

  /** Give up a held spawn, or leave its queue. `code` empty releases whatever
   *  the caller holds, matching `/respawn release` with no argument. */
  def release(guildId: String, userId: String, code: Option[String]): Future[ActionResult]

  /** Add time to the claim the caller is currently holding. */
  def extend(guildId: String, userId: String, extraMinutes: Int): Future[ActionResult]

  /** Book a window on a spawn.
   *
   *  `firstStart` is an absolute instant, so the page can pick a time in the
   *  reader's own zone without either side having to agree a timezone.
   *  `daysOfWeek` is the weekday bitmask from `RespawnSchedule` — zero for a
   *  one-off. Those weekdays resolve in *server* time, which is why the page
   *  shows which server day a slot lands on before it is booked.
   *
   *  Booking over somebody else's slot is not a refusal: it asks them whether
   *  they are actually hunting it, which is why this can succeed with a
   *  question outstanding rather than a booking. */
  def book(guildId: String, userId: String, characterName: String, code: String,
           firstStart: java.time.ZonedDateTime, durationMinutes: Int, daysOfWeek: Int): Future[ActionResult]

  /** Cancel one of the caller's own bookings. */
  def cancelBooking(guildId: String, userId: String, scheduleId: Long): Future[ActionResult]

  /** The caller's standing bookings, for the calendar to draw.
   *
   *  A read rather than a write, but it belongs here: it is per-user and needs
   *  the same guild resolution everything else does. */
  def bookings(guildId: String, userId: String): List[BookingView]

  /** Every booking on a spawn, whoever made it — what the calendar draws behind
   *  the picker so somebody can see the evening before choosing a slot in it. */
  def bookingsOn(guildId: String, code: String): List[BookingView]

  /** One spawn's week, as concrete windows between two instants.
   *
   *  Deliberately not the rules themselves. A repeating booking is an anchor
   *  plus a weekday mask read in *server* time, and a page given those would
   *  have to re-derive which evenings they land on — in JavaScript, against a
   *  local clock, with daylight saving in the middle. That is the same
   *  calculation `RespawnSchedule` already does, and two implementations of it
   *  would disagree at exactly the edges that matter. So the expansion happens
   *  here and only blocks with a start and an end cross the wire. */
  def calendar(guildId: String, code: String,
               from: java.time.ZonedDateTime, to: java.time.ZonedDateTime): Option[CalendarView]

  // ---- Moderator tools. Refused for a plain member by the route, and each of
  // these acts on somebody else's claim, which is the whole reason they are
  // gated rather than merely hidden.

  /** Move whoever holds a spawn off it. */
  def forceLeave(guildId: String, actorId: String, code: String): Future[ActionResult]

  /** Hand a held spawn to somebody else. */
  def reassign(guildId: String, actorId: String, code: String, toUserId: String): Future[ActionResult]

  /** Give somebody back (or take away) stamina for the rest of the day. */
  def grantStamina(guildId: String, actorId: String, targetUserId: String, minutes: Int): Future[ActionResult]

  /** Give whoever holds a spawn more time on it.
   *
   *  An override: no stamina is charged and the guild's maximum claim length
   *  does not apply, both because a moderator repairing a lost hunt should not
   *  be refused by rules written for the person asking for one. */
  def extendHolder(guildId: String, actorId: String, code: String, extraMinutes: Int): Future[ActionResult]

  /** Add a spawn to the guild's catalogue that the bundled list does not carry.
   *
   *  The same four fields `respawns.json` has. Relayed like every other write
   *  even though most of the work is a database insert every bot could do: the
   *  board post in Discord has to be redrawn afterwards, and only the bot that
   *  owns the forum can touch it. */
  def addSpawn(guildId: String, actorId: String, code: String, region: String,
               name: String, creature: String): Future[ActionResult]

  /** Set or clear one spawn's own ceiling on claim length.
   *
   *  `minutes` empty clears the override, putting the spawn back on the guild's
   *  number. Relayed like the other catalogue writes because the spawn's forum
   *  post has to be redrawn afterwards, and only the bot that owns the forum can
   *  touch it. */
  def setSpawnMax(guildId: String, actorId: String, code: String,
                  minutes: Option[Int]): Future[ActionResult]

  /** Take one day off the calendar, leaving the booking rule behind it alone.
   *
   *  The day is named by the instant it starts on, which is what the grid
   *  already draws and the only handle a predicted slot has — it has no row and
   *  so no id. A repeating booking keeps repeating; only the evening goes.
   */
  def dropSlot(guildId: String, actorId: String, code: String,
               startsAt: java.time.ZonedDateTime): Future[ActionResult]

  /** Put one day of the calendar in somebody else's name.
   *
   *  Named the same way and for the same reason. What the new owner gets is a
   *  booking of their own for that evening, not a share of the rule it came
   *  from — see `RespawnService.reassignSlot`.
   */
  def reassignSlot(guildId: String, actorId: String, code: String,
                   startsAt: java.time.ZonedDateTime, toUserId: String): Future[ActionResult]

  /** Take a spawn the guild added back out of its catalogue.
   *
   *  Only ever one it added itself — a code from the bundled list is refused,
   *  since removing one would last until the next boot brought it back. */
  def removeSpawn(guildId: String, actorId: String, code: String): Future[ActionResult]
}

/** One booking as the calendar shows it. Times are absolute instants for the
 *  same reason everything else on this surface is: the page renders them in the
 *  reader's own zone. */
final case class BookingView(
  scheduleId: Long,
  code: String,
  spawnName: String,
  owner: String,
  ownerId: String,
  startsAt: java.time.ZonedDateTime,
  durationMinutes: Int,
  daysOfWeek: Int,
  repeats: Boolean,
  /** How the slot stands: `booked`, `asked` or `confirmed` — the same three the
   *  board uses, so both surfaces agree about what a booking is. */
  state: String
)

/** One spawn's calendar over a window: the spawn, and every window on it. */
final case class CalendarView(
  code: String,
  name: String,
  creature: String,
  slots: List[CalendarSlot]
)

/** One window on the grid — an actual block somebody could collide with, not a
 *  rule that might produce one.
 *
 *  `scheduleId` is present when the block came from a booking, and it is what
 *  the owner cancels; a live hunt has none. `predicted` marks a slot the rule
 *  will produce but which has not been booked into a row yet: it is real enough
 *  to draw and to plan around, but too far ahead for its owner to be asked
 *  about, which is the distinction `ClashVerdict.TooFarAhead` turns on. */
final case class CalendarSlot(
  scheduleId: Option[Long],
  ownerId: String,
  owner: String,
  /** The Discord account behind [[owner]], and what the server calls them.
   *
   *  Both travel because [[owner]] is a Tibia character most of the time, and a
   *  character name says nothing about who to go and talk to. Empty when there
   *  is none to give: a nickname was not recorded before today, and every block
   *  booked until now has none. */
  account: String,
  nickname: String,
  startsAt: java.time.ZonedDateTime,
  endsAt: java.time.ZonedDateTime,
  /** `claimed`, `booked`, `asked` or `confirmed` — the board's own words. */
  state: String,
  repeats: Boolean,
  daysOfWeek: Int,
  predicted: Boolean
)

object RespawnActionPort {
  /** What every action answers when this bot is not the one that should act on
   *  the guild. */
  val Unavailable: ActionResult = ActionResult(ok = false,
    "This server's respawns are run by another Violent Bot instance, so this has to be done in Discord for now.")
}
