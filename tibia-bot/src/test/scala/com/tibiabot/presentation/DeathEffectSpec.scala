package com.tibiabot.presentation

import com.typesafe.config.{ConfigFactory, ConfigValueType}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._
import scala.util.Try

class DeathEffectSpec extends AnyFunSuite with Matchers {

  /** Every creature name the bot knows about, read straight out of mappings.conf.
   *
   *  [[com.tibiabot.Config]] itself cannot initialise here — it resolves
   *  discord.conf, which needs a populated environment — but this file is plain
   *  data with no substitutions in it, so it parses on its own.
   *
   *  Gathered from every list in the file rather than a named few, so adding a
   *  new creature list does not quietly narrow what this spec will accept.
   */
  private lazy val knownCreatures: Set[String] = {
    val mappings = ConfigFactory.parseResources("mappings.conf").getConfig("mapping-config")
    val entries = mappings.root().asScala.toList
    val fromLists = entries.flatMap {
      case (key, value) if value.valueType == ConfigValueType.LIST =>
        Try(mappings.getStringList(key).asScala.toList).getOrElse(Nil)
      case _ => Nil
    }
    val fromUrlMappings = Try(mappings.getObject("creature-url-mappings").asScala.keys.toList)
      .getOrElse(Nil)
    (fromLists ++ fromUrlMappings).map(_.toLowerCase.trim).toSet
  }

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

  test("every mapped effect is a killer something can actually die to (no never-matching keys)") {
    // A key is matched against the killer name exactly as TibiaData reports it
    // (TibiaBot passes it through untouched, lowercased here), so a key nothing
    // is ever called by can never fire — a silently dead animation rather than
    // anything that would show up as a failure in the wild. This is the guard
    // against a typo in the map.
    //
    // Two kinds of killer legitimately earn an effect of their own: an
    // environmental damage source, and a creature we would rather draw
    // ourselves than take from the wiki — "mushroom" is a boss summon, and its
    // entry is deliberate. It is *not* a substance source, and must not be
    // added to that set to satisfy this: Killers.sourceArticle reads the same
    // set to decide whether a death line says "killed by mushroom" or "killed by
    // a mushroom", and only the second is right for a creature.
    DeathEffect.mappedDamageTypes should not be empty
    val known = com.tibiabot.domain.Killers.substanceSources ++ knownCreatures
    withClue("keys matching no known killer: ") {
      DeathEffect.mappedDamageTypes.diff(known) shouldBe empty
    }
  }

  test("a damage type still reads as a substance, so its death line takes no article") {
    // The half of the old invariant worth keeping: anything here that *is* an
    // environmental source has to be one Killers agrees about, or the effect and
    // the wording of the line beside it would disagree.
    val substances = DeathEffect.mappedDamageTypes.intersect(com.tibiabot.domain.Killers.substanceSources)
    substances should contain allOf ("death", "ice", "drowning", "life drain")
    substances.foreach(name => com.tibiabot.domain.Killers.sourceArticle(name) shouldBe "")
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
