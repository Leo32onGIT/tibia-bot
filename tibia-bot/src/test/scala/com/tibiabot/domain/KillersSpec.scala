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

  test("joinNatural: no killers, one, two, and many") {
    Killers.joinNatural(Nil) shouldBe ""
    Killers.joinNatural(Seq("a dragon")) shouldBe "a dragon"
    Killers.joinNatural(Seq("a dragon", "a dragon lord")) shouldBe "a dragon and a dragon lord"
    Killers.joinNatural(Seq("a", "b", "c")) shouldBe "a, b and c"
  }
}
