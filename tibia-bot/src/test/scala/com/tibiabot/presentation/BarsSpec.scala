package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The bars, drawn with readable stand-ins for the nine emoji so what comes out
 *  can be read as a string. */
class BarsSpec extends AnyFunSuite with Matchers {

  /** g/r/e for the colour, and the case says the shape: `G` a rounded start,
   *  `g` a middle, `G|` a rounded end. */
  private val ink: ((String, String)) => String = {
    case (colour, shape) =>
      val letter = colour.head.toString
      shape match {
        case "start" => letter.toUpperCase
        case "end" => letter.toUpperCase + "|"
        case _ => letter
      }
  }

  private def count(bar: String, letter: Char): Int = bar.count(_.toLower == letter)

  /** A ceiling high enough that the fills below are not all pinned to full. */
  private val Ceiling = 30

  test("a bar is always the same number of segments") {
    List((9.0, 4.0), (47.0, 23.0), (31.0, 0.0), (0.0, 0.0), (1.0, 900.0)).foreach { case (left, right) =>
      withClue(s"$left v $right: ") {
        val bar = Bars.split(left, right, ink, Ceiling, segments = 12)
        (count(bar, 'g') + count(bar, 'r') + count(bar, 'e')) shouldBe 12
      }
    }
  }

  test("the run is rounded at both ends and square in between") {
    val bar = Bars.split(9, 4, ink, Ceiling, segments = 6)
    bar.head.isUpper shouldBe true
    bar should endWith("|")
    // exactly two capitals: the first piece and the last
    bar.count(_.isUpper) shouldBe 2
  }

  // --- the fill: how big the day was ---------------------------------------

  test("a bigger day fills more of the bar") {
    val small = Bars.split(3, 0, ink, Ceiling, segments = 12)
    val large = Bars.split(25, 0, ink, Ceiling, segments = 12)
    count(small, 'e') should be > count(large, 'e')
    count(small, 'g') should be < count(large, 'g')
  }

  test("a day at the ceiling fills the bar, and nothing beyond it overflows") {
    count(Bars.split(Ceiling, 0, ink, Ceiling, segments = 12), 'g') shouldBe 12
    count(Bars.split(Ceiling * 10, 0, ink, Ceiling, segments = 12), 'g') shouldBe 12
  }

  test("the fill is logarithmic, so a small day still reads as a day") {
    // Linear would give one segment at 3 against a ceiling of 30. The point of
    // the curve is that a handful of frags is a real day and ought to look it.
    count(Bars.split(3, 0, ink, Ceiling, segments = 12), 'g') should be >= 4
  }

  test("anything at all shows at least one segment") {
    val bar = Bars.split(1, 0, ink, 500, segments = 12)
    count(bar, 'g') shouldBe 1
    count(bar, 'e') shouldBe 11
  }

  test("a lower ceiling fills the same day further") {
    // The same tally on a quieter world is a bigger day there.
    count(Bars.split(6, 0, ink, 6, segments = 12), 'g') should be >
      count(Bars.split(6, 0, ink, 100, segments = 12), 'g')
  }

  test("a ceiling of nothing cannot divide by itself") {
    noException should be thrownBy Bars.split(5, 1, ink, 0, segments = 12)
    noException should be thrownBy Bars.split(5, 1, ink, 1, segments = 12)
  }

  // --- the split: who won ---------------------------------------------------

  test("the split follows the two figures within the fill") {
    // 9 against 3 at the ceiling: full bar, and three quarters of it green.
    val bar = Bars.split(9, 3, ink, 12, segments = 12)
    count(bar, 'e') shouldBe 0
    count(bar, 'g') shouldBe 9
    count(bar, 'r') shouldBe 3
  }

  test("a side that scored anything never disappears entirely") {
    // 31 against 1 rounds to nothing at twelve segments, and a bar that reads as
    // a clean sweep when somebody did die is a lie.
    val bar = Bars.split(31, 1, ink, Ceiling, segments = 12)
    count(bar, 'r') shouldBe 1
    count(bar, 'g') shouldBe 11
  }

  test("a side that scored nothing does disappear") {
    val bar = Bars.split(31, 0, ink, Ceiling, segments = 12)
    count(bar, 'r') shouldBe 0
    count(bar, 'g') shouldBe 12
  }

  test("a day with nothing on either side is all track") {
    // It used to split down the middle, which drew a dead-even war on a day
    // nobody died — the one reading that is definitely wrong.
    val bar = Bars.split(0, 0, ink, Ceiling, segments = 12)
    count(bar, 'e') shouldBe 12
    count(bar, 'g') shouldBe 0
    count(bar, 'r') shouldBe 0
  }

  // --- weighing a death by level -------------------------------------------

  test("a death counts for more the higher the victim was") {
    Bars.weigh(levels = 300, deaths = 1, referenceLevel = 150) shouldBe 2.0
    Bars.weigh(levels = 150, deaths = 1, referenceLevel = 150) shouldBe 1.0
  }

  test("a pile of low levels stays a small day") {
    // Thirty level eights against a world that fights at 300 — the same count as
    // a real war, and nothing like the same day.
    val nobodies = Bars.weigh(levels = 30 * 8, deaths = 30, referenceLevel = 300)
    nobodies should be < 1.0
    // Two segments out of twelve — the curve gives anything non-zero a foothold,
    // and this stays a foothold rather than a war.
    count(Bars.split(nobodies, 0, ink, Ceiling, segments = 12), 'g') should be <= 2
  }

  test("the same low levels on a low-level world are a real day") {
    // The reference is the world's own, so a young server is measured against
    // itself rather than against Antica.
    val young = Bars.weigh(levels = 30 * 8, deaths = 30, referenceLevel = 10)
    young shouldBe 24.0
    count(Bars.split(young, 0, ink, Ceiling, segments = 12), 'g') should be > 10
  }

  test("a side with no levels recorded counts its deaths instead") {
    // Rows filed before victim_level existed. The death did happen; only the
    // level is unknown, so it weighs as one ordinary local.
    Bars.weigh(levels = 0, deaths = 4, referenceLevel = 300) shouldBe 4.0
  }

  test("a reference of nothing cannot divide by itself") {
    noException should be thrownBy Bars.weigh(levels = 400, deaths = 1, referenceLevel = 0)
  }

  // --- sizing a world -------------------------------------------------------

  test("a busier world needs more of a day to fill its bar") {
    Bars.ceilingFor(1100) should be > Bars.ceilingFor(60)
  }

  test("an all-but-empty world still has a ceiling to divide by") {
    Bars.ceilingFor(0) should be >= 2
    Bars.ceilingFor(3) should be >= 2
  }

  test("a world nothing has been recorded for falls back rather than vanishing") {
    val scale = Bars.Scale.forWorld(None, None)
    scale.ceiling shouldBe Bars.DefaultCeiling
    scale.referenceLevel shouldBe Bars.DefaultLevel.toDouble
  }

  test("a world with figures uses its own") {
    val scale = Bars.Scale.forWorld(Some(600.0), Some(280.0))
    scale.ceiling shouldBe Bars.ceilingFor(600)
    scale.referenceLevel shouldBe 280.0
  }

  test("an impossible average level falls back rather than dividing by it") {
    Bars.Scale.forWorld(Some(600.0), Some(0.0)).referenceLevel shouldBe Bars.DefaultLevel.toDouble
  }
}
