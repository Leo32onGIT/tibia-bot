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

  test("a known code keeps its city and a creature to draw it with") {
    // Deliberately says nothing about the spawn's *name*. Curating the list is an
    // ongoing job — 415 has already been renamed once — and a test that pins the
    // wording fails every time somebody improves it, which teaches people that a
    // red build is normal. The code, the city and having a creature are what the
    // rest of the system actually depends on.
    val known = RespawnCatalogue.seed.find(_.code == "415")
    known.map(_.region) shouldBe Some("Edron")
    known.map(_.name).exists(_.nonEmpty) shouldBe true
    known.map(_.creature).exists(_.nonEmpty) shouldBe true
  }

  test("no seed entry is left without a creature to draw it with") {
    // The board and every claim card fall back to a signpost image without one,
    // which is why this is worth knowing before a release rather than after.
    val unmapped = RespawnCatalogue.seed.filter(_.creature.trim.isEmpty).map(_.code)
    withClue(s"seed entries with no creature: ${unmapped.take(20).mkString(", ")} ") {
      unmapped should have size 0
    }
  }
}
