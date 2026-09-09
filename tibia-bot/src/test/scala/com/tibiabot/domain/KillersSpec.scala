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

  test("joinWithin: a list that fits is the natural join, untouched") {
    Killers.joinWithin(Seq("a", "b", "c"), 4065) shouldBe "a, b and c"
    Killers.joinWithin(Nil, 4065) shouldBe ""
    Killers.joinWithin(Seq("a dragon"), 4065) shouldBe "a dragon"
  }

  test("joinWithin: the entries that do not fit become a count") {
    // "aaaa, aaaa, aaaa, aaaa and aaaa" is 31 characters.
    Killers.joinWithin(Seq.fill(5)("aaaa"), 30) shouldBe "aaaa, aaaa, aaaa and 2 more"
  }

  test("joinWithin: an entry that fits is given up when its tail no longer does") {
    // The case that makes a first-overflow scan wrong: three entries are 16
    // characters and fit inside 24 on their own, but " and 2 more" beside them
    // does not — so the third has to go to make room for the tail.
    Killers.joinWithin(Seq.fill(5)("aaaa"), 24) shouldBe "aaaa, aaaa and 3 more"
    "aaaa, aaaa, aaaa".length should be <= 24
  }

  test("joinWithin: a killer entry is never cut in half") {
    // Real rendered entries — a cut inside one leaves a broken markdown link,
    // which Discord shows as raw text instead of a name.
    val parts = (1 to 100).map(i => s"**[Player Number$i [412]](https://www.tibia.com/community/?name=Player+Number$i)**")
    val fitted = Killers.joinWithin(parts, 4065)

    fitted.length should be <= 4065
    fitted.count(_ == '[') shouldBe fitted.count(_ == ']')
    val tail = fitted.substring(fitted.lastIndexOf(", ") + 2)
    tail should fullyMatch regex """.+ and \d+ more"""
    val kept = fitted.substring(0, fitted.lastIndexOf(", ")).split(", ").toSeq
    kept.foreach(entry => parts should contain(entry))
  }

  test("joinWithin: the count covers exactly the entries left out") {
    val parts = (1 to 100).map(i => s"**[Player Number$i [412]](https://www.tibia.com/community/?name=Player+Number$i)**")
    val fitted = Killers.joinWithin(parts, 4065)
    val hidden = """ and (\d+) more$""".r.findFirstMatchIn(fitted).map(_.group(1).toInt).getOrElse(0)

    hidden should be > 0
    fitted.split(", ").length + hidden shouldBe 100
  }

  test("joinWithin: a single entry too wide for the limit falls back to a count") {
    Killers.joinWithin(Seq("a" * 50), 20) shouldBe "1 killer"
    Killers.joinWithin(Seq.fill(3)("a" * 50), 20) shouldBe "3 killers"
  }

  test("exivaTargets: the highest levels, hardest first, and no more than five") {
    val killers = Seq("A" -> Some(100), "B" -> Some(500), "C" -> Some(300), "D" -> Some(200), "E" -> Some(400), "F" -> Some(50))
    Killers.exivaTargets(killers) shouldBe Seq("B", "E", "C", "D", "A")
    Killers.exivaTargets((1 to 30).map(i => (s"Player$i", Some(i)))) shouldBe
      Seq("Player30", "Player29", "Player28", "Player27", "Player26")
  }

  test("exivaTargets: a shorter killer list is ranked but never padded") {
    Killers.exivaTargets(Seq("Low" -> Some(80), "High" -> Some(410))) shouldBe Seq("High", "Low")
    Killers.exivaTargets(Nil) shouldBe empty
  }

  test("exivaTargets: a player and their summon are one name to exiva") {
    // Both are separate killer entries, and only one of them carries a level —
    // the person is listed once, at the level that did resolve.
    Killers.exivaTargets(Seq("Bubble" -> None, "Other" -> Some(300), "Bubble" -> Some(400))) shouldBe
      Seq("Bubble", "Other")
  }

  test("exivaTargets: a killer whose level never resolved sorts last") {
    Killers.exivaTargets(Seq("Unknown" -> None, "Known" -> Some(80))) shouldBe Seq("Known", "Unknown")
    // …but is still listed when there is room, rather than dropped.
    Killers.exivaTargets(Seq("Unknown" -> None)) shouldBe Seq("Unknown")
  }

  test("exivaTargets: equal levels keep the order the death reported them in") {
    Killers.exivaTargets(Seq("First" -> Some(300), "Second" -> Some(300), "Third" -> Some(300))) shouldBe
      Seq("First", "Second", "Third")
  }
}
