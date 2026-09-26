package com.tibiabot.observer

import com.tibiabot.domain.{MiniWorldChange, RaidAnnouncement}
import com.tibiabot.persistence.RedisCache
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/** The primary fetches and publishes; a secondary reads that copy and never calls
 *  the API; a lone bot fetches for itself. */
class ObserverFeedSpec extends AnyFunSuite with Matchers {

  private final class FakeRedis extends RedisCache {
    val store = TrieMap.empty[String, String]
    def get(key: String): Future[Option[String]] = Future.successful(store.get(key))
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] =
      Future.successful { store.put(key, value); () }
    def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] =
      Future.successful(store.putIfAbsent(key, value).isEmpty)
    def delete(key: String): Future[Unit] = Future.successful { store.remove(key); () }
    def keysMatching(pattern: String): Future[List[String]] = Future.successful(Nil)
    val listeners = TrieMap.empty[String, List[String => Unit]]
    override def publish(channel: String, message: String): Future[Long] = {
      val reached = listeners.getOrElse(channel, Nil)
      reached.foreach(_(message))
      Future.successful(reached.size.toLong)
    }
    override def subscribe(channel: String)(onMessage: String => Unit): Future[Unit] =
      Future.successful { listeners.put(channel, onMessage :: listeners.getOrElse(channel, Nil)); () }
    def close(): Unit = ()
  }

  test("the primary announces a changed raids copy once, and a secondary reacts to it") {
    val redis = new FakeRedis
    var pool = Map("Antica" -> List(raid))
    val primary = new ObserverFeed(ObserverFeed.Publisher, () => Some(antica), () => pool, redis)
    val secondary = new ObserverFeed(ObserverFeed.Consumer, never, never, redis)
    var seen = List.empty[Map[String, List[RaidAnnouncement]]]
    secondary.onRaidsChanged(() => seen = seen :+ secondary.raidsByWorld())

    primary.raidsByWorld()
    primary.raidsByWorld()            // unchanged: no second announcement
    pool = Map("Antica" -> List(raid, raid.copy(category = "subareaRevealed")))
    primary.raidsByWorld()
    seen shouldBe List(Map("Antica" -> List(raid)), pool)
  }

  private val antica = Map("antica" -> List(MiniWorldChange("Antica", "Fury Gate", "Near Venore.")))
  private val raid = RaidAnnouncement("r1", "Antica", "Carlin", Some("Ghostlands"), "areaRevealed",
    Some(Instant.parse("2026-09-24T12:00:00Z")), 43)

  private val never: () => Nothing = () => fail("a secondary never calls the API")

  private class Clock { var at: Instant = Instant.parse("2026-09-24T10:00:00Z"); def now(): Instant = at }

  test("the primary publishes what it fetched, and a secondary reads that copy") {
    val redis = new FakeRedis
    val primary = new ObserverFeed(ObserverFeed.Publisher, () => Some(antica), () => Map("Antica" -> List(raid)), redis)
    primary.refreshMwc() shouldBe Some(antica)
    primary.raidsByWorld() shouldBe Map("Antica" -> List(raid))

    val secondary = new ObserverFeed(ObserverFeed.Consumer, never, never, redis)
    secondary.refreshMwc() shouldBe Some(antica)
    secondary.raidsByWorld() shouldBe Map("Antica" -> List(raid))
    secondary.mwcForWorld("ANTICA") shouldBe antica("antica")
  }

  test("a secondary with no copy to read reports the feed missing, never empty") {
    val secondary = new ObserverFeed(ObserverFeed.Consumer, never, never, new FakeRedis)
    secondary.refreshMwc() shouldBe None
    secondary.raidsByWorld() shouldBe empty
  }

  test("a lone bot fetches for itself and publishes nothing") {
    val redis = new FakeRedis
    val lone = new ObserverFeed(ObserverFeed.Standalone, () => Some(antica), () => Map("Antica" -> List(raid)), redis)
    lone.refreshMwc() shouldBe Some(antica)
    lone.raidsByWorld() should not be empty
    redis.store shouldBe empty
  }

  test("a failed fetch publishes nothing, so the last good copy stands") {
    val redis = new FakeRedis
    var answer: Option[Map[String, List[MiniWorldChange]]] = Some(antica)
    val primary = new ObserverFeed(ObserverFeed.Publisher, () => answer, () => Map.empty, redis)
    primary.refreshMwc()
    answer = None
    primary.refreshMwc() shouldBe None
    new ObserverFeed(ObserverFeed.Consumer, never, never, redis).refreshMwc() shouldBe Some(antica)
  }

  test("through the server-save window, changes are reused for two minutes before asking again") {
    val clock = new Clock
    clock.at = Instant.parse("2026-09-24T08:05:00Z") // 10:05 in Berlin
    var fetches = 0
    val feed = new ObserverFeed(ObserverFeed.Standalone, () => { fetches += 1; Some(antica) }, () => Map.empty,
      new FakeRedis, () => clock.now())
    feed.mwcForWorld("Antica")
    clock.at = clock.at.plus(Duration.ofSeconds(90))
    feed.mwcForWorld("Antica")
    fetches shouldBe 1
    clock.at = clock.at.plus(Duration.ofSeconds(60))
    feed.mwcForWorld("Antica")
    fetches shouldBe 2
  }

  test("once the day's changes have settled, they're reused until the next server save") {
    val clock = new Clock // 12:00 in Berlin
    var fetches = 0
    val feed = new ObserverFeed(ObserverFeed.Standalone, () => { fetches += 1; Some(antica) }, () => Map.empty,
      new FakeRedis, () => clock.now())
    feed.mwcForWorld("Antica") should not be empty
    clock.at = clock.at.plus(Duration.ofHours(11)) // 23:00
    feed.mwcForWorld("Antica") should not be empty
    fetches shouldBe 1
    clock.at = Instant.parse("2026-09-25T08:01:00Z") // 10:01 the next day
    feed.mwcForWorld("Antica")
    fetches shouldBe 2
  }

  test("a failed refresh falls back to the last good changes until the next server save") {
    val clock = new Clock // 12:00 in Berlin
    var answer: Option[Map[String, List[MiniWorldChange]]] = Some(antica)
    val feed = new ObserverFeed(ObserverFeed.Standalone, () => answer, () => Map.empty, new FakeRedis, () => clock.now())
    feed.mwcForWorld("Antica") should not be empty
    answer = None
    clock.at = clock.at.plus(Duration.ofHours(11)) // 23:00
    feed.mwcForWorld("Antica") should not be empty
    clock.at = Instant.parse("2026-09-25T08:01:00Z") // 10:01 the next day
    feed.mwcForWorld("Antica") shouldBe empty
  }

  test("a secondary goes on with the day's copy however old, and never uses yesterday's") {
    val clock = new Clock
    val redis = new FakeRedis
    new ObserverFeed(ObserverFeed.Publisher, () => Some(antica), () => Map.empty, redis, () => clock.now()).refreshMwc()
    val secondary = new ObserverFeed(ObserverFeed.Consumer, never, never, redis, () => clock.now())
    clock.at = clock.at.plus(Duration.ofHours(11))
    secondary.refreshMwc() shouldBe Some(antica)
    clock.at = Instant.parse("2026-09-25T08:01:00Z")
    secondary.refreshMwc() shouldBe None
    secondary.mwcForWorld("Antica") shouldBe empty
  }

  test("a copy keeps when it was fetched, and one without a stamp is not trusted") {
    val copy = ObserverFeed.MwcCopy(Instant.parse("2026-09-24T08:05:00Z"), antica,
      Map("antica" -> Instant.parse("2026-09-23T08:03:00Z")))
    FeedJson.parseMwc(FeedJson.mwc(copy)) shouldBe Some(copy)
    // One from before the per-world stamps counts its worlds from when it was fetched.
    FeedJson.parseMwc("""{"fetchedAt":"2026-09-24T08:05:00Z","worlds":""" +
      """{"antica":[{"world":"Antica","title":"Fury Gate","body":"Near Venore."}]}}""")
      .map(_.seenSince("antica")) shouldBe Some(Instant.parse("2026-09-24T08:05:00Z"))
    FeedJson.parseMwc("""{"antica":[{"world":"Antica","title":"Fury Gate","body":"Near Venore."}]}""") shouldBe None
  }

  private val anticaToday = Map("antica" -> List(MiniWorldChange("Antica", "Warpath", "Orcs march on Thais.")))

  /** 09:50 and a quarter past server save in Berlin, on the day `Clock` starts. */
  private val beforeSave = Instant.parse("2026-09-24T07:50:00Z")
  private val afterSave = Instant.parse("2026-09-24T08:15:00Z")

  test("after server save a world's changes are held back until they move on from yesterday's") {
    val clock = new Clock
    clock.at = beforeSave
    var answer: Option[Map[String, List[MiniWorldChange]]] = Some(antica)
    val feed = new ObserverFeed(ObserverFeed.Standalone, () => answer, () => Map.empty, new FakeRedis, () => clock.now())
    feed.refreshMwc() shouldBe Some(antica)
    clock.at = afterSave // the feed still reports yesterday's
    feed.refreshMwc() shouldBe Some(Map.empty)
    feed.mwcForWorld("Antica") shouldBe empty
    feed.takeAnsweredWithout() shouldBe false
    answer = Some(anticaToday)
    clock.at = clock.at.plus(Duration.ofMinutes(4))
    feed.refreshMwc() shouldBe Some(anticaToday)
    feed.mwcForWorld("Antica") shouldBe anticaToday("antica")
  }

  test("a world that keeps yesterday's changes gets them once the server-save window is over") {
    val clock = new Clock
    clock.at = beforeSave
    val feed = new ObserverFeed(ObserverFeed.Standalone, () => Some(antica), () => Map.empty, new FakeRedis, () => clock.now())
    feed.refreshMwc()
    clock.at = Instant.parse("2026-09-24T08:44:00Z")
    feed.refreshMwc() shouldBe Some(Map.empty)
    clock.at = Instant.parse("2026-09-24T08:45:00Z")
    feed.refreshMwc() shouldBe Some(antica)
  }

  test("a world with no changes before server save gets the day's straight away") {
    val clock = new Clock
    clock.at = beforeSave
    var answer: Option[Map[String, List[MiniWorldChange]]] = Some(Map.empty)
    val feed = new ObserverFeed(ObserverFeed.Standalone, () => answer, () => Map.empty, new FakeRedis, () => clock.now())
    feed.refreshMwc()
    answer = Some(antica)
    clock.at = afterSave
    feed.refreshMwc() shouldBe Some(antica)
  }

  test("a primary restarted after server save, and its secondaries, hold yesterday's back too") {
    val clock = new Clock
    val redis = new FakeRedis
    var answer: Option[Map[String, List[MiniWorldChange]]] = Some(antica)
    clock.at = beforeSave
    new ObserverFeed(ObserverFeed.Publisher, () => answer, () => Map.empty, redis, () => clock.now()).refreshMwc()
    clock.at = afterSave
    val restarted = new ObserverFeed(ObserverFeed.Publisher, () => answer, () => Map.empty, redis, () => clock.now())
    val secondary = new ObserverFeed(ObserverFeed.Consumer, never, never, redis, () => clock.now())
    restarted.refreshMwc() shouldBe Some(Map.empty)
    secondary.refreshMwc() shouldBe Some(Map.empty)
    answer = Some(anticaToday)
    restarted.refreshMwc() shouldBe Some(anticaToday)
    secondary.refreshMwc() shouldBe Some(anticaToday)
  }

  test("an answer with no changes to give is remembered once, for the watcher") {
    var answer: Option[Map[String, List[MiniWorldChange]]] = None
    val feed = new ObserverFeed(ObserverFeed.Standalone, () => answer, () => Map.empty, new FakeRedis)
    feed.takeAnsweredWithout() shouldBe false
    feed.mwcForWorld("Antica") shouldBe empty
    feed.takeAnsweredWithout() shouldBe true
    feed.takeAnsweredWithout() shouldBe false
    // A world that simply has no changes today is an answer, not a gap.
    answer = Some(antica)
    feed.mwcForWorld("Secura") shouldBe empty
    feed.takeAnsweredWithout() shouldBe false
  }

  test("a raid's missing subarea and start survive the copy") {
    val bare = raid.copy(subarea = None, startDate = None)
    FeedJson.parseRaids(FeedJson.raids(Map("Antica" -> List(raid, bare)))) shouldBe Some(Map("Antica" -> List(raid, bare)))
  }
}
