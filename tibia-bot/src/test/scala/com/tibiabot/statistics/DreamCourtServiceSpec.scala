package com.tibiabot.statistics

import com.tibiabot.domain.time.DreamScarCycle
import com.tibiabot.persistence.KillStatisticsRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

/** What the service does with a verdict: nothing, until it is told to. */
class DreamCourtServiceSpec extends AnyFunSuite with Matchers {

  private val today = LocalDate.of(2026, 9, 12)
  private val cycle = DreamScarCycle.bossCycle

  /** A world whose kills say `index` is today's boss, cleanly, for ten days. */
  private def history(world: String, index: Int): List[BossKills] =
    (0 to 9).toList.map { n =>
      BossKills(world, today.minusDays(n.toLong),
        cycle(Math.floorMod(index - n, cycle.length)), 5, 0)
    }

  private class StubKills(rows: Map[String, List[BossKills]], fail: Boolean = false)
      extends KillStatisticsRepository {
    def recordBossKills(rows: List[BossKills]): Unit = ()
    def recordSummary(summary: DayKillSummary): Unit = ()
    def hasDay(world: String, saveDay: LocalDate): Boolean = false
    def bossHistory(world: String, race: String, from: LocalDate): List[BossKills] = Nil
    def sightings(world: String, from: LocalDate): Map[String, List[(LocalDate, Int)]] = Map.empty
    def earliestDay(world: String): Option[LocalDate] = None
    def killsOn(world: String, saveDay: LocalDate): List[BossKills] = Nil
    def dailyCounts(world: String, from: LocalDate, races: Set[String]): List[BossKills] = {
      if (fail) throw new RuntimeException("cache is away")
      rows.getOrElse(world, Nil)
    }
    def summary(world: String, saveDay: LocalDate): Option[DayKillSummary] = None
    def removeExpired(before: LocalDate): Unit = ()
  }

  private def service(rows: Map[String, List[BossKills]], mode: DreamCourtMode, fail: Boolean = false) =
    new DreamCourtService(new StubKills(rows, fail), () => mode)

  test("observing leaves the wiki in charge even where it disagrees") {
    // Where this ships. The rule is built on one day's measurement of a signal
    // that was right about half the time on its own; the log is how we find out
    // whether a fortnight of them is better, rather than assuming it.
    val wiki = Map("Antica" -> cycle(0))
    val svc = service(Map("Antica" -> history("Antica", 3)), DreamCourtMode.Observe)
    svc.correct(wiki, today) shouldBe wiki
  }

  test("healing puts the world right") {
    val wiki = Map("Antica" -> cycle(0))
    val svc = service(Map("Antica" -> history("Antica", 3)), DreamCourtMode.Heal)
    svc.correct(wiki, today) shouldBe Map("Antica" -> cycle(3))
  }

  test("a world the evidence agrees with is left alone") {
    val wiki = Map("Antica" -> cycle(3))
    val svc = service(Map("Antica" -> history("Antica", 3)), DreamCourtMode.Heal)
    svc.correct(wiki, today) shouldBe wiki
  }

  test("a world with too little history keeps whatever the wiki said") {
    // The common case for a long while, and for quiet worlds forever.
    val wiki = Map("Antica" -> cycle(0))
    val thin = history("Antica", 3).take(3)
    service(Map("Antica" -> thin), DreamCourtMode.Heal).correct(wiki, today) shouldBe wiki
  }

  test("only the worlds that need moving are moved") {
    val wiki = Map("Antica" -> cycle(0), "Secura" -> cycle(1), "Vunira" -> cycle(4))
    val svc = service(Map(
      "Antica" -> history("Antica", 3),   // disagrees
      "Secura" -> history("Secura", 1),   // agrees
      "Vunira" -> Nil                     // nothing to say
    ), DreamCourtMode.Heal)
    svc.correct(wiki, today) shouldBe Map("Antica" -> cycle(3), "Secura" -> cycle(1), "Vunira" -> cycle(4))
  }

  test("a world the wiki never mentioned is not invented") {
    // The map is the wiki's shape; this corrects it rather than extending it, so
    // a world nothing reads about stays absent.
    val svc = service(Map("Antica" -> history("Antica", 3)), DreamCourtMode.Heal)
    svc.correct(Map.empty, today) shouldBe Map.empty
  }

  test("a cache that cannot be read costs the correction, not the map") {
    val wiki = Map("Antica" -> cycle(0))
    service(Map("Antica" -> history("Antica", 3)), DreamCourtMode.Heal, fail = true)
      .correct(wiki, today) shouldBe wiki
  }

  test("the mode is read afresh, so it can be turned on without a restart") {
    var mode: DreamCourtMode = DreamCourtMode.Observe
    val svc = new DreamCourtService(
      new StubKills(Map("Antica" -> history("Antica", 3))), () => mode)
    val wiki = Map("Antica" -> cycle(0))
    svc.correct(wiki, today) shouldBe wiki
    mode = DreamCourtMode.Heal
    svc.correct(wiki, today) shouldBe Map("Antica" -> cycle(3))
  }

  test("anything that is not heal is observe") {
    DreamCourtMode.parse("heal") shouldBe DreamCourtMode.Heal
    DreamCourtMode.parse("HEAL") shouldBe DreamCourtMode.Heal
    DreamCourtMode.parse("observe") shouldBe DreamCourtMode.Observe
    DreamCourtMode.parse("") shouldBe DreamCourtMode.Observe
    DreamCourtMode.parse("nonsense") shouldBe DreamCourtMode.Observe
  }
}
