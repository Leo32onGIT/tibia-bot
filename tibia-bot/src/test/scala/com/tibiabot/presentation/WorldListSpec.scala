package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins how the allies/hunted list orders its worlds and their players. */
class WorldListSpec extends AnyFunSuite with Matchers {

  private def order(worlds: Map[String, List[String]]): List[String] = WorldList.sorted(worlds).map(_._1)

  test("each world keeps its own lines") {
    WorldList.sorted(Map("Antica" -> List("a", "b"))) shouldBe List("Antica" -> List("a", "b"))
  }

  test("worlds are ordered alphabetically") {
    order(Map("Bona" -> List("b"), "Antica" -> List("a"))) shouldBe List("Antica", "Bona")
  }

  /** Both synthetic buckets say "we could not place this player on a world",
   *  which is the least interesting thing the list can say — so they follow
   *  every real world rather than sorting in among them by first letter. */
  test("the synthetic buckets are pushed to the end, contents and all") {
    val out = WorldList.sorted(Map("Not checked yet" -> List("unknown"), "Antica" -> List("a"), "Zuna" -> List("z")))
    out.last shouldBe ("Not checked yet" -> List("unknown"))
  }

  test("'Character does not exist' sorts last for the same reason") {
    order(Map("Character does not exist" -> List("ghost"), "Antica" -> List("a"), "Bona" -> List("b"))).last shouldBe
      "Character does not exist"
  }

  /** Ordering between the two synthetic buckets is alphabetical like any other,
   *  but both must still follow every real world. */
  test("both synthetic buckets follow every real world") {
    order(Map("Not checked yet" -> List("u"), "Character does not exist" -> List("g"), "Zuna" -> List("z"))) shouldBe
      List("Zuna", "Character does not exist", "Not checked yet")
  }

  test("an empty map yields an empty list") {
    WorldList.sorted(Map.empty) shouldBe Nil
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
