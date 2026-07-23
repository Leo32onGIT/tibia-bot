package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Characterization tests: pin the CURRENT behaviour of the online-presence
 *  logic (TibiaBot.currentOnline). These must stay green after the Set->Map
 *  optimization. */
class OnlineTrackerSpec extends AnyFunSuite with Matchers {

  private val t0 = ZonedDateTime.parse("2026-05-30T10:00:00Z")
  private def at(sec: Int) = t0.plusSeconds(sec.toLong)

  test("new players start with empty guild, zero duration, empty flag") {
    val tr = new OnlineTracker
    tr.updateFromOnline(Seq(("Knight", 100, "Elite Knight")), t0)

    val p = tr.find("Knight").value
    p.level shouldBe 100
    p.vocation shouldBe "Elite Knight"
    p.guildName shouldBe ""
    p.duration shouldBe 0L
    p.flag shouldBe ""
    p.time shouldBe t0
  }

  test("duration accumulates across cycles for a player who stays online") {
    val tr = new OnlineTracker
    tr.updateFromOnline(Seq(("Mage", 50, "Master Sorcerer")), at(0))
    tr.updateFromOnline(Seq(("Mage", 51, "Master Sorcerer")), at(60))
    tr.updateFromOnline(Seq(("Mage", 52, "Master Sorcerer")), at(150))

    val p = tr.find("Mage").value
    p.duration shouldBe 150L   // (60-0) + (150-60)
    p.level shouldBe 52        // level/vocation always taken from the fresh list
    p.time shouldBe at(150)
  }

  test("guild and flag are carried over across cycles") {
    val tr = new OnlineTracker
    tr.updateFromOnline(Seq(("Paladin", 200, "Royal Paladin")), at(0))
    tr.setGuild("Paladin", "Red Rose")
    tr.setFlag("Paladin", ":arrow_up:")

    tr.updateFromOnline(Seq(("Paladin", 201, "Royal Paladin")), at(60))
    val p = tr.find("Paladin").value
    p.guildName shouldBe "Red Rose"
    p.flag shouldBe ":arrow_up:"
    p.level shouldBe 201
  }

  test("players who log off are dropped on the next update") {
    val tr = new OnlineTracker
    tr.updateFromOnline(Seq(("A", 1, "None"), ("B", 2, "None")), at(0))
    tr.size shouldBe 2

    tr.updateFromOnline(Seq(("A", 1, "None")), at(60))  // B logged off
    tr.find("B") shouldBe None
    tr.find("A") shouldBe defined
    tr.size shouldBe 1
  }

  test("setGuild only rewrites when the guild actually changed") {
    val tr = new OnlineTracker
    tr.updateFromOnline(Seq(("X", 10, "None")), at(0))
    tr.setGuild("X", "Alpha")
    tr.setGuild("X", "Alpha")  // no-op
    tr.find("X").value.guildName shouldBe "Alpha"

    tr.setGuild("X", "Beta")
    tr.find("X").value.guildName shouldBe "Beta"
  }

  test("find is exact and case-sensitive (matches the original .find(_.name == x))") {
    val tr = new OnlineTracker
    tr.updateFromOnline(Seq(("Charname", 5, "None")), at(0))
    tr.find("Charname") shouldBe defined
    tr.find("charname") shouldBe None
  }

  test("operations on unknown names are silent no-ops") {
    val tr = new OnlineTracker
    tr.updateFromOnline(Seq(("Known", 5, "None")), at(0))
    noException should be thrownBy tr.setGuild("Ghost", "G")
    noException should be thrownBy tr.setFlag("Ghost", "f")
    tr.size shouldBe 1
  }

  test("restore seeds an empty tracker, stamping time to restoreTime rather than the snapshot's original time") {
    val tr = new OnlineTracker
    val restoreTime = at(1000)
    tr.restore(List(OnlinePlayer("Restored", 80, "Knight", "Some Guild", at(0), duration = 500L, flag = ":arrow_up:")), restoreTime)

    val p = tr.find("Restored").value
    p.level shouldBe 80
    p.guildName shouldBe "Some Guild"
    p.duration shouldBe 500L
    p.flag shouldBe ":arrow_up:"
    p.time shouldBe restoreTime
  }

  test("restore does not clobber a player a live poll already updated (existing wins)") {
    val tr = new OnlineTracker
    tr.updateFromOnline(Seq(("Live", 10, "None")), at(0))  // a real poll landed first

    tr.restore(List(OnlinePlayer("Live", 999, "Stale", "Stale Guild", at(0), duration = 12345L)), at(5))

    val p = tr.find("Live").value
    p.level shouldBe 10
    p.guildName shouldBe ""
    p.duration shouldBe 0L
  }

  test("a real update after restore computes a small delta from the restore moment, not the downtime gap") {
    val tr = new OnlineTracker
    val restoreTime = at(0)
    tr.restore(List(OnlinePlayer("Restored", 80, "Knight", "", at(-100000), duration = 500L)), restoreTime)

    tr.updateFromOnline(Seq(("Restored", 81, "Knight")), at(60))

    val p = tr.find("Restored").value
    p.duration shouldBe 560L  // 500 + (60 - 0), not inflated by the huge pre-restart `time` gap
  }

  // tiny .value helper for Option without importing OptionValues
  private implicit class OptOps[A](o: Option[A]) {
    def value: A = o.getOrElse(fail("expected Some but was None"))
  }
}
