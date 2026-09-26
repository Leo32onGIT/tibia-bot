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

  /** A watcher over a scripted feed and clock, recording what it amends. `quiet`
   *  is the bot that asks the API itself. */
  private class Harness(quiet: Boolean = false) {
    var feed: Option[Map[String, List[MiniWorldChange]]] = Some(Map.empty)
    var clock: ZonedDateTime = at(9, 0)
    var fetches = 0
    var answeredWithout = false
    var rulesChanged = false
    val amended: ListBuffer[Set[String]] = ListBuffer.empty
    val watcher = new MiniWorldChangeWatcher(
      fetch = () => { fetches += 1; feed },
      amend = worlds => amended += worlds,
      now = () => clock,
      answeredWithout = () => { val was = answeredWithout; answeredWithout = false; was },
      quietOutsideWindow = quiet,
      rulesChanged = () => { val was = rulesChanged; rulesChanged = false; was })
    def tickAt(hour: Int, minute: Int): Unit = { clock = at(hour, minute); watcher.tick() }
    def tickAt(day: Int, hour: Int, minute: Int): Unit = {
      clock = LocalDateTime.of(2026, 9, day, hour, minute).atZone(Clock.Berlin)
      watcher.tick()
    }
  }

  test("asking the API itself, it polls once after a restart outside the window, then not again that day") {
    val h = new Harness(quiet = true)
    h.tickAt(12, 0)
    (1 to 59).foreach(m => h.tickAt(12, m))
    (0 to 59).foreach(m => h.tickAt(20, m))
    h.fetches shouldBe 1
  }

  test("asking the API itself, it polls every 2 minutes through the window, once just after, then stops") {
    val h = new Harness(quiet = true)
    h.tickAt(9, 0)
    (0 to 59).foreach(m => h.tickAt(10, m))
    (0 to 59).foreach(m => h.tickAt(11, m))
    // 9:00 after the restart; 10:00 at server save and every 2 minutes to 10:44 through
    // the window; 10:46 once they've settled.
    h.fetches shouldBe 1 + 1 + 22 + 1
    // And the next day's window again.
    h.tickAt(24, 10, 1)
    h.fetches shouldBe 26
  }

  test("asking the API itself, a failed poll is tried again every 2 minutes until one gets through") {
    val h = new Harness(quiet = true)
    h.feed = None
    (0 to 3).foreach(m => h.tickAt(12, m))
    h.fetches shouldBe 2 // 12:00, 12:02
    h.feed = Some(mwc("Antica", "Fury Gate"))
    (4 to 9).foreach(m => h.tickAt(12, m))
    h.fetches shouldBe 3 // 12:04, and then settled
  }

  test("asking the API itself, rules changing brings a poll outside the window, and just the one") {
    val h = new Harness(quiet = true)
    h.tickAt(12, 0)
    h.rulesChanged = true
    h.tickAt(12, 1) // too soon after the last poll: kept for the next
    h.fetches shouldBe 1
    h.tickAt(12, 2)
    h.fetches shouldBe 2
    (3 to 30).foreach(m => h.tickAt(12, m))
    h.fetches shouldBe 2
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

  test("messages posted with no changes to be had get every world's at the next good poll") {
    val h = new Harness
    h.feed = Some(mwc("Antica", "Fury Gate") ++ mwc("Secura", "Nomads"))
    h.tickAt(10, 1)
    // A message went out while the feed was down; the changes themselves never moved.
    h.feed = None
    h.answeredWithout = true
    h.tickAt(10, 3)
    h.amended shouldBe empty
    h.feed = Some(mwc("Antica", "Fury Gate") ++ mwc("Secura", "Nomads"))
    h.tickAt(10, 5)
    h.amended.toList shouldBe List(Set("antica", "secura"))
    h.tickAt(10, 7)
    h.amended should have size 1
  }

  test("yesterday's changes going at server save is not a change, and the day's coming in is") {
    val h = new Harness
    h.feed = Some(mwc("Antica", "Fury Gate"))
    h.tickAt(9, 50)
    h.feed = Some(Map.empty) // the feed holds yesterday's back
    h.tickAt(10, 1)
    h.amended shouldBe empty
    h.feed = Some(mwc("Antica", "Warpath"))
    h.tickAt(10, 7)
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
