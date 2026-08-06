package com.tibiabot.respawn

import com.tibiabot.domain.RespawnClaim
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** The paging arithmetic behind the moderator claim log. Getting this wrong
 *  either hides history that exists or offers an Older button that turns up
 *  nothing, so both directions are pinned here.
 */
class LogPageSpec extends AnyFunSuite with Matchers {

  private val now = ZonedDateTime.parse("2026-08-04T12:00:00Z")

  private def claim(id: Long) = RespawnClaim(
    id = id, respawnId = 1L, userId = s"u$id", userName = s"User $id", characterName = "",
    status = RespawnClaim.StatusFinished, queuePosition = 0, claimedAt = now, startsAt = Some(now),
    endsAt = Some(now.plusHours(1)), durationMinutes = 60, warned = false,
    kind = RespawnClaim.KindAdHoc, limboUntil = None, offerExpiresAt = None,
    outcome = Some(RespawnClaim.Outcome.Completed), endedAt = Some(now.plusHours(1)))

  test("a full page with more behind it offers Older") {
    val page = LogPage(entries = (1L to 10L).map(claim).toList, page = 0, hasOlder = true)
    page.hasOlder shouldBe true
    page.hasNewer shouldBe false // nothing newer than the first page
    page.isEmpty shouldBe false
  }

  test("the first page never offers Newer, and a later one always does") {
    LogPage(List(claim(1)), page = 0, hasOlder = false).hasNewer shouldBe false
    LogPage(List(claim(1)), page = 1, hasOlder = false).hasNewer shouldBe true
    LogPage(List(claim(1)), page = 4, hasOlder = false).hasNewer shouldBe true
  }

  test("an empty log is empty rather than a page of nothing") {
    val page = LogPage(Nil, page = 0, hasOlder = false)
    page.isEmpty shouldBe true
    page.hasOlder shouldBe false
    page.hasNewer shouldBe false
  }

  test("a page that ends the trail offers neither direction onward") {
    // Both false is the "that's everything" case the footer reports.
    val page = LogPage((1L to 3L).map(claim).toList, page = 0, hasOlder = false)
    page.hasOlder shouldBe false
    page.hasNewer shouldBe false
  }
}
