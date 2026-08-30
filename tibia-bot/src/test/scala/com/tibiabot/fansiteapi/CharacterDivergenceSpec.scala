package com.tibiabot.fansiteapi

import com.tibiabot.fansiteapi.response.FansiteCharacterResponse
import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant

/** Shadow-mode comparison, whose only real job is to be readable.
 *
 *  The two sources are deliberately out of phase, so most differences between
 *  them are the design working rather than a bug. A comparison that reported
 *  all of them equally would be ignored within a day, which is the same as not
 *  having one. */
class CharacterDivergenceSpec extends AnyFunSuite with Matchers with FansiteJsonSupport {

  private val payload: FansiteCharacterResponse = {
    val is = getClass.getResourceAsStream("/fansiteapi/character_summon_death.json")
    require(is != null, "missing fixture")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString.parseJson.convertTo[FansiteCharacterResponse]
    finally is.close()
  }

  private val t0 = Instant.parse("2026-08-30T07:00:00Z")

  private def sheet(at: Instant): CharacterResponse = CharacterMapping.toCharacterResponse(payload, Some(at))

  private def withCharacter(base: CharacterResponse)(f: Character => Character): CharacterResponse =
    base.copy(character = base.character.copy(character = f(base.character.character)))

  test("identical sheets diverge in nothing") {
    CharacterDivergence.between(sheet(t0), sheet(t0)).isEmpty shouldBe true
  }

  test("a level that moved is drift, not a fault") {
    // Expected whenever the copies are of different ages, which is always.
    val d = CharacterDivergence.between(sheet(t0), withCharacter(sheet(t0.plusSeconds(150)))(_.copy(level = 14d)))
    d.stable shouldBe empty
    d.volatile.exists(_.startsWith("level")) shouldBe true
  }

  test("a field that cannot drift is a fault") {
    // Nothing about staleness can change a character's world, so a difference
    // here is a mapping bug and is worth a warning.
    val d = CharacterDivergence.between(sheet(t0), withCharacter(sheet(t0))(_.copy(world = "Somewhere Else")))
    d.stable.exists(_.startsWith("world")) shouldBe true
  }

  test("the origin skew is reported so a difference can be read in context") {
    CharacterDivergence.between(sheet(t0), sheet(t0.plusSeconds(180))).originSkewSeconds shouldBe 180L
  }

  test("a death only the newer copy has seen is not a divergence") {
    // The older copy was built before the death happened, so its absence there
    // is correct. Reporting it would flag the phase offset itself as a bug.
    val older = sheet(t0)
    val newer = sheet(t0.plusSeconds(300))
    val extraDeath = Deaths(
      time = t0.plusSeconds(200).toString, level = 13d,
      killers = List(Killers("a dragon", player = false, traded = false, summon = "")),
      assists = Nil, reason = "")
    val withNewDeath = newer.copy(character = newer.character.copy(
      deaths = Some(extraDeath :: newer.character.deaths.getOrElse(Nil))))

    CharacterDivergence.between(older, withNewDeath).stable shouldBe empty
  }

  test("a death both copies were built after, that only one has, is a fault") {
    // Both were built well after this death, so one of them losing it means the
    // mapping dropped it — exactly what shadow mode exists to catch.
    val older = sheet(t0.plusSeconds(600))
    val newer = sheet(t0.plusSeconds(900))
    val missed = Deaths(
      time = t0.plusSeconds(100).toString, level = 13d,
      killers = List(Killers("a dragon", player = false, traded = false, summon = "")),
      assists = Nil, reason = "")
    val withOldDeath = newer.copy(character = newer.character.copy(
      deaths = Some(missed :: newer.character.deaths.getOrElse(Nil))))

    CharacterDivergence.between(older, withOldDeath).stable.exists(_.startsWith("settled deaths")) shouldBe true
  }

  test("the description names the character and marks faults apart from drift") {
    val d = CharacterDivergence.between(
      sheet(t0),
      withCharacter(sheet(t0.plusSeconds(60)))(_.copy(world = "Elsewhere", level = 99d)))
    d.describe should include("Gatorbeug")
    d.describe should include("60s apart")
    d.describe should include("!world")
    d.describe should include("~level")
  }
}
