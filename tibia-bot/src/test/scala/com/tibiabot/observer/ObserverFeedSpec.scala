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
    def close(): Unit = ()
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

  test("changes are reused for two minutes before asking again") {
    val clock = new Clock
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
    val copy = ObserverFeed.MwcCopy(Instant.parse("2026-09-24T08:05:00Z"), antica)
    FeedJson.parseMwc(FeedJson.mwc(copy)) shouldBe Some(copy)
    FeedJson.parseMwc("""{"antica":[{"world":"Antica","title":"Fury Gate","body":"Near Venore."}]}""") shouldBe None
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
