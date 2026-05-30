package com.tibiabot.domain.time

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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

  test("indexOfBoss maps each boss to its position") {
    DreamScarCycle.indexOfBoss("Plagueroot") shouldBe 0
    DreamScarCycle.indexOfBoss("Izcandar the Banished") shouldBe 4
  }
}
