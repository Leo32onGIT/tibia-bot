package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class UserGuildCacheSpec extends AnyWordSpec with Matchers {

  private class TestClock(var millis: Long = 1_700_000_000_000L) {
    def now: () => Long = () => millis
    def advance(d: FiniteDuration): Unit = millis += d.toMillis
  }

  private def cache(clock: TestClock, ttl: FiniteDuration = 7.days) =
    new UserGuildCache(ttl, clock.now)

  "UserGuildCache" should {

    "return nothing for somebody who has never signed in" in {
      cache(new TestClock).get("user-1") shouldBe None
    }

    "hand back what was stored" in {
      val c = cache(new TestClock)
      c.put("user-1", Set("g1", "g2"))
      c.get("user-1") shouldBe Some(Set("g1", "g2"))
    }

    "keep visitors apart" in {
      val c = cache(new TestClock)
      c.put("user-1", Set("g1"))
      c.put("user-2", Set("g2"))
      c.get("user-1") shouldBe Some(Set("g1"))
      c.get("user-2") shouldBe Some(Set("g2"))
    }

    "replace an earlier login rather than merging with it" in {
      // Somebody who has left a guild since last signing in must not keep it,
      // or the picker would offer a server they can no longer reach.
      val c = cache(new TestClock)
      c.put("user-1", Set("g1", "g2"))
      c.put("user-1", Set("g1"))
      c.get("user-1") shouldBe Some(Set("g1"))
    }

    "hold an entry right up to its TTL" in {
      val clock = new TestClock
      val c = cache(clock, ttl = 1.hour)
      c.put("user-1", Set("g1"))
      clock.advance(59.minutes)
      c.get("user-1") shouldBe Some(Set("g1"))
    }

    // A miss is not a failure — the caller sends them back through login, which
    // is transparent while their Discord session is live.
    "report a miss once the TTL has passed" in {
      val clock = new TestClock
      val c = cache(clock, ttl = 1.hour)
      c.put("user-1", Set("g1"))
      clock.advance(61.minutes)
      c.get("user-1") shouldBe None
    }

    "forget somebody on sign-out" in {
      val c = cache(new TestClock)
      c.put("user-1", Set("g1"))
      c.invalidate("user-1")
      c.get("user-1") shouldBe None
    }

    "tolerate signing out somebody who was never in" in {
      noException should be thrownBy cache(new TestClock).invalidate("nobody")
    }

    "distinguish a visitor in no guilds from one who never signed in" in {
      // Empty is a real answer — it means "signed in, but shares no server with
      // the bot", which the dashboard has its own words for.
      val c = cache(new TestClock)
      c.put("user-1", Set.empty)
      c.get("user-1") shouldBe Some(Set.empty[String])
      c.get("user-2") shouldBe None
    }

    "drop expired entries on prune and keep live ones" in {
      // Nothing evicts on its own — the map is only written on login — so a
      // long-running process would otherwise keep a row per person ever seen.
      val clock = new TestClock
      val c = cache(clock, ttl = 1.hour)
      c.put("old", Set("g1"))
      clock.advance(90.minutes)
      c.put("fresh", Set("g2"))
      c.size shouldBe 2
      c.prune()
      c.size shouldBe 1
      c.get("fresh") shouldBe Some(Set("g2"))
      c.get("old") shouldBe None
    }
  }
}
