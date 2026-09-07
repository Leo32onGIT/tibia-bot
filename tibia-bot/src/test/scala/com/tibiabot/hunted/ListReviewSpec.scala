package com.tibiabot.hunted

import com.tibiabot.domain.Players
import com.tibiabot.hunted.ListReview.Finding
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The rules that decide a listed entry has outlived what it was for.
 *
 *  Worth pinning tightly: getting the traded one backwards proposes deleting
 *  entries somebody added deliberately, and the flag it reads decays upstream, so
 *  a mistake here cannot be corrected by looking again later.
 */
class ListReviewSpec extends AnyFunSuite with Matchers {

  private val tracked = Set("Antica", "Belobra")

  private def entry(tradedWhenAdded: Boolean = false, flagged: String = "") =
    Players("bubble", "false", "none", "someone", tradedWhenAdded, flagged)

  // --- traded ---

  test("a player who becomes traded after being added is flagged") {
    ListReview.review(entry(), traded = true, "Antica", tracked) shouldBe Some(Finding.Traded)
  }

  /** The safety rule. Somebody added them knowing, and the bot has no business
   *  second-guessing that however long they stay listed. */
  test("a player already traded when added is never flagged for it") {
    ListReview.review(entry(tradedWhenAdded = true), traded = true, "Antica", tracked) shouldBe None
  }

  test("a player who is not traded is not flagged") {
    ListReview.review(entry(), traded = false, "Antica", tracked) shouldBe None
  }

  /** The flag decays upstream — a rename clears it. That must not read as
   *  anything happening. */
  test("an already-traded player whose flag later clears is still not flagged") {
    ListReview.review(entry(tradedWhenAdded = true), traded = false, "Antica", tracked) shouldBe None
  }

  // --- world ---

  test("a player on a world the server does not track is flagged") {
    ListReview.review(entry(), traded = false, "Vunira", tracked) shouldBe Some(Finding.MovedWorld("Vunira"))
  }

  test("a player on any tracked world is not flagged, whichever one") {
    ListReview.review(entry(), traded = false, "Antica", tracked) shouldBe None
    ListReview.review(entry(), traded = false, "Belobra", tracked) shouldBe None
  }

  test("world matching ignores case") {
    ListReview.review(entry(), traded = false, "aNTiCa", tracked) shouldBe None
  }

  /** A sheet that did not say where they are is not evidence they left. */
  test("an empty world is never a finding") {
    ListReview.review(entry(), traded = false, "", tracked) shouldBe None
  }

  /** A guild mid-setup, or one whose worlds were all removed. Flagging everybody
   *  for having left a set of no worlds is the worst reading of that. */
  test("a server tracking no worlds flags nobody") {
    ListReview.review(entry(), traded = false, "Vunira", Set.empty) shouldBe None
  }

  // --- one-shot ---

  test("an entry already flagged is not flagged again") {
    ListReview.review(entry(flagged = "traded"), traded = true, "Vunira", tracked) shouldBe None
    ListReview.review(entry(flagged = "world"), traded = true, "Vunira", tracked) shouldBe None
  }

  /** Both at once: the account changed hands, which a move back cannot undo. */
  test("traded wins over a world move when both apply") {
    ListReview.review(entry(), traded = true, "Vunira", tracked) shouldBe Some(Finding.Traded)
  }

  test("the reasons are the strings the database stores") {
    Finding.Traded.reason shouldBe "traded"
    Finding.MovedWorld("Vunira").reason shouldBe "world"
  }
}
