package com.tibiabot.respawn

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The debounce that decides when a free spawn's post goes back to sleep.
 *
 *  What is worth pinning here is the arithmetic a real burst of clicks
 *  produces: a second press pushing the close out rather than adding a second
 *  one, a post being handed to the sweep exactly once, and one guild's posts
 *  never turning up in another's batch.
 *
 *  The delay is passed explicitly throughout. `Config` reads a required `TOKEN`
 *  from the environment and cannot be loaded from a spec, and a Scala default
 *  argument is evaluated at the call site — so supplying one keeps `Config` out
 *  of this file rather than merely unused.
 */
class RespawnSleepSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach {

  private val start = Instant.parse("2026-08-18T20:00:00Z")
  private val guild = "111"
  private val delay = 300L // the configured 5m, fixed here so the sums are readable

  private def touch(threadId: String, at: Instant, guildId: String = guild): Unit =
    RespawnSleep.touch(guildId, threadId, at, delay)

  override def beforeEach(): Unit = RespawnSleep.clear()
  override def afterEach(): Unit = RespawnSleep.clear()

  test("a post is not due until the delay has passed") {
    touch("t1", start)
    RespawnSleep.due(guild, start.plusSeconds(delay - 1)) shouldBe empty
    RespawnSleep.due(guild, start.plusSeconds(delay)).map(_.threadId) shouldBe List("t1")
  }

  test("a second press pushes the close out rather than queueing another") {
    touch("t1", start)
    touch("t1", start.plusSeconds(60))
    RespawnSleep.size shouldBe 1
    // Due on the first press's clock, but the second press moved it — which is
    // the whole point: somebody working through a booking form does not get the
    // post shut under them partway.
    RespawnSleep.due(guild, start.plusSeconds(delay)) shouldBe empty
    RespawnSleep.due(guild, start.plusSeconds(delay + 60)).map(_.threadId) shouldBe List("t1")
  }

  test("draining hands each post over exactly once") {
    touch("t1", start)
    val now = start.plusSeconds(delay)
    RespawnSleep.due(guild, now).map(_.threadId) shouldBe List("t1")
    RespawnSleep.due(guild, now) shouldBe empty
    RespawnSleep.size shouldBe 0
  }

  test("a press at the moment a post comes due keeps it open") {
    touch("t1", start)
    val now = start.plusSeconds(delay)
    touch("t1", now)
    RespawnSleep.due(guild, now) shouldBe empty
    RespawnSleep.isPending("t1") shouldBe true
  }

  test("only the posts that are due are handed over") {
    touch("quiet", start)
    touch("busy", start.plusSeconds(delay))
    RespawnSleep.due(guild, start.plusSeconds(delay)).map(_.threadId) shouldBe List("quiet")
    RespawnSleep.isPending("busy") shouldBe true
  }

  test("one guild's due posts never appear in another's batch") {
    touch("t1", start)
    touch("t2", start, guildId = "222")
    val now = start.plusSeconds(delay)
    RespawnSleep.due("222", now).map(_.threadId) shouldBe List("t2")
    RespawnSleep.due(guild, now).map(_.threadId) shouldBe List("t1")
  }

  test("forget drops a post without waiting for it to come due") {
    touch("t1", start)
    RespawnSleep.forget("t1")
    RespawnSleep.isPending("t1") shouldBe false
    RespawnSleep.due(guild, start.plusSeconds(delay)) shouldBe empty
  }

  test("entries nothing drains are evicted, and pending ones are not") {
    touch("stale", start)
    touch("fresh", start.plusSeconds(3600))
    // An hour past "stale"'s due time, which is a whole hour of sweeps that
    // never came for it — so nothing is going to. "fresh" is not due yet at all.
    RespawnSleep.evictStale(start.plusSeconds(3600 + delay + 1)) shouldBe 1
    RespawnSleep.isPending("stale") shouldBe false
    RespawnSleep.isPending("fresh") shouldBe true
  }
}
