package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The bars, drawn with readable stand-ins for the nine emoji so what comes out
 *  can be read as a string. */
class BarsSpec extends AnyFunSuite with Matchers {

  /** g/r/e for the colour, and the case says the shape: `G` a rounded start,
   *  `g` a middle, `Ǥ` a rounded end. */
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

  test("a bar is always the same number of segments") {
    List((9, 4), (47, 23), (31, 0), (0, 0), (1, 900)).foreach { case (left, right) =>
      withClue(s"$left v $right: ") {
        val bar = Bars.split(left, right, ink, segments = 12)
        (count(bar, 'g') + count(bar, 'r')) shouldBe 12
      }
    }
  }

  test("the run is rounded at both ends and square in between") {
    val bar = Bars.split(9, 4, ink, segments = 6)
    bar.head.isUpper shouldBe true
    bar should endWith("|")
    // exactly two capitals: the first piece and the last
    bar.count(_.isUpper) shouldBe 2
  }

  test("the split follows the two figures") {
    val bar = Bars.split(9, 3, ink, segments = 12)
    count(bar, 'g') shouldBe 9
    count(bar, 'r') shouldBe 3
  }

  test("a side that scored anything never disappears entirely") {
    // 31 against 1 rounds to nothing at twelve segments, and a bar that reads as
    // a clean sweep when somebody did die is a lie.
    val bar = Bars.split(31, 1, ink, segments = 12)
    count(bar, 'r') shouldBe 1
    count(bar, 'g') shouldBe 11
  }

  test("a side that scored nothing does disappear") {
    val bar = Bars.split(31, 0, ink, segments = 12)
    count(bar, 'r') shouldBe 0
    count(bar, 'g') shouldBe 12
  }

  test("a day with nothing on either side splits down the middle") {
    val bar = Bars.split(0, 0, ink, segments = 12)
    count(bar, 'g') shouldBe 6
    count(bar, 'r') shouldBe 6
  }

}
