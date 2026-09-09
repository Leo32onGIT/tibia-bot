package com.tibiabot.respawn

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** A claim must never be able to outlive the window its spawn's bookings are
 *  written in.
 *
 *  A claim is cut short against the next *booked* slot, and a repeating rule's
 *  slots are only written once they come within `schedule-look-ahead-minutes`. So
 *  a claim long enough to span that window can be started while tonight's slot
 *  does not exist yet, be capped against nothing, and still be running when the
 *  slot is written and comes due. The sweep then reads that as a collision,
 *  cancels the booking and queues its owner — the same ending as the two holes
 *  already closed, reached by arithmetic rather than by a missing check.
 *
 *  It was latent when found: the code permitted a day-long spawn ceiling against a
 *  twelve-hour look-ahead, and only nobody having set one kept it shut. Which is
 *  exactly the kind of assumption that holds until it doesn't, so it is checked
 *  here rather than remembered.
 *
 *  Hermetic, like CacheConfigSpec: resolved with `allowUnresolved` so it needs no
 *  TOKEN/POSTGRES_* environment.
 */
class ScheduleHorizonSpec extends AnyFunSuite with Matchers {

  private val lookAheadMinutes: Int =
    ConfigFactory.parseResources("discord.conf")
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
      .getConfig("discord-config")
      .getConfig("respawn")
      .getInt("schedule-look-ahead-minutes")

  test("the configured look-ahead is the twelve hours the ceiling is measured against") {
    lookAheadMinutes shouldBe 720
  }

  /** The invariant. Fails if either number moves the wrong way — a longer settable
   *  ceiling, or a shorter look-ahead — which are the two ways to reopen this. */
  test("no spawn can be given a ceiling that outlives the look-ahead") {
    RespawnService.spawnCeilingLimit(lookAheadMinutes) should be <= lookAheadMinutes
  }

  test("the look-ahead is what binds today, not the flat ceiling") {
    RespawnService.MaxSpawnCeilingMinutes should be > lookAheadMinutes
    RespawnService.spawnCeilingLimit(lookAheadMinutes) shouldBe lookAheadMinutes
  }

  /** The flat ceiling still binds where it is the smaller of the two, so raising
   *  the look-ahead cannot quietly let a spawn be set past a day. */
  test("the flat ceiling still binds under a longer look-ahead") {
    RespawnService.spawnCeilingLimit(2 * 24 * 60) shouldBe RespawnService.MaxSpawnCeilingMinutes
  }

  test("a look-ahead shorter than any sane hunt still bounds the ceiling") {
    RespawnService.spawnCeilingLimit(30) shouldBe 30
  }
}
