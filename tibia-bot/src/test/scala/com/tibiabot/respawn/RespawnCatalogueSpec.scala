package com.tibiabot.respawn

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Covers the bundled seed catalogue and the autocomplete ranking, both of
 *  which are pure and need no database or JDA. */
class RespawnCatalogueSpec extends AnyFunSuite with Matchers {

  test("the bundled seed catalogue parses and is non-trivial") {
    RespawnCatalogue.seed.size should be > 200
  }

  test("seed codes are unique — a duplicate would silently lose a spawn on import") {
    val duplicates = RespawnCatalogue.seed.groupBy(_.code).filter { case (_, entries) => entries.size > 1 }.keys
    withClue(s"duplicate codes: ${duplicates.mkString(", ")}") {
      duplicates shouldBe empty
    }
  }

  test("every seed entry has a code, region and name") {
    RespawnCatalogue.seed.filter(s => s.code.isEmpty || s.region.isEmpty || s.name.isEmpty) shouldBe empty
  }

  test("the reference spawn from the design is present and mapped to a creature") {
    val cultOrcs = RespawnCatalogue.seed.find(_.code == "415")
    cultOrcs.map(_.name) shouldBe Some("Cult Orcs")
    cultOrcs.map(_.region) shouldBe Some("Edron")
    cultOrcs.map(_.creature).exists(_.nonEmpty) shouldBe true
  }

  private val candidates = List(
    ("415", "Cult Orcs"),
    ("205", "Carlin Cults"),
    ("1415a", "Fury dungeon"),
    ("1401", "Oramond Marshes (Entrance/South)"),
    ("1402", "Oramond Camps (Northeast)"),
    ("806", "Hydra Mountain")
  )

  test("an exact code match outranks a code that merely contains it") {
    // "415" must not be buried under "1415a" — the person typing a code knows
    // exactly which spawn they mean.
    RespawnCatalogue.rankMatches(candidates, "415", 10).head shouldBe ("415", "Cult Orcs")
  }

  test("a code prefix matches every spawn beginning with it") {
    RespawnCatalogue.rankMatches(candidates, "14", 10).map(_._1) should contain allOf ("1401", "1402", "1415a")
  }

  test("a name prefix outranks a name substring") {
    // "Cult Orcs" starts with the input; "Carlin Cults" only contains it.
    RespawnCatalogue.rankMatches(candidates, "cult", 10).map(_._2) shouldBe
      List("Cult Orcs", "Carlin Cults")
  }

  test("matching is case-insensitive on both code and name") {
    RespawnCatalogue.rankMatches(candidates, "HYDRA", 10).map(_._1) shouldBe List("806")
    RespawnCatalogue.rankMatches(candidates, "1415A", 10).map(_._1) shouldBe List("1415a")
  }

  test("no match returns nothing rather than the whole catalogue") {
    RespawnCatalogue.rankMatches(candidates, "zzzz", 10) shouldBe empty
  }

  test("empty input lists everything in code order, so the first keystroke isn't a jumble") {
    RespawnCatalogue.rankMatches(candidates, "", 10).map(_._1) shouldBe
      List("205", "415", "806", "1401", "1402", "1415a")
  }

  test("results are capped at the requested limit — Discord rejects more than 25 choices") {
    RespawnCatalogue.rankMatches(candidates, "", 2) should have size 2
  }

  test("codes sort numerically, not lexically") {
    // Plain string ordering would put "1401" before "205".
    val ordered = RespawnCatalogue.rankMatches(candidates, "", 10).map(_._1)
    ordered.indexOf("205") should be < ordered.indexOf("1401")
  }
}
