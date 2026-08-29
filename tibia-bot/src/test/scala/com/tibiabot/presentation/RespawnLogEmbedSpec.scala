package com.tibiabot.presentation

import com.tibiabot.domain.RespawnClaim
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** The claim log's rows, and the description budget behind them.
 *
 *  One layout for every scope: a bold spawn name, then a line per hunt beneath
 *  it. A spawn's own log is that same shape with a single group in it, which is
 *  why these all go through [[RespawnEmbeds.logGroup]] — there is no second
 *  renderer left to test.
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

  // One hunt's line, as the feed draws it. Every scope reaches this same row,
  // so these are the assertions that used to be split across two renderers.
  private def row(c: RespawnClaim, spawn: String = "415 Cult Orcs"): String =
    RespawnEmbeds.logGroup(spawn, List(c)).split("\n")(1)

  test("a hunt is one line: when it ended, then who had it and how it went") {
    val lines = RespawnEmbeds.logGroup("415 Cult Orcs", List(claim())).split("\n")
    lines should have size 2
    lines(0) shouldBe "**415 Cult Orcs**"
    lines(1) should include("<t:")
    lines(1) should include("Bobinho")
    lines(1) should include("bob")
    lines(1) should include("ran its full time")
  }

  test("the spawn is named on the header, never on the hunts under it") {
    // What the two-line entry existed to avoid, solved by the header instead:
    // the name is written once however many hunts follow it.
    val lines = RespawnEmbeds
      .logGroup("415 Cult Orcs", List(claim(), claim(), claim())).split("\n")
    lines should have size 4
    lines.tail.foreach(line => line should not include "415 Cult Orcs")
  }

  test("the person is named in plain text, not as a mention") {
    // A mention would need a REST lookup per entry to say anything a stored
    // row does not already, and renders as a pill rather than a name.
    row(claim()) should include("**Bobinho (bob)**")
    row(claim()) should not include "<@"
  }

  test("a hunt's line is indented with something Discord won't collapse") {
    // A non-breaking space, U+00A0, asserted as the codepoint rather than by
    // pasting one into a string where the next reader cannot tell it from an
    // ordinary space. Ordinary leading whitespace is stripped from an embed
    // description, which would flatten every row into the header above it.
    row(claim()).head shouldBe ' '
    row(claim()).head should not be ' '
  }

  test("someone with no character name is named by their username alone") {
    row(claim(character = "")) should include("bob")
    row(claim(character = "")) should not include "()"
  }

  test("a row too old to carry a username says so rather than showing a raw id") {
    // user_name arrived with a DEFAULT '', so the earliest rows have none. This
    // used to fall back to a mention; nothing in the log is a mention any more,
    // and an id on its own tells a reader nothing, so the line owns up instead.
    val line = row(claim(character = "").copy(userName = ""))
    line should include("someone")
    line should not include "123456789012345678"
    line should not include "<@"
  }

  test("a character name survives a row with no username") {
    val line = row(claim().copy(userName = ""))
    line should include("Bobinho")
    line should not include "("
  }

  test("a claim that never recorded an end says so rather than rendering a broken timestamp") {
    row(claim(ended = None)) should include("unknown time")
  }

  test("a full page of the worst realistic entries fits the description limit with room over") {
    // Long character name, long spawn name, and the longest outcome label there
    // is — ten of them, which is a full page, and each in its own group so the
    // header is paid for every time rather than shared.
    val worst = RespawnEmbeds.logGroup("1904 Asura Citadel -1 (Marapur)", List(
      claim(character = "Averyveryverylongcharactername",
        outcome = RespawnClaim.Outcome.Merged, minutes = 1439)))
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

  test("back-to-back hunts on one spawn fold into a single run") {
    val claims = List(onSpawn(1L, now), onSpawn(1L, now.minusHours(1)), onSpawn(1L, now.minusHours(2)))
    val runs = RespawnEmbeds.collapsedRuns(claims)
    runs should have size 1
    runs.head._2 should have size 3
  }

  test("the page stays newest-first — a run never pulls older hunts up the page") {
    // The point of folding runs rather than gathering every hunt for a spawn:
    // spawn 1's 15:00 hunt must stay below spawn 2's 20:00 one, not jump up
    // under spawn 1's 21:00.
    val claims = List(
      onSpawn(1L, now.withHour(21)),
      onSpawn(2L, now.withHour(20)),
      onSpawn(1L, now.withHour(15)))
    val runs = RespawnEmbeds.collapsedRuns(claims)
    runs.map(_._1) shouldBe List(1L, 2L, 1L)
    runs.flatMap(_._2).flatMap(_.endedAt).map(_.getHour) shouldBe List(21, 20, 15)
  }

  test("a spawn interrupted and returned to appears twice, rather than being gathered") {
    val claims = List(onSpawn(1L, now), onSpawn(2L, now.minusHours(1)), onSpawn(1L, now.minusHours(2)))
    RespawnEmbeds.collapsedRuns(claims).map(r => (r._1, r._2.size)) shouldBe List((1L, 1), (2L, 1), (1L, 1))
  }

  test("hunts keep their order inside a run") {
    val claims = List(onSpawn(1L, now), onSpawn(1L, now.minusHours(4)), onSpawn(1L, now.minusHours(9)))
    RespawnEmbeds.collapsedRuns(claims).head._2.flatMap(_.endedAt) shouldBe
      List(now, now.minusHours(4), now.minusHours(9))
  }

  test("a page with no repeats folds nothing and reads exactly as it did") {
    val claims = List(onSpawn(1L, now), onSpawn(2L, now.minusHours(1)), onSpawn(3L, now.minusHours(2)))
    RespawnEmbeds.collapsedRuns(claims).map(_._2.size) shouldBe List(1, 1, 1)
  }

  test("a run names the spawn once, on its own header line") {
    val block = RespawnEmbeds.logGroup("415 Cult Orcs", List(onSpawn(1L, now), onSpawn(1L, now.minusHours(2))))
    val lines = block.split("\n")
    lines(0) shouldBe "**415 Cult Orcs**"
    lines should have size 3
    lines.tail.foreach(line => line should not include "415 Cult Orcs")
  }

  test("the person is bold on every line, so a scan lands on the name") {
    val lines = RespawnEmbeds.logGroup("415 Cult Orcs", List(onSpawn(1L, now))).split("\n")
    lines(1) should include("**Bobinho (bob)**")
    // The duration and outcome after it stay plain — bolding those would leave
    // nothing for the eye to catch on.
    lines(1) should include("· 2h · ran its full time")
  }

  test("an empty page folds to nothing rather than an empty run") {
    RespawnEmbeds.collapsedRuns(Nil) shouldBe empty
  }
}
