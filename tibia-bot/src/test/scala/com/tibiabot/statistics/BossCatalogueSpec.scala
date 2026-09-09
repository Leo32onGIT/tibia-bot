package com.tibiabot.statistics

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The bundled boss catalogue. Small, but every fact in it is one the daily
 *  snapshot depends on being right, and a wrong race name is invisible — it
 *  records the boss as never having spawned, on every world, forever. */
class BossCatalogueSpec extends AnyFunSuite with Matchers {

  test("the catalogue loads") {
    BossCatalogue.bosses should have size 74
  }

  test("every boss has a usable spawn window") {
    BossCatalogue.bosses.foreach { boss =>
      withClue(s"${boss.name}: ") {
        boss.name should not be empty
        boss.windowMin should be > 0
        boss.windowMax should be >= boss.windowMin
        boss.spawnPoints should be > 0
      }
    }
  }

  test("race names are unique, so no boss can shadow another") {
    val races = BossCatalogue.bosses.map(_.race.toLowerCase)
    races.distinct should have size races.size
    BossCatalogue.byRace should have size BossCatalogue.bosses.size
  }

  test("a boss whose kill-statistics name differs carries it") {
    // Checked against the live endpoint: tibia.com counts these under a plural
    // that is not the boss's own name. Matching on the name alone would record
    // all three as never spawning.
    BossCatalogue.byRace.get("yetis").map(_.name) shouldBe Some("Yeti")
    BossCatalogue.byRace.get("midnight panthers").map(_.name) shouldBe Some("Midnight Panther")
    BossCatalogue.byRace.get("albino dragons").map(_.name) shouldBe Some("Albino Dragon")
  }

  test("Rotworm Queen is matched on the singular the endpoint actually uses") {
    // Upstream mapped it to "Rotworm Queens", which appears on no world. The
    // singular was verified present on five.
    BossCatalogue.byRace.get("rotworm queen").map(_.name) shouldBe Some("Rotworm Queen")
    BossCatalogue.byRace should not contain key("rotworm queens")
  }

  test("most bosses are predictable, and the exceptions are marked rather than absent") {
    // The seventeen with no fixed window are still recorded — a kill is a fact
    // regardless — and simply will not be predicted.
    val unpredictable = BossCatalogue.bosses.filterNot(_.predict)
    unpredictable should not be empty
    unpredictable.size should be < BossCatalogue.bosses.size
    BossCatalogue.bosses.count(_.predict) should be > 50
  }

  test("lookup by race is case-insensitive both ways") {
    BossCatalogue.isBoss("YETIS") shouldBe true
    BossCatalogue.isBoss("Ferumbras") shouldBe true
    BossCatalogue.isBoss("dragon") shouldBe false
    BossCatalogue.isBoss("players") shouldBe false
  }

  test("the world bosses carry the long windows that make them worth predicting") {
    val ferumbras = BossCatalogue.bosses.find(_.name == "Ferumbras")
    ferumbras.map(_.windowMin) shouldBe Some(161)
    ferumbras.map(_.windowMax) shouldBe Some(175)
  }
}
