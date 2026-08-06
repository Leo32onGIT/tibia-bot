package com.tibiabot.domain.time

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.DayOfWeek

class DreamScarCycleSpec extends AnyFunSuite with Matchers {

  test("each world's boss advances to the next in the cycle") {
    DreamScarCycle.shiftAllBossesUp(Map("Antica" -> "Plagueroot")) shouldBe
      Map("Antica" -> "Malofur Mangrinder")
  }

  test("the last boss wraps around to the first") {
    DreamScarCycle.shiftAllBossesUp(Map("Antica" -> "Izcandar the Banished")) shouldBe
      Map("Antica" -> "Plagueroot")
  }

  test("an unknown boss is left unchanged") {
    DreamScarCycle.shiftAllBossesUp(Map("Antica" -> "World not found")) shouldBe
      Map("Antica" -> "World not found")
  }

  test("shifts every world independently") {
    val before = Map("Antica" -> "Maxxenius", "Bona" -> "Alptramun")
    DreamScarCycle.shiftAllBossesUp(before) shouldBe
      Map("Antica" -> "Alptramun", "Bona" -> "Izcandar the Banished")
  }

  test("a multi-day shift advances that many steps, wrapping") {
    val before = Map("Antica" -> "Plagueroot")
    DreamScarCycle.shiftAllBossesUp(before, 2) shouldBe Map("Antica" -> "Maxxenius")
    DreamScarCycle.shiftAllBossesUp(before, 5) shouldBe before  // a full cycle
    DreamScarCycle.shiftAllBossesUp(before, 6) shouldBe Map("Antica" -> "Malofur Mangrinder")
  }

  test("shifting zero days leaves the map alone") {
    val before = Map("Antica" -> "Alptramun", "Bona" -> "World not found")
    DreamScarCycle.shiftAllBossesUp(before, 0) shouldBe before
  }

  test("daysBehind counts forward from the rendered day to the current one") {
    DreamScarCycle.daysBehind(DayOfWeek.WEDNESDAY, DayOfWeek.WEDNESDAY) shouldBe 0
    DreamScarCycle.daysBehind(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY) shouldBe 1
    DreamScarCycle.daysBehind(DayOfWeek.SATURDAY, DayOfWeek.MONDAY) shouldBe 2 // wraps the week
    DreamScarCycle.daysBehind(DayOfWeek.THURSDAY, DayOfWeek.WEDNESDAY) shouldBe 6
  }

  // The whole point of pairing the two: a page cached a day before the rollover
  // is brought up to date rather than believed or thrown away.
  test("a day-stale render is corrected by shifting it daysBehind steps") {
    val stale = Map("Victoris" -> "Plagueroot")
    val behind = DreamScarCycle.daysBehind(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
    DreamScarCycle.shiftAllBossesUp(stale, behind) shouldBe Map("Victoris" -> "Malofur Mangrinder")
  }

  test("indexOfBoss maps each boss to its position") {
    DreamScarCycle.indexOfBoss("Plagueroot") shouldBe 0
    DreamScarCycle.indexOfBoss("Izcandar the Banished") shouldBe 4
  }

  test("isDreamCourtBoss recognises every cycle boss, case-insensitively") {
    DreamScarCycle.bossCycle.foreach { boss =>
      DreamScarCycle.isDreamCourtBoss(boss) shouldBe true
      DreamScarCycle.isDreamCourtBoss(boss.toLowerCase) shouldBe true
    }
  }

  test("isDreamCourtBoss is false for non-cycle names") {
    DreamScarCycle.isDreamCourtBoss("Ferumbras") shouldBe false
    DreamScarCycle.isDreamCourtBoss("") shouldBe false
  }
}
