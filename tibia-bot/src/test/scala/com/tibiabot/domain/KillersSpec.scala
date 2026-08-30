package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the killer-name interpretation used to build death notifications.
 *  Names are taken verbatim from real Tibia death payloads. */
class KillersSpec extends AnyFunSuite with Matchers {

  test("parseSummon: a lowercase '<creature> of <player>' is a summon") {
    Killers.parseSummon("fire elemental of Violent Beams") shouldBe Some(("fire elemental", "Violent Beams"))
    Killers.parseSummon("a war golem of Xyz") shouldBe Some(("a war golem", "Xyz"))
  }

  test("parseSummon: a player whose NAME contains ' of ' is not a summon (leading word is capitalised)") {
    Killers.parseSummon("Knight of Flame") shouldBe None
    Killers.parseSummon("Lord of the Elements") shouldBe None
  }

  test("summonBehind: the summon field wins, with the killer name as the summoner") {
    // How both APIs actually report a summon kill: summoner in the name,
    // creature alongside it. This is the case that used to be missed entirely.
    Killers.summonBehind("Beams of Justice", "fire elemental") shouldBe Some(("fire elemental", "Beams of Justice"))
    Killers.summonBehind("Saanchez Style", "sorcerer familiar") shouldBe Some(("sorcerer familiar", "Saanchez Style"))
  }

  test("summonBehind: an ordinary player kill carries no summon") {
    Killers.summonBehind("Beams of Justice", "") shouldBe None
    Killers.summonBehind("Beams of Justice", "   ") shouldBe None
    Killers.summonBehind("Bubble", null) shouldBe None
  }

  test("summonBehind: falls back to the inline '<creature> of <player>' form") {
    Killers.summonBehind("fire elemental of Violent Beams", "") shouldBe Some(("fire elemental", "Violent Beams"))
  }

  test("summonBehind: a player whose name contains ' of ' is still not a summon") {
    // The guard that made the old name-parsing path safe has to survive.
    Killers.summonBehind("Knight of Flame", "") shouldBe None
  }

  test("summonBehind: the level shown beside a summon is the summoner's") {
    // levelLookupNames feeds the killer-level prefetch, and for a summon the
    // embed renders the summoner's level — so that is the name it must ask for.
    Killers.levelLookupNames("Victim", Seq(("Beams of Justice", true))) shouldBe Seq("Beams of Justice")
  }

  test("parseSummon: a plain creature or plain player name is not a summon") {
    Killers.parseSummon("a dragon lord") shouldBe None
    Killers.parseSummon("Bubble") shouldBe None
  }

  test("parseSummon: only the first ' of ' splits, so the summoner name is kept whole") {
    Killers.parseSummon("energy elemental of Sir of Camelot") shouldBe Some(("energy elemental", "Sir of Camelot"))
  }

  test("article: 'an' before a vowel, 'a' otherwise") {
    Killers.article("energy elemental") shouldBe "an"
    Killers.article("orshabaal") shouldBe "an"
    Killers.article("dragon lord") shouldBe "a"
    Killers.article("fire elemental") shouldBe "a"
  }

  test("sourceArticle: substance-like sources take no article") {
    Killers.sourceArticle("energy") shouldBe ""
    Killers.sourceArticle("fire") shouldBe ""
    Killers.sourceArticle("a trap") shouldBe ""
    Killers.sourceArticle("life drain") shouldBe ""
  }

  test("sourceArticle: real creatures keep their article and a trailing space") {
    Killers.sourceArticle("dragon lord") shouldBe "a "
    Killers.sourceArticle("orc berserker") shouldBe "an "
  }

  test("levelLookupNames takes player killers only — creatures have no level") {
    Killers.levelLookupNames("Victim", Seq(("Bubble", true), ("a dragon", false))) shouldBe Seq("Bubble")
  }

  test("levelLookupNames skips the victim's own 'self' entry") {
    Killers.levelLookupNames("Victim", Seq(("Victim", true), ("Bubble", true))) shouldBe Seq("Bubble")
  }

  test("levelLookupNames resolves a summon to its summoner") {
    Killers.levelLookupNames("Victim", Seq(("fire elemental of Bubble", true))) shouldBe Seq("Bubble")
  }

  test("levelLookupNames keeps a player whose name merely contains ' of '") {
    Killers.levelLookupNames("Victim", Seq(("Knight of Flame", true))) shouldBe Seq("Knight of Flame")
  }

  test("levelLookupNames is empty for a purely environmental death") {
    Killers.levelLookupNames("Victim", Seq(("energy", false), ("drowning", false))) shouldBe empty
  }

  test("joinNatural: no killers, one, two, and many") {
    Killers.joinNatural(Nil) shouldBe ""
    Killers.joinNatural(Seq("a dragon")) shouldBe "a dragon"
    Killers.joinNatural(Seq("a dragon", "a dragon lord")) shouldBe "a dragon and a dragon lord"
    Killers.joinNatural(Seq("a", "b", "c")) shouldBe "a, b and c"
  }
}
