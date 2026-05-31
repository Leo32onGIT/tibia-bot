package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the boss-tier emoji matcher. Config-free: categories are injected, so
 *  this verifies the lookup semantics (first match wins, case-insensitive
 *  input, trailing space, empty default) without loading Config. */
class BossEmojiSpec extends AnyFunSuite with Matchers {

  // lists are stored lower-cased, mirroring Config
  private val categories: Seq[(Seq[String], String)] = Seq(
    List("orshabaal", "ghazbaran") -> ":nemesis:",
    List("yeti", "midnight panther") -> ":archfoe:",
    List("crustacea gigantica") -> ":bane:"
  )

  test("returns the matching category's emoji with a trailing space") {
    BossEmoji.categoryEmoji("Orshabaal", categories) shouldBe ":nemesis: "
    BossEmoji.categoryEmoji("midnight panther", categories) shouldBe ":archfoe: "
  }

  test("matching is case-insensitive on the input name") {
    BossEmoji.categoryEmoji("GHAZBARAN", categories) shouldBe ":nemesis: "
  }

  test("a name in no category yields the empty string (no icon)") {
    BossEmoji.categoryEmoji("a dragon", categories) shouldBe ""
  }

  test("the first matching category wins, in declared order") {
    val overlapping: Seq[(Seq[String], String)] = Seq(
      List("rat") -> ":first:",
      List("rat") -> ":second:"
    )
    BossEmoji.categoryEmoji("rat", overlapping) shouldBe ":first: "
  }
}
