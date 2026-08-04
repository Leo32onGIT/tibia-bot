package com.tibiabot.presentation

import com.tibiabot.domain.RespawnClaim
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** The claim log's two-line entries, and the description budget behind them.
 *
 *  Discord rejects an embed whose description runs past 4096 characters, and a
 *  rejected embed is the whole interaction failing rather than a shortened log
 *  — so the point of most of this is showing a full page of the worst entries
 *  anyone could produce still fits with room to spare.
 */
class RespawnLogEmbedSpec extends AnyFunSuite with Matchers {

  private val now = ZonedDateTime.parse("2026-08-04T21:40:00Z")

  private def claim(character: String = "Bobinho", outcome: String = RespawnClaim.Outcome.Completed,
                    minutes: Int = 120, ended: Option[ZonedDateTime] = Some(now)) = RespawnClaim(
    id = 1L, respawnId = 7L, userId = "123456789012345678", userName = "bob", characterName = character,
    status = RespawnClaim.StatusFinished, queuePosition = 0, claimedAt = now, startsAt = Some(now),
    endsAt = Some(now), durationMinutes = minutes, warned = false, kind = RespawnClaim.KindAdHoc,
    limboUntil = None, offerExpiresAt = None, outcome = Some(outcome), endedAt = ended)

  test("an entry is two lines: when and where, then who and how it went") {
    val lines = RespawnEmbeds.logEntry(claim(), Some("415 Cult Orcs")).split("\n")
    lines should have size 2
    lines(0) should include("<t:")
    lines(0) should include("415 Cult Orcs")
    lines(1) should include("Bobinho")
    lines(1) should include("bob")
    lines(1) should include("ran its full time")
  }

  test("the person is named in plain text, not as a mention") {
    // A mention would need a REST lookup per entry to say anything a stored
    // row does not already, and renders as a pill rather than a name.
    val second = RespawnEmbeds.logEntry(claim(), None).split("\n")(1)
    second should include("Bobinho (bob)")
    second should not include "<@"
  }

  test("a spawn's own log leaves the name off, rather than repeating it every entry") {
    val lines = RespawnEmbeds.logEntry(claim(), None).split("\n")
    lines should have size 2
    lines(0) should not include "·"
    lines(1) should include("Bobinho")
  }

  test("the second line is indented with something Discord won't collapse") {
    // Ordinary leading spaces are stripped from an embed description, which
    // would flatten the two lines into one block.
    val second = RespawnEmbeds.logEntry(claim(), None).split("\n")(1)
    second.head should not be ' '
    second should startWith(" ")
  }

  test("someone with no character name is named by their username alone") {
    val second = RespawnEmbeds.logEntry(claim(character = ""), None).split("\n")(1)
    second should include("bob")
    second should not include "()"
  }

  test("a row too old to carry a username falls back to a mention rather than naming nobody") {
    // user_name arrived with a DEFAULT '' , so the earliest rows have none.
    val second = RespawnEmbeds
      .logEntry(claim(character = "").copy(userName = ""), None).split("\n")(1)
    second should include("<@123456789012345678>")
  }

  test("a character name survives a row with no username") {
    val second = RespawnEmbeds
      .logEntry(claim().copy(userName = ""), None).split("\n")(1)
    second should include("Bobinho")
    second should not include "("
  }

  test("a claim that never recorded an end says so rather than rendering a broken timestamp") {
    RespawnEmbeds.logEntry(claim(ended = None), None) should include("unknown time")
  }

  test("a full page of the worst realistic entries fits the description limit with room over") {
    // Long character name, long spawn name, and the longest outcome label there
    // is — ten of them, which is a full page.
    val worst = RespawnEmbeds.logEntry(
      claim(character = "Averyveryverylongcharactername", outcome = RespawnClaim.Outcome.Merged, minutes = 1439),
      Some("1904 Asura Citadel -1 (Marapur)"))
    val page = List.fill(10)(worst)
    val used = page.map(_.length + 1).sum
    withClue(s"a full page renders in $used characters: ")(used should be < 2000)
    RespawnEmbeds.entriesWithinLimit(page, 4096) should have size 10
  }

  test("entries past the budget are dropped from the end, keeping what is being read") {
    // The page is newest-first, so the survivors are the recent rows.
    val entries = List("a" * 40, "b" * 40, "c" * 40)
    RespawnEmbeds.entriesWithinLimit(entries, 90) shouldBe List("a" * 40, "b" * 40)
  }

  test("a budget too small for even one entry yields nothing rather than a partial row") {
    RespawnEmbeds.entriesWithinLimit(List("a" * 40), 10) shouldBe empty
  }

  private def onSpawn(respawnId: Long, endedAt: ZonedDateTime) =
    claim().copy(respawnId = respawnId, endedAt = Some(endedAt))

  test("groups are ordered by their most recent hunt, not by how many they hold") {
    // Spawn 2 has more hunts, but spawn 1 has the most recent one — so the top
    // of the page is still the last thing that happened.
    val claims = List(
      onSpawn(1L, now),
      onSpawn(2L, now.minusHours(1)),
      onSpawn(2L, now.minusHours(2)),
      onSpawn(2L, now.minusHours(3)))
    RespawnEmbeds.groupedByRespawn(claims).map(_._1) shouldBe List(1L, 2L)
  }

  test("a spawn's hunts stay newest-first within its group") {
    val claims = List(onSpawn(1L, now), onSpawn(1L, now.minusHours(4)), onSpawn(1L, now.minusHours(9)))
    val group = RespawnEmbeds.groupedByRespawn(claims).head._2
    group.flatMap(_.endedAt) shouldBe List(now, now.minusHours(4), now.minusHours(9))
  }

  test("a spawn appearing twice on a page folds into one group, not two") {
    // The feed arrives newest-first, so a spawn's hunts can be split by another
    // spawn's in between — folding has to gather both.
    val claims = List(onSpawn(1L, now), onSpawn(2L, now.minusHours(1)), onSpawn(1L, now.minusHours(2)))
    val grouped = RespawnEmbeds.groupedByRespawn(claims)
    grouped should have size 2
    grouped.head._1 shouldBe 1L
    grouped.head._2 should have size 2
  }

  test("a group names the spawn once and counts what it is showing") {
    val block = RespawnEmbeds.logGroup("415 Cult Orcs", List(onSpawn(1L, now), onSpawn(1L, now.minusHours(2))))
    val lines = block.split("\n")
    lines(0) shouldBe "**415 Cult Orcs · 2 hunts**"
    lines should have size 3
    lines.tail.foreach(line => line should not include "415 Cult Orcs")
  }

  test("a group of one says hunt, not hunts") {
    RespawnEmbeds.logGroup("415 Cult Orcs", List(onSpawn(1L, now))).split("\n")(0) shouldBe
      "**415 Cult Orcs · 1 hunt**"
  }

  test("a claim with no recorded end still sorts, rather than throwing the group order") {
    val claims = List(claim(ended = None).copy(respawnId = 3L), onSpawn(1L, now))
    // The dated group wins the top; the undated one is simply last.
    RespawnEmbeds.groupedByRespawn(claims).map(_._1) shouldBe List(1L, 3L)
  }
}
