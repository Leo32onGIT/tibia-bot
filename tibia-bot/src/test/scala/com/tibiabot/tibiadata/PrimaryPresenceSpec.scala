package com.tibiabot.tibiadata

import com.tibiabot.persistence.RedisCache
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** The signal a consume-only secondary bets on.
 *
 *  Every test here is really about the same asymmetry: believing a dead primary
 *  is alive stops the secondary fetching anything at all, while believing a
 *  live one is dead costs some duplicate requests. So every uncertain case has
 *  to resolve to "absent". */
class PrimaryPresenceSpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val t0 = Instant.parse("2026-08-30T09:00:00Z")

  private class FakeCache(var failing: Boolean = false) extends RedisCache {
    val store = TrieMap.empty[String, String]
    var gets = 0
    def get(key: String): Future[Option[String]] = {
      gets += 1
      if (failing) Future.failed(new RuntimeException("redis is down"))
      else Future.successful(store.get(key))
    }
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = { store.put(key, value); Future.unit }
    def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] = Future.successful(false)
    def delete(key: String): Future[Unit] = { store.remove(key); Future.unit }
    def keysMatching(pattern: String): Future[List[String]] = Future.successful(Nil)
    def close(): Unit = ()
  }

  private def beating(cache: FakeCache): Unit = cache.store.put(PrimaryPresence.HeartbeatKey, "primary-bot-id")

  test("a cold start assumes no primary, so the secondary fetches for itself") {
    // Waiting for a bot we have never heard from would blind this secondary on
    // every restart.
    val cache = new FakeCache
    beating(cache)
    new PrimaryPresence(cache, 30.seconds, () => t0).isAlive shouldBe false
  }

  test("a heartbeat that is there reads as alive once seen") {
    val cache = new FakeCache
    beating(cache)
    val presence = new PrimaryPresence(cache, 30.seconds, () => t0)
    await(presence.refreshNow()) shouldBe true
    presence.isAlive shouldBe true
  }

  test("no heartbeat reads as absent") {
    val cache = new FakeCache
    val presence = new PrimaryPresence(cache, 30.seconds, () => t0)
    await(presence.refreshNow()) shouldBe false
    presence.isAlive shouldBe false
  }

  test("a Redis failure reads as absent rather than propagating") {
    // The caller is a cache miss on a character poll; an exception there would
    // fail the fetch instead of falling back to making one.
    val cache = new FakeCache
    beating(cache)
    cache.failing = true
    val presence = new PrimaryPresence(cache, 30.seconds, () => t0)
    await(presence.refreshNow()) shouldBe false
    presence.isAlive shouldBe false
  }

  test("a stale answer decays back to absent rather than being trusted forever") {
    // The heartbeat's whole job is to notice a primary that stopped. An answer
    // believed indefinitely would never notice.
    val cache = new FakeCache
    beating(cache)
    var clock = t0
    val presence = new PrimaryPresence(cache, 30.seconds, () => clock)
    await(presence.refreshNow()) shouldBe true
    presence.isAlive shouldBe true

    clock = t0.plusSeconds(31)
    cache.store.remove(PrimaryPresence.HeartbeatKey)
    presence.isAlive shouldBe false
  }

  test("a fresh answer is served without touching Redis again") {
    // The caller is one cache miss per character per poll; a Redis read each
    // time would put the whole fleet's misses on the wire.
    val cache = new FakeCache
    beating(cache)
    val presence = new PrimaryPresence(cache, 30.seconds, () => t0)
    await(presence.refreshNow())
    val after = cache.gets
    (1 to 50).foreach(_ => presence.isAlive shouldBe true)
    cache.gets shouldBe after
  }
}
