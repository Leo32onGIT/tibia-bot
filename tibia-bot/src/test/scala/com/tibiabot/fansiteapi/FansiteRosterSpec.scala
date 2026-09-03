package com.tibiabot.fansiteapi

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

/** Who gets a slot in the fansite budget.
 *
 *  The interesting cases are all about scarcity: what happens when more is
 *  offered than can be afforded, and what happens to a world that goes quiet
 *  while still holding slots. Neither is visible in normal running, and both
 *  decide whether the paced lane spends its requests on the characters somebody
 *  actually asked about. */
class FansiteRosterSpec extends AnyFunSuite with Matchers {

  private final class Clock {
    private var t: Instant = Instant.parse("2026-09-03T00:00:00Z")
    def read(): Instant = t
    def advance(by: FiniteDuration): Unit = t = t.plusSeconds(by.toSeconds)
  }

  private def roster(clock: Clock = new Clock(), budget: Int = 3, staleAfter: FiniteDuration = 3.minutes) =
    new FansiteRoster(budget, staleAfter, () => clock.read())

  test("everyone offered is admitted while there is room") {
    val r = roster()
    r.publish("Antica", Seq("Bubble" -> 500, "Eternal Oblivion" -> 400))

    r.admits("Bubble") shouldBe true
    r.admits("Eternal Oblivion") shouldBe true
    r.admittedCount shouldBe 2
  }

  test("past the budget the highest levels are kept and the lowest dropped") {
    val r = roster(budget = 3)
    r.publish("Antica", Seq("Low" -> 100, "Top" -> 900, "Mid" -> 500, "Lowest" -> 50, "High" -> 700))

    r.admits("Top") shouldBe true
    r.admits("High") shouldBe true
    r.admits("Mid") shouldBe true
    r.admits("Low") shouldBe false
    r.admits("Lowest") shouldBe false
    r.admittedCount shouldBe 3
  }

  test("ranking is fleet-wide, so a busy world outbids a quiet one") {
    val r = roster(budget = 2)
    r.publish("Quiet", Seq("Villager" -> 200, "Farmer" -> 150))
    r.publish("Busy", Seq("Warlord" -> 900, "Champion" -> 800))

    r.admits("Warlord") shouldBe true
    r.admits("Champion") shouldBe true
    r.admits("Villager") shouldBe false
    r.admits("Farmer") shouldBe false
  }

  test("a character who levels past an admitted one takes the slot") {
    val r = roster(budget = 1)
    r.publish("Antica", Seq("Slow" -> 500, "Fast" -> 499))
    r.admits("Slow") shouldBe true

    r.publish("Antica", Seq("Slow" -> 500, "Fast" -> 501))
    r.admits("Fast") shouldBe true
    r.admits("Slow") shouldBe false
  }

  test("republishing a world replaces what it offered before") {
    val r = roster(budget = 10)
    r.publish("Antica", Seq("LoggedOn" -> 300, "LoggedOff" -> 400))
    r.publish("Antica", Seq("LoggedOn" -> 300))

    r.admits("LoggedOn") shouldBe true
    r.admits("LoggedOff") shouldBe false
  }

  test("a world with nobody hunted online holds no budget") {
    val r = roster(budget = 2)
    r.publish("Antica", Seq("Someone" -> 300))
    r.publish("Antica", Nil)

    r.admittedCount shouldBe 0
  }

  test("a world that stops publishing stops holding slots") {
    val clock = new Clock()
    val r = roster(clock, budget = 2, staleAfter = 3.minutes)
    r.publish("Gone", Seq("Ghost" -> 900))
    r.publish("Live", Seq("Present" -> 100))
    r.admits("Ghost") shouldBe true

    clock.advance(4.minutes)
    r.publish("Live", Seq("Present" -> 100))

    // The high level no longer wins a slot it cannot use — nobody is polling it.
    r.admits("Ghost") shouldBe false
    r.admits("Present") shouldBe true
  }

  test("a world that comes back is counted again") {
    val clock = new Clock()
    val r = roster(clock, budget = 2)
    r.publish("Flaky", Seq("Returner" -> 900))

    clock.advance(4.minutes)
    r.publish("Other", Seq("Someone" -> 100))
    r.admits("Returner") shouldBe false

    r.publish("Flaky", Seq("Returner" -> 900))
    r.admits("Returner") shouldBe true
  }

  test("names match however they are capitalised") {
    val r = roster()
    r.publish("Antica", Seq("Eternal Oblivion" -> 500))

    r.admits("eternal oblivion") shouldBe true
    r.admits("ETERNAL OBLIVION") shouldBe true
    r.admits("Eternal Oblivion") shouldBe true
  }

  test("nobody is admitted before a single world has published") {
    roster().admits("Anyone") shouldBe false
  }

  test("nonsense settings are refused at construction") {
    an[IllegalArgumentException] should be thrownBy roster(budget = 0)
    an[IllegalArgumentException] should be thrownBy roster(staleAfter = Duration.Zero)
  }
}
