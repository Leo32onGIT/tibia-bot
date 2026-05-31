package com.tibiabot.persistence

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** R1 character-cache snapshot persistence: save->load round-trips, and any
 *  absent / corrupt / disabled cache degrades to an empty load (best-effort). */
class CharacterCachePersistenceSpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private class FakeCache extends RedisCache {
    val store = TrieMap.empty[String, String]
    def get(key: String): Future[Option[String]] = Future.successful(store.get(key))
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = { store.put(key, value); Future.unit }
    def close(): Unit = ()
  }

  private val t1 = ZonedDateTime.parse("2026-05-31T10:00:00Z")
  private val t2 = ZonedDateTime.parse("2026-05-31T11:30:00Z")

  test("save then load round-trips the snapshot") {
    val cache = new FakeCache
    val p = new CharacterCachePersistence(cache)
    await(p.save(Map("Violent Beams" -> t1, "Bubble" -> t2)))
    await(p.load()) shouldBe Map("Violent Beams" -> t1, "Bubble" -> t2)
  }

  test("absent snapshot loads as empty") {
    await(new CharacterCachePersistence(new FakeCache).load()) shouldBe empty
  }

  test("corrupt snapshot loads as empty (best-effort)") {
    val cache = new FakeCache
    cache.store.put("tibia:chardate-snapshot", "}{garbage")
    await(new CharacterCachePersistence(cache).load()) shouldBe empty
  }

  test("disabled Redis (Noop): save is a no-op and load is empty") {
    val p = new CharacterCachePersistence(NoopRedisCache)
    await(p.save(Map("X" -> t1)))
    await(p.load()) shouldBe empty
  }
}
