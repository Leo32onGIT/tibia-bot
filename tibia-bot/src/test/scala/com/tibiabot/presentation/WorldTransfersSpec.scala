package com.tibiabot.presentation

import com.tibiabot.domain.WorldTransfer
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class WorldTransfersSpec extends AnyFunSuite with Matchers {

  private val t = ZonedDateTime.parse("2026-05-30T10:00:00Z")
  private def record(name: String, worlds: List[String], at: ZonedDateTime = t) =
    WorldTransfer(name.toLowerCase, worlds, at)

  test("sources: a former world on the world we are polling is an arrival") {
    WorldTransfers.sources("Antica", "Antica", List("Nefera")) shouldBe List("Nefera")
  }

  test("sources: no former world is not an arrival") {
    WorldTransfers.sources("Antica", "Antica", Nil) shouldBe Nil
  }

  test("sources: a character who has since transferred out is not an arrival here") {
    // Still in the recently-online set for this world, but the sheet says they left.
    WorldTransfers.sources("Bona", "Antica", List("Nefera")) shouldBe Nil
  }

  test("sources drops blanks, whitespace and this world itself") {
    WorldTransfers.sources("Antica", "Antica", List("", "  ", " Nefera ", "antica")) shouldBe List("Nefera")
  }

  test("sources keeps several former worlds, deduplicated") {
    WorldTransfers.sources("Antica", "Antica", List("Nefera", "Bona", "Nefera")) shouldBe List("Nefera", "Bona")
  }

  test("unreported announces a transfer this guild has not posted") {
    WorldTransfers.unreported("Antica", "Antica", List("Nefera"), None) shouldBe Some(List("Nefera"))
  }

  test("unreported stays quiet for a transfer already posted") {
    WorldTransfers.unreported("Antica", "Antica", List("Nefera"), Some(List("Nefera"))) shouldBe None
  }

  test("unreported ignores case and ordering when comparing against what was posted") {
    WorldTransfers.unreported("Antica", "Antica", List("Bona", "nefera"), Some(List("Nefera", "bona"))) shouldBe None
  }

  test("unreported announces a second, different transfer by the same character") {
    WorldTransfers.unreported("Antica", "Antica", List("Nefera", "Bona"), Some(List("Nefera"))) shouldBe
      Some(List("Nefera", "Bona"))
  }

  test("unreported announces again once a former world has cleared and a new one appears") {
    WorldTransfers.unreported("Antica", "Antica", List("Bona"), Some(List("Nefera"))) shouldBe Some(List("Bona"))
  }

  test("unreported stays quiet when there is nothing to announce, posted or not") {
    WorldTransfers.unreported("Antica", "Antica", Nil, None) shouldBe None
    WorldTransfers.unreported("Antica", "Antica", Nil, Some(List("Nefera"))) shouldBe None
    WorldTransfers.unreported("Bona", "Antica", List("Nefera"), None) shouldBe None
  }

  test("sourceText reads as a phrase for one, two or more worlds") {
    WorldTransfers.sourceText(List("Nefera")) shouldBe "Nefera"
    WorldTransfers.sourceText(List("Nefera", "Bona")) shouldBe "Nefera and Bona"
    WorldTransfers.sourceText(List("Nefera", "Bona", "Antica")) shouldBe "Nefera, Bona and Antica"
  }

  test("the arrow follows the side: hunted red, allied green, stranger grey") {
    WorldTransfers.side(hunted = true, allied = false) shouldBe WorldTransfers.Side.Hunted
    WorldTransfers.side(hunted = false, allied = true) shouldBe WorldTransfers.Side.Allied
    WorldTransfers.side(hunted = false, allied = false) shouldBe WorldTransfers.Side.Neutral
  }

  test("somebody on both lists arrives as hunted, as they read everywhere else") {
    WorldTransfers.side(hunted = true, allied = true) shouldBe WorldTransfers.Side.Hunted
  }

  test("postedFor finds the record a character was announced under before renaming") {
    val records = List(record("Rodzeraah", List("Unebra")))
    WorldTransfers.postedFor(records, "Chris Rpbombita", List("Rodzeraah")).map(_.formerWorlds) shouldBe
      Some(List("Unebra"))
  }

  test("a renamed character's already-announced transfer is not posted a second time") {
    // The bug this pairing exists for: the sheet still lists Unebra for months after
    // the move, so the arrival is re-detected under every name the character takes.
    val records = List(record("Rodzeraah", List("Unebra")))
    val posted = WorldTransfers.postedFor(records, "Chris Rpbombita", List("Rodzeraah"))
    WorldTransfers.unreported("Antica", "Antica", List("Unebra"), posted.map(_.formerWorlds)) shouldBe None
  }

  test("a renamed character's *new* transfer is still announced") {
    val records = List(record("Rodzeraah", List("Unebra")))
    val posted = WorldTransfers.postedFor(records, "Chris Rpbombita", List("Rodzeraah"))
    WorldTransfers.unreported("Antica", "Antica", List("Unebra", "Bona"), posted.map(_.formerWorlds)) shouldBe
      Some(List("Unebra", "Bona"))
  }

  test("postedFor prefers the record under the live name over one under a former name") {
    val records = List(record("Rodzeraah", List("Unebra")), record("Chris Rpbombita", List("Unebra", "Bona")))
    WorldTransfers.postedFor(records, "Chris Rpbombita", List("Rodzeraah")).map(_.formerWorlds) shouldBe
      Some(List("Unebra", "Bona"))
  }

  test("postedFor takes the most recent when two former names both have records") {
    val records = List(
      record("Rodzeraah", List("Unebra"), t),
      record("Middlename", List("Unebra", "Bona"), t.plusDays(30))
    )
    WorldTransfers.postedFor(records, "Chris Rpbombita", List("Rodzeraah", "Middlename")).map(_.formerWorlds) shouldBe
      Some(List("Unebra", "Bona"))
  }

  test("postedFor finds nothing for a character who has never been announced") {
    WorldTransfers.postedFor(List(record("Someoneelse", List("Unebra"))), "Chris Rpbombita", List("Rodzeraah")) shouldBe None
    WorldTransfers.postedFor(Nil, "Chris Rpbombita", Nil) shouldBe None
  }

  test("staleKeys names only the dropped keys, ignoring case, blanks and a name taken back") {
    val records = List(record("Rodzeraah", List("Unebra")), record("Chris Rpbombita", List("Unebra")))
    WorldTransfers.staleKeys(records, "Chris Rpbombita", List("rodzeraah", "", "  ", "CHRIS RPBOMBITA")) shouldBe
      List("rodzeraah")
    // Nothing to move when the record is already under the live name.
    WorldTransfers.staleKeys(records, "Chris Rpbombita", Nil) shouldBe Nil
  }

  test("applyRename moves the record onto the live name, carrying worlds and time across") {
    val moved = WorldTransfers.applyRename(
      List(record("Rodzeraah", List("Unebra"))), "Chris Rpbombita", List("Rodzeraah"))
    moved shouldBe List(WorldTransfer("chris rpbombita", List("Unebra"), t))
  }

  test("applyRename collapses several stale rows and keeps the live one when there is one") {
    val records = List(
      record("Rodzeraah", List("Unebra"), t),
      record("Middlename", List("Unebra"), t.plusDays(30)),
      record("Chris Rpbombita", List("Unebra", "Bona"), t.plusDays(60)),
      record("Unrelated", List("Nefera"))
    )
    val moved = WorldTransfers.applyRename(records, "Chris Rpbombita", List("Rodzeraah", "Middlename"))
    moved should contain theSameElementsAs List(
      WorldTransfer("chris rpbombita", List("Unebra", "Bona"), t.plusDays(60)),
      record("Unrelated", List("Nefera"))
    )
  }

  test("applyRename leaves an untouched list alone when nothing is stale") {
    val records = List(record("Chris Rpbombita", List("Unebra")), record("Unrelated", List("Nefera")))
    WorldTransfers.applyRename(records, "Chris Rpbombita", Nil) shouldBe records
    WorldTransfers.applyRename(records, "Chris Rpbombita", List("Chris Rpbombita")) shouldBe records
  }
}
