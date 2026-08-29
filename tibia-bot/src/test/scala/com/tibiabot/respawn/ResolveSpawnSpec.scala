package com.tibiabot.respawn

import com.tibiabot.domain.Respawn
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Matching what somebody typed to a catalogue row.
 *
 *  Worth pinning in both directions. A match that is too eager sends a claim, a
 *  booking or a claim-log search to the wrong respawn without saying it guessed;
 *  one that is too strict makes people hunt for a code they can see on the board
 *  but cannot type their way to.
 */
class ResolveSpawnSpec extends AnyFunSuite with Matchers {

  private def spawn(id: Long, code: String, name: String, creature: String = "") =
    Respawn(id = id, code = code, name = name, creature = creature, region = "Carlin",
      world = "", mapperLink = "", threadId = "", source = Respawn.SourceSeed, addedBy = "seed")

  // The real shape of the awkward part of the catalogue: four rows sharing a
  // name, told apart only by a bracketed element.
  private val catalogue = List(
    spawn(1, "201", "Secret Library (Fire)", "Burning Book"),
    spawn(2, "202", "Secret Library (Energy)", "Energetic Book"),
    spawn(3, "203", "Secret Library (Ice)", "Icecold Book"),
    spawn(4, "204", "Secret Library (Earth)", "Cursed Book"),
    spawn(5, "205", "Carlin Cults", "Cult Enforcer"),
    spawn(6, "415", "Cult Orcs", "Orc Warlord")
  )

  private def resolve(query: String) = RespawnService.resolveIn(catalogue, query)

  test("an exact name wins outright, even where looser rules would be ambiguous") {
    resolve("Secret Library (Fire)").map(_.code) shouldBe Some("201")
    resolve("secret library (ice)").map(_.code) shouldBe Some("203")
  }

  test("the display name is matched, since autocomplete sends that shape back") {
    resolve("205 — Carlin Cults").map(_.code) shouldBe Some("205")
  }

  test("words in any order find the row a substring cannot") {
    // The point of the whole exercise: the words are the wrong way round and
    // have a bracket between them, so no substring of the name contains this.
    resolve("fire library").map(_.code) shouldBe Some("201")
    resolve("library earth").map(_.code) shouldBe Some("204")
  }

  test("the words may be split across the name and the creature") {
    // "Icecold Book" is the creature, "Secret Library (Ice)" the name.
    resolve("library icecold").map(_.code) shouldBe Some("203")
  }

  test("a query matching several rows is refused rather than guessed") {
    // Four Secret Libraries, so this cannot mean one of them.
    resolve("library") shouldBe None
    resolve("secret library") shouldBe None
    // Two rows say "cult", one by name and one by creature.
    resolve("cult") shouldBe None
  }

  test("a creature is matched when no name does") {
    resolve("Orc Warlord").map(_.code) shouldBe Some("415")
    resolve("warlord").map(_.code) shouldBe Some("415")
  }

  test("a substring of one name still resolves, as it always did") {
    resolve("Carlin Cults").map(_.code) shouldBe Some("205")
    resolve("orcs").map(_.code) shouldBe Some("415")
  }

  test("nothing recognisable stays unresolved") {
    resolve("") shouldBe None
    resolve("   ") shouldBe None
    resolve("dragon lair") shouldBe None
  }

  test("a row with no creature is never matched by the empty creature field") {
    // "" is a substring of everything, so an unset creature must not be treated
    // as one that matches whatever was typed.
    val sparse = List(spawn(1, "101", "Misguided Camp"), spawn(2, "102", "Extension Site"))
    RespawnService.resolveIn(sparse, "camp").map(_.code) shouldBe Some("101")
    RespawnService.resolveIn(sparse, "anything else").map(_.code) shouldBe None
  }

  // ---- resolveAmong: the same question asked of rows already in hand --------
  //
  // The dashboard's calendar resolves this way rather than going back to the
  // database per request, so what it answers has to be what `resolve` answers —
  // the code first, and the ladder above only where the code misses.

  test("the code is tried before anything else, as the database lookup was") {
    RespawnService.resolveAmong(catalogue, "415").map(_.name) shouldBe Some("Cult Orcs")
    RespawnService.resolveAmong(catalogue, "201").map(_.name) shouldBe Some("Secret Library (Fire)")
  }

  test("a code is matched without regard to case, as LOWER(code) = LOWER(?) was") {
    val lettered = List(spawn(1, "Ab1", "Somewhere"))
    RespawnService.resolveAmong(lettered, "aB1").map(_.name) shouldBe Some("Somewhere")
  }

  test("what the code misses falls through to the same ladder") {
    RespawnService.resolveAmong(catalogue, "fire library").map(_.code) shouldBe Some("201")
    RespawnService.resolveAmong(catalogue, "205 — Carlin Cults").map(_.code) shouldBe Some("205")
  }

  test("nothing matches nothing") {
    RespawnService.resolveAmong(catalogue, "").map(_.code) shouldBe None
    RespawnService.resolveAmong(catalogue, "   ").map(_.code) shouldBe None
    RespawnService.resolveAmong(catalogue, "999").map(_.code) shouldBe None
    RespawnService.resolveAmong(Nil, "415").map(_.code) shouldBe None
  }
}
