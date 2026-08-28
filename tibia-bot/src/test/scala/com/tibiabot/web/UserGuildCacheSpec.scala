package com.tibiabot.web

import com.tibiabot.persistence.RedisCache
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class UserGuildCacheSpec extends AnyWordSpec with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private class TestClock(var millis: Long = 1_700_000_000_000L) {
    def now: () => Long = () => millis
    def advance(d: FiniteDuration): Unit = millis += d.toMillis
  }

  /** Enough Redis for this: keys and values. TTLs are ignored, which is why the
   *  deadline is carried in the value — so what expires an entry is the clock
   *  above rather than something no test could advance. */
  private class FakeRedis extends RedisCache {
    val store = TrieMap.empty[String, String]
    def get(key: String): Future[Option[String]] = Future.successful(store.get(key))
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] =
      Future.successful { store.put(key, value); () }
    def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] =
      Future.successful(store.putIfAbsent(key, value).isEmpty)
    def delete(key: String): Future[Unit] = Future.successful { store.remove(key); () }
    def keysMatching(pattern: String): Future[List[String]] = Future.successful(Nil)
    def close(): Unit = ()
  }

  /** A store that is there and cannot answer — the state a dead Redis leaves
   *  this in, which must cost a sign-in rather than a failed request. */
  private final class BrokenRedis extends FakeRedis {
    override def get(key: String): Future[Option[String]] =
      Future.failed(new RuntimeException("no redis"))
  }

  private def cache(clock: TestClock, ttl: FiniteDuration = 7.days) =
    new UserGuildCache(ttl, clock.now)

  private def cacheOn(redis: RedisCache, clock: TestClock, ttl: FiniteDuration = 7.days) =
    new UserGuildCache(ttl, clock.now, redis)

  private def warm(c: UserGuildCache, userId: String): Unit =
    Await.result(c.warm(userId), 3.seconds)

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

  /** The restart case. A process that has never seen somebody sign in is what
   *  every process is on its first request after a deploy, and the session
   *  cookie in their browser outlives that by a week. */
  "UserGuildCache, backed by a store" should {

    "hand a restarted process the entry the old one had" in {
      val clock = new TestClock
      val redis = new FakeRedis
      cacheOn(redis, clock).put("user-1", Set("g1", "g2"))

      val restarted = cacheOn(redis, clock)
      restarted.get("user-1") shouldBe None
      warm(restarted, "user-1")
      restarted.get("user-1") shouldBe Some(Set("g1", "g2"))
    }

    "keep the deadline the entry was given rather than starting it over" in {
      // Otherwise every restart renews a stale list, and somebody who left a
      // guild months ago is still offered it for as long as the bot keeps
      // deploying.
      val clock = new TestClock
      val redis = new FakeRedis
      cacheOn(redis, clock, ttl = 1.hour).put("user-1", Set("g1"))

      clock.advance(59.minutes)
      val restarted = cacheOn(redis, clock, ttl = 1.hour)
      warm(restarted, "user-1")
      restarted.get("user-1") shouldBe Some(Set("g1"))

      clock.advance(2.minutes)
      restarted.get("user-1") shouldBe None
    }

    "not warm an entry that has already expired" in {
      val clock = new TestClock
      val redis = new FakeRedis
      cacheOn(redis, clock, ttl = 1.hour).put("user-1", Set("g1"))
      clock.advance(61.minutes)

      val restarted = cacheOn(redis, clock, ttl = 1.hour)
      warm(restarted, "user-1")
      restarted.get("user-1") shouldBe None
    }

    "carry somebody in no guilds across as themselves, not as a stranger" in {
      // Some(empty) is what stops the dashboard bouncing them through login
      // forever, so it is the one value that must not come back as None.
      val clock = new TestClock
      val redis = new FakeRedis
      cacheOn(redis, clock).put("user-1", Set.empty)

      val restarted = cacheOn(redis, clock)
      warm(restarted, "user-1")
      restarted.get("user-1") shouldBe Some(Set.empty[String])
    }

    "leave memory alone when the store has never heard of them" in {
      val c = cacheOn(new FakeRedis, new TestClock)
      warm(c, "nobody")
      c.get("nobody") shouldBe None
      c.size shouldBe 0
    }

    "not ask the store about somebody it already knows" in {
      val clock = new TestClock
      val redis = new FakeRedis
      val c = cacheOn(redis, clock)
      c.put("user-1", Set("g1"))
      // Emptied behind its back: a warm that reads it would find nothing and a
      // warm that does not read it cannot.
      redis.store.clear()
      warm(c, "user-1")
      c.get("user-1") shouldBe Some(Set("g1"))
    }

    "treat a store that cannot answer as a miss rather than an error" in {
      val c = cacheOn(new BrokenRedis, new TestClock)
      noException should be thrownBy warm(c, "user-1")
      c.get("user-1") shouldBe None
    }

    "treat a value it cannot read as a miss too" in {
      val clock = new TestClock
      val redis = new FakeRedis
      redis.store.put("tibia:user-guilds:user-1", "not-an-entry")
      val c = cacheOn(redis, clock)
      warm(c, "user-1")
      c.get("user-1") shouldBe None
    }

    "forget somebody in the store as well as in memory on sign-out" in {
      // Or the next request would warm them straight back in, and signing out
      // would have done nothing at all.
      val clock = new TestClock
      val redis = new FakeRedis
      val c = cacheOn(redis, clock)
      c.put("user-1", Set("g1"))
      c.invalidate("user-1")

      val restarted = cacheOn(redis, clock)
      warm(restarted, "user-1")
      restarted.get("user-1") shouldBe None
    }
  }
}
