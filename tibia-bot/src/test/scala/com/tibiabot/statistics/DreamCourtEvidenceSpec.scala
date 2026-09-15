package com.tibiabot.statistics

import com.tibiabot.domain.time.DreamScarCycle
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

/** Reading a world's Dream Courts boss out of what it killed.
 *
 *  The measurements this is built on: several of the five die on the same world
 *  on the same day, so the signal is which died *most*; that signal agreed with
 *  the wiki about 46% of the time on a full sweep, with a quarter one step
 *  behind and a quarter one step ahead; and two thirds of signals were thin
 *  enough to be noise. So these tests are mostly about what it refuses to say. */
class DreamCourtEvidenceSpec extends AnyFunSuite with Matchers {

  private val today = LocalDate.of(2026, 9, 12)
  private val cycle = DreamScarCycle.bossCycle

  private def daysAgo(n: Int) = today.minusDays(n.toLong)

  private def row(n: Int, index: Int, count: Int) =
    BossKills("Antica", daysAgo(n), cycle(Math.floorMod(index, cycle.length)), count, 0)

  /** A world whose boss today is `index`, seen `n` days ago as the rotation had
   *  it then — one step back per day. */
  private def truthful(n: Int, index: Int, count: Int = 5) = row(n, index - n, count)

  /** The five rows one day produces, most of them zero. */
  private def quietDay(n: Int) = cycle.indices.toList.map(i => row(n, i, 0))

  // --- one day's answer ----------------------------------------------------

  test("a day points at the boss it killed most of") {
    DreamCourtEvidence.observed(List(row(0, 3, 18), row(0, 2, 5))) shouldBe Some(3)
  }

  test("a day nobody killed one on says nothing") {
    DreamCourtEvidence.observed(quietDay(0)) shouldBe None
    DreamCourtEvidence.observed(Nil) shouldBe None
  }

  test("a tie says nothing rather than picking one") {
    // Genuinely two answers. Seen in the real sweep — Celebra, 2 against 2.
    DreamCourtEvidence.observed(List(row(0, 1, 2), row(0, 4, 2))) shouldBe None
  }

  test("a race that is not one of the five is ignored") {
    val rows = List(BossKills("Antica", daysAgo(0), "rotworm", 120000, 0), row(0, 1, 3))
    DreamCourtEvidence.observed(rows) shouldBe Some(1)
  }

  test("the five are matched however the endpoint cases them") {
    val shouty = BossKills("Antica", daysAgo(0), cycle(2).toUpperCase, 9, 0)
    DreamCourtEvidence.observed(List(shouty)) shouldBe Some(2)
  }

  // --- carrying a day forward ----------------------------------------------

  test("an older day still votes, one step per day") {
    // The rotation advances one step a day, so a boss seen four days ago implies
    // a boss four steps on — which is what makes a fortnight usable rather than
    // just yesterday.
    DreamCourtEvidence.votes(List(row(4, 0, 5)), today) shouldBe Map(4 -> 1)
  }

  test("the projection wraps round the cycle") {
    DreamCourtEvidence.votes(List(row(7, 4, 5)), today) shouldBe Map(1 -> 1)
  }

  test("days that agree pile onto the same answer") {
    val rows = (0 to 3).toList.flatMap(n => List(truthful(n, 2)))
    DreamCourtEvidence.votes(rows, today) shouldBe Map(2 -> 4)
  }

  // --- the verdict ---------------------------------------------------------

  test("a world that has been consistent all fortnight is answered unanimously") {
    val rows = (0 to 9).toList.map(n => truthful(n, 2))
    val verdict = DreamCourtEvidence.verdict(rows, today, minDays = 6, minLead = 2).value
    verdict.bossIndex shouldBe 2
    verdict.boss shouldBe cycle(2)
    verdict.votes shouldBe 10
    verdict.runnerUp shouldBe 0
    verdict.days shouldBe 10
  }

  test("noise either side of the truth does not move the answer") {
    // The shape the real data had: right most of the time, and wrong by exactly
    // one step in both directions the rest of it. That is what accumulating
    // fixes, because the errors fall either side rather than compounding.
    val right = (0 to 5).toList.map(n => truthful(n, 2))
    val behind = (6 to 7).toList.map(n => row(n, 2 - n - 1, 5))
    val ahead = (8 to 9).toList.map(n => row(n, 2 - n + 1, 5))
    val verdict = DreamCourtEvidence.verdict(right ++ behind ++ ahead, today, 6, 2).value
    verdict.bossIndex shouldBe 2
    verdict.votes shouldBe 6
    verdict.runnerUp shouldBe 2
    verdict.lead shouldBe 4
  }

  test("a window too thin to be sure says nothing") {
    // Three against two against two. A real answer is in there somewhere, but
    // not one worth overruling the wiki with.
    val right = (0 to 2).toList.map(n => truthful(n, 2))
    val behind = (3 to 4).toList.map(n => row(n, 2 - n - 1, 5))
    val ahead = (5 to 6).toList.map(n => row(n, 2 - n + 1, 5))
    DreamCourtEvidence.verdict(right ++ behind ++ ahead, today, 6, 2) shouldBe None
  }

  test("a world with too few days says nothing however clean they are") {
    val rows = (0 to 4).toList.map(n => truthful(n, 2))
    DreamCourtEvidence.verdict(rows, today, minDays = 6, minLead = 2) shouldBe None
    // One more day and the same evidence is enough.
    DreamCourtEvidence.verdict(rows :+ truthful(5, 2), today, 6, 2).map(_.bossIndex) shouldBe Some(2)
  }

  test("a world nobody hunts says nothing rather than guessing") {
    val rows = (0 to 13).toList.flatMap(quietDay)
    DreamCourtEvidence.verdict(rows, today, 6, 2) shouldBe None
  }

  test("a one-vote lead is not enough") {
    val right = (0 to 3).toList.map(n => truthful(n, 2))
    val behind = (4 to 6).toList.map(n => row(n, 2 - n - 1, 5))
    DreamCourtEvidence.verdict(right ++ behind, today, 6, minLead = 2) shouldBe None
    DreamCourtEvidence.verdict(right ++ behind, today, 6, minLead = 1).map(_.bossIndex) shouldBe Some(2)
  }

  test("quiet days do not count towards the days a verdict needs") {
    // Six days of history, only four of which anybody killed anything on.
    val seen = (0 to 3).toList.map(n => truthful(n, 2))
    val quiet = (4 to 9).toList.flatMap(quietDay)
    DreamCourtEvidence.verdict(seen ++ quiet, today, 6, 2) shouldBe None
  }

  private implicit class OptionOps[A](option: Option[A]) {
    def value: A = option.getOrElse(fail("expected a verdict"))
  }
}
