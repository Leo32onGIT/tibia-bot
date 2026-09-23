package com.tibiabot.observer

import com.tibiabot.domain.MiniWorldChange
import com.tibiabot.domain.time.Clock
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{LocalDateTime, ZonedDateTime}
import scala.collection.mutable.ListBuffer

class MiniWorldChangeWatcherSpec extends AnyFunSuite with Matchers {

  private def at(hour: Int, minute: Int): ZonedDateTime =
    LocalDateTime.of(2026, 9, 23, hour, minute).atZone(Clock.Berlin)

  private def mwc(world: String, titles: String*): Map[String, List[MiniWorldChange]] =
    Map(world.toLowerCase -> titles.toList.map(t => MiniWorldChange(world, t, s"$t is active.")))

  /** A watcher over a scripted feed and clock, recording what it amends. */
  private class Harness {
    var feed: Option[Map[String, List[MiniWorldChange]]] = Some(Map.empty)
    var clock: ZonedDateTime = at(9, 0)
    var fetches = 0
    val amended: ListBuffer[Set[String]] = ListBuffer.empty
    val watcher = new MiniWorldChangeWatcher(
      fetch = () => { fetches += 1; feed },
      amend = worlds => amended += worlds,
      now = () => clock)
    def tickAt(hour: Int, minute: Int): Unit = { clock = at(hour, minute); watcher.tick() }
  }

  test("the first poll only records what is active") {
    val h = new Harness
    h.feed = Some(mwc("Antica", "Fury Gate"))
    h.tickAt(10, 1)
    h.amended shouldBe empty
  }

  test("amends just the worlds whose changes moved on") {
    val h = new Harness
    h.feed = Some(mwc("Antica", "Fury Gate") ++ mwc("Secura", "Nomads"))
    h.tickAt(10, 1)
    h.feed = Some(mwc("Antica", "Fury Gate", "Warpath") ++ mwc("Secura", "Nomads"))
    h.tickAt(10, 3)
    h.amended.toList shouldBe List(Set("antica"))
  }

  test("amends a world whose last change ended, so its embed comes back out") {
    val h = new Harness
    h.feed = Some(mwc("Antica", "Fury Gate"))
    h.tickAt(10, 1)
    h.feed = Some(Map.empty)
    h.tickAt(10, 3)
    h.amended.toList shouldBe List(Set("antica"))
  }

  test("a failed poll is skipped without forgetting the last good set") {
    val h = new Harness
    h.feed = Some(mwc("Antica", "Fury Gate"))
    h.tickAt(10, 1)
    h.feed = None
    h.tickAt(10, 3)
    h.amended shouldBe empty
    h.feed = Some(mwc("Antica", "Fury Gate"))
    h.tickAt(10, 5)
    h.amended shouldBe empty
  }

  test("polls every 2 minutes through the server-save window, every 15 otherwise") {
    val h = new Harness
    (1 to 10).foreach(m => h.tickAt(10, m))
    h.fetches shouldBe 5 // 10:01, 10:03, 10:05, 10:07, 10:09
    (0 to 29).foreach(m => h.tickAt(12, m))
    h.fetches shouldBe 7 // 12:00, 12:15
  }
}
