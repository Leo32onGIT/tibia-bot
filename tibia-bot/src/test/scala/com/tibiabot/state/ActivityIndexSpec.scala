package com.tibiabot.state

import com.tibiabot.domain.{ActivityIndex, PlayerCache}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** The name lookup the death scan asks its activity questions through, and the
 *  memoisation that makes it worth building.
 *
 *  What it has to get right is that it answers exactly what searching the row
 *  list with `equalsIgnoreCase` answered — including which row wins when two
 *  collapse onto one name — and that it is rebuilt when, and only when, the
 *  guild's rows are replaced.
 */
class ActivityIndexSpec extends AnyFunSuite with Matchers {

  private val when = ZonedDateTime.parse("2026-01-01T00:00:00Z")
  private def row(name: String, guild: String = "Nemesis") = PlayerCache(name, Nil, guild, when)

  private def stateWith(activity: Map[String, List[PlayerCache]]): StreamState = {
    val state = new StreamState
    state.modifyActivityData(_ => activity)
    state
  }

  test("a row is found under any casing of its name") {
    val index = ActivityIndex(List(row("Bob")))
    index.get("bob").map(_.name) shouldBe Some("Bob")
    index.get("BOB").map(_.name) shouldBe Some("Bob")
    index.contains("bOb") shouldBe true
  }

  test("a name with no row is absent rather than wrong") {
    val index = ActivityIndex(List(row("Bob")))
    index.get("Carol") shouldBe None
    index.contains("Carol") shouldBe false
  }

  test("the empty index answers nothing, including for the empty name") {
    ActivityIndex.empty.get("Bob") shouldBe None
    ActivityIndex.empty.contains("") shouldBe false
    ActivityIndex(Nil).size shouldBe 0
  }

  test("two rows differing only in case keep the one a list search would have found") {
    // tracked_activity's primary key is on `name`, which Postgres compares
    // case-sensitively, so both rows can exist. `find` returned the first.
    val rows = List(row("Bob", "Nemesis"), row("bob", "Vindicate"))
    ActivityIndex(rows).get("BOB").map(_.guild) shouldBe Some("Nemesis")
    ActivityIndex(rows).size shouldBe 1
    ActivityIndex(rows.reverse).get("BOB").map(_.guild) shouldBe Some("Vindicate")
  }

  test("the index answers the same as scanning the list it was built from") {
    val rows = List(row("Bob"), row("carol"), row("Dave"))
    val index = ActivityIndex(rows)
    List("Bob", "bob", "CAROL", "Dave", "Erin", "").foreach { name =>
      index.contains(name) shouldBe rows.exists(_.name.equalsIgnoreCase(name))
      index.get(name) shouldBe rows.find(_.name.equalsIgnoreCase(name))
    }
  }

  test("a guild's index is built from that guild's rows and no other's") {
    val state = stateWith(Map("a" -> List(row("Bob")), "b" -> List(row("Carol"))))
    state.activityIndex("a").contains("Bob") shouldBe true
    state.activityIndex("a").contains("Carol") shouldBe false
    state.activityIndex("b").contains("Carol") shouldBe true
  }

  test("a guild with no rows at all gets an empty index rather than a failure") {
    val state = stateWith(Map("a" -> List(row("Bob"))))
    state.activityIndex("never-set-up").contains("Bob") shouldBe false
    state.activityIndex("never-set-up").size shouldBe 0
  }

  test("an untouched guild is indexed once and handed back the same index after that") {
    // The whole point of memoising: the map is read three times per character
    // per discord and written a handful of times an hour, so a guild nobody
    // wrote to must not pay for a rebuild.
    val state = stateWith(Map("a" -> List(row("Bob"))))
    val first = state.activityIndex("a")
    state.activityIndex("a") should be theSameInstanceAs first

    // A write to a *different* guild leaves this one's rows alone, so it must
    // not invalidate this one either.
    state.modifyActivityData(_ + ("b" -> List(row("Carol"))))
    state.activityIndex("a") should be theSameInstanceAs first
  }

  test("replacing a guild's rows rebuilds its index") {
    val state = stateWith(Map("a" -> List(row("Bob"))))
    val stale = state.activityIndex("a")

    state.modifyActivityData(m => m + ("a" -> (row("Carol") :: m("a"))))

    val fresh = state.activityIndex("a")
    fresh should not be theSameInstanceAs(stale)
    fresh.contains("Carol") shouldBe true
    fresh.contains("Bob") shouldBe true
  }

  test("a row removed from a guild is gone from its index") {
    val state = stateWith(Map("a" -> List(row("Bob"), row("Carol"))))
    state.activityIndex("a").contains("Bob") shouldBe true

    state.modifyActivityData(_ + ("a" -> List(row("Carol"))))

    state.activityIndex("a").contains("Bob") shouldBe false
    state.activityIndex("a").contains("Carol") shouldBe true
  }

  test("a renamed row is found under the new name and not the old one") {
    // The scan's own write path: applyRename replaces the guild's list, and the
    // next poll must see the move or it announces the rename all over again.
    val state = stateWith(Map("a" -> List(row("Bob"))))
    state.activityIndex("a").contains("Bob") shouldBe true

    state.modifyActivityData { m =>
      m + ("a" -> com.tibiabot.presentation.GuildActivity.applyRename(m("a"), "Bob", "Alice", List("Bob"), when))
    }

    state.activityIndex("a").contains("Bob") shouldBe false
    state.activityIndex("a").get("Alice").map(_.formerNames) shouldBe Some(List("Bob"))
  }

  test("the index always answers from the rows currently in the map") {
    // Belt and braces over the memoisation: whatever the cache did, ten
    // successive edits must each be visible to the very next read.
    val state = stateWith(Map.empty)
    (1 to 10).foreach { i =>
      state.modifyActivityData(_ + ("a" -> List(row(s"Char$i"))))
      state.activityIndex("a").contains(s"Char$i") shouldBe true
      state.activityIndex("a").size shouldBe 1
    }
  }

  test("a guild removed and set up again is not answered from its old index") {
    // A discord removed and set up again — /clear, or leaving and re-inviting
    // the bot. Two independent things keep the old rows from coming back: the
    // removal drops the cache entry, and the identity check would find it stale
    // even if it had not. This pins the outcome rather than either mechanism.
    val state = stateWith(Map("a" -> List(row("Bob"))))
    state.activityIndex("a").contains("Bob") shouldBe true

    state.modifyActivityData(_ - "a")
    state.modifyActivityData(_ + ("a" -> List(row("Carol"))))

    state.activityIndex("a").contains("Bob") shouldBe false
    state.activityIndex("a").contains("Carol") shouldBe true
  }

  test("a guild dropped from the map does not answer from a cached index") {
    val state = stateWith(Map("a" -> List(row("Bob"))))
    state.activityIndex("a").contains("Bob") shouldBe true

    state.modifyActivityData(_ - "a")

    state.activityIndex("a").contains("Bob") shouldBe false
    state.activityIndex("a").size shouldBe 0
  }

  test("concurrent readers of one guild all get an index that answers correctly") {
    // Several world streams read the same discord's rows at once, and the cache
    // they fill in is shared. Nothing may see a half-built or foreign index.
    val rows = (1 to 200).map(i => row(s"Char$i")).toList
    val state = stateWith(Map("a" -> rows))

    val threads = (1 to 8).map { _ =>
      new Thread(() =>
        (1 to 200).foreach { i =>
          val index = state.activityIndex("a")
          assert(index.contains(s"Char$i"), s"Char$i missing")
          assert(index.size == 200)
        })
    }
    threads.foreach(_.start())
    threads.foreach(_.join())
  }
}
