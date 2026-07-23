package com.tibiabot.persistence

import com.tibiabot.tracking.OnlinePlayer
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Online-duration snapshot persistence: save->load round-trips (dropping
 *  `time`, since restore re-stamps it), and any absent/corrupt/disabled cache
 *  degrades to an empty load (best-effort). */
class OnlineDurationPersistenceSpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private class FakeCache extends RedisCache {
    val store = TrieMap.empty[String, String]
    def get(key: String): Future[Option[String]] = Future.successful(store.get(key))
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = { store.put(key, value); Future.unit }
    def close(): Unit = ()
  }

  private val t0 = ZonedDateTime.parse("2026-05-31T10:00:00Z")

  test("save then load round-trips the snapshot fields (not time)") {
    val cache = new FakeCache
    val p = new OnlineDurationPersistence(cache, "Antica")
    val players = List(
      OnlinePlayer("Violent Beams", 300, "Elite Knight", "Some Guild", t0, duration = 1200L, flag = ":arrow_up:"),
      OnlinePlayer("Bubble", 150, "Master Sorcerer", "", t0, duration = 60L)
    )
    await(p.save(players))

    val loaded = await(p.load())
    loaded.keySet shouldBe Set("Violent Beams", "Bubble")
    loaded("Violent Beams") shouldBe OnlinePlayerSnapshot(300, "Elite Knight", "Some Guild", 1200L, ":arrow_up:")
    loaded("Bubble") shouldBe OnlinePlayerSnapshot(150, "Master Sorcerer", "", 60L, "")
  }

  test("different worlds use independent keys") {
    val cache = new FakeCache
    await(new OnlineDurationPersistence(cache, "Antica").save(List(OnlinePlayer("A", 1, "None", "", t0))))
    await(new OnlineDurationPersistence(cache, "Secura").save(List(OnlinePlayer("B", 2, "None", "", t0))))

    await(new OnlineDurationPersistence(cache, "Antica").load()).keySet shouldBe Set("A")
    await(new OnlineDurationPersistence(cache, "Secura").load()).keySet shouldBe Set("B")
  }

  test("absent snapshot loads as empty") {
    await(new OnlineDurationPersistence(new FakeCache, "Antica").load()) shouldBe empty
  }

  test("corrupt snapshot loads as empty (best-effort)") {
    val cache = new FakeCache
    cache.store.put("tibia:online-snapshot:antica", "}{garbage")
    await(new OnlineDurationPersistence(cache, "Antica").load()) shouldBe empty
  }

  test("disabled Redis (Noop): save is a no-op and load is empty") {
    val p = new OnlineDurationPersistence(NoopRedisCache, "Antica")
    await(p.save(List(OnlinePlayer("X", 1, "None", "", t0))))
    await(p.load()) shouldBe empty
  }
}
