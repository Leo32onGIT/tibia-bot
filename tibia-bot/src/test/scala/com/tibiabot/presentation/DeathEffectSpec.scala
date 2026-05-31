package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DeathEffectSpec extends AnyFunSuite with Matchers {

  test("environmental damage-type killers resolve to an effect animation") {
    DeathEffect.thumbnail("drowning")   shouldBe defined
    DeathEffect.thumbnail("death")      shouldBe defined
    DeathEffect.thumbnail("ice")        shouldBe defined
    DeathEffect.thumbnail("life drain") shouldBe defined
  }

  test("each mapped damage type returns the matching resource gif") {
    DeathEffect.thumbnail("death").get   should endWith ("Death_Effect.gif")
    DeathEffect.thumbnail("ice").get     should endWith ("Ice_Explosion_Effect.gif")
    DeathEffect.thumbnail("drowning").get should endWith ("Reaper_Effect.gif")
    DeathEffect.thumbnail("life drain").get should endWith ("Red_Sparkles_Effect.gif")
  }

  test("matching is case-insensitive on the killer name") {
    DeathEffect.thumbnail("Drowning")   shouldBe DeathEffect.thumbnail("drowning")
    DeathEffect.thumbnail("LIFE DRAIN") shouldBe DeathEffect.thumbnail("life drain")
  }

  test("real creature killers from the fixture fall back to the creature image (None)") {
    // mammoth / wyrm are the actual killer names in test/resources/tibiadata/character.json
    DeathEffect.thumbnail("mammoth") shouldBe None
    DeathEffect.thumbnail("wyrm")    shouldBe None
  }

  test("'pvp' is not a killer-name lookup — player kills are decided at the death site") {
    // "pvp" must NOT resolve via thumbnail(); it is a classification, never a killer name.
    DeathEffect.thumbnail("pvp") shouldBe None
    DeathEffect.pvp should endWith ("Phantasmal_Ooze.gif")
  }

  test("suicide animation constant is exposed for the empty-killer death path") {
    DeathEffect.suicide should endWith ("Ghost_Smoke_Effect.gif")
  }

  test("every mapped damage type is a real substance death source (no never-matching keys)") {
    // Killers.substanceSources is the author's canonical set of environmental
    // killer names; a DeathEffect key outside it could never match a real death.
    DeathEffect.mappedDamageTypes should not be empty
    DeathEffect.mappedDamageTypes.subsetOf(com.tibiabot.domain.Killers.substanceSources) shouldBe true
  }

  test("all effect resources share the resource base url") {
    val base = "https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/"
    DeathEffect.pvp should startWith (base)
    DeathEffect.suicide should startWith (base)
    Seq("death", "ice", "drowning", "life drain").foreach { k =>
      DeathEffect.thumbnail(k).get should startWith (base)
    }
  }
}
