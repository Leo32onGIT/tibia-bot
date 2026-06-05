package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the multi-world list formatting used by the allies/hunted list output. */
class WorldListSpec extends AnyFunSuite with Matchers {

  test("each world's players are prefixed by a world header") {
    val out = WorldList.format(Map("Antica" -> List("a", "b")))
    out shouldBe List(":globe_with_meridians: **Antica** :globe_with_meridians:", "a", "b")
  }

  test("worlds are ordered alphabetically") {
    val out = WorldList.format(Map("Bona" -> List("b"), "Antica" -> List("a")))
    out.filter(_.contains(":globe_with_meridians:")) shouldBe List(
      ":globe_with_meridians: **Antica** :globe_with_meridians:",
      ":globe_with_meridians: **Bona** :globe_with_meridians:"
    )
    out.indexWhere(_.contains("Antica")) should be < out.indexWhere(_.contains("Bona"))
  }

  test("the 'Character does not exist' bucket is pushed to the end") {
    val out = WorldList.format(Map(
      "Character does not exist" -> List("ghost"),
      "Antica" -> List("a"),
      "Bona" -> List("b")
    ))
    out.last shouldBe "ghost"
    out.indexWhere(_.contains("Character does not exist")) should be > out.indexWhere(_.contains("Bona"))
  }

  test("an empty map yields an empty list") {
    WorldList.format(Map.empty) shouldBe Nil
  }

  // --- byWorld ---

  test("byWorld sorts a world's players by descending level") {
    val out = WorldList.byWorld(Map("knight" -> Seq(
      (100, "Antica", "k100"), (200, "Antica", "k200"), (150, "Antica", "k150"))))
    out("Antica") shouldBe List("k200", "k150", "k100")
  }

  test("byWorld orders vocations druid, knight, paladin, sorcerer, monk, none within a world") {
    val out = WorldList.byWorld(Map(
      "none" -> Seq((50, "W", "n")),
      "monk" -> Seq((50, "W", "m")),
      "sorcerer" -> Seq((50, "W", "s")),
      "paladin" -> Seq((50, "W", "p")),
      "knight" -> Seq((50, "W", "k")),
      "druid" -> Seq((50, "W", "d"))))
    out("W") shouldBe List("d", "k", "p", "s", "m", "n")
  }

  test("byWorld keeps worlds independent") {
    val out = WorldList.byWorld(Map("knight" -> Seq(
      (100, "Antica", "kA"), (90, "Belobra", "kB"))))
    out.keySet shouldBe Set("Antica", "Belobra")
    out("Antica") shouldBe List("kA")
    out("Belobra") shouldBe List("kB")
  }

  test("byWorld keeps input order for equal levels (stable)") {
    val out = WorldList.byWorld(Map("knight" -> Seq(
      (100, "W", "first"), (100, "W", "second"))))
    out("W") shouldBe List("first", "second")
  }

  test("byWorld ignores empty/missing vocations and yields an empty map for no entries") {
    WorldList.byWorld(Map.empty) shouldBe Map.empty
    WorldList.byWorld(Map("knight" -> Seq.empty)) shouldBe Map.empty
  }
}
