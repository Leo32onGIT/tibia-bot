package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CreatureWikiSpec extends AnyFunSuite with Matchers {

  /** A slice of the wiki's list, spelled as the wiki spells it. */
  private val wiki = new CreatureWiki(List(
    "Flimsy Lost Soul", "Grim Reaper", "Sineater Inferniarch", "Boar Man", "War Wolf",
    "Norcferatu Heartless", "Plunder Patriarch", "Acolyte of the Cult", "Harpy",
    "Hero", "Medusa", "Muglex Clan Feetman", "Amarie (Creature)"
  ))

  private def page(race: String) = wiki.titleFor(race)

  test("a race reported in the plural finds the page named in the singular") {
    page("flimsy lost souls") shouldBe Some("Flimsy Lost Soul")
    page("grim reapers") shouldBe Some("Grim Reaper")
  }

  test("the endings Tibia actually uses all resolve") {
    page("sineater inferniarches") shouldBe Some("Sineater Inferniarch")  // ch -> ches
    page("norcferatu heartlesses") shouldBe Some("Norcferatu Heartless")  // s  -> ses
    page("plunder patriarches") shouldBe Some("Plunder Patriarch")        // ch -> ches
    page("boar men") shouldBe Some("Boar Man")                            // man -> men
    page("war wolves") shouldBe Some("War Wolf")                          // f  -> ves
    page("harpies") shouldBe Some("Harpy")                                // y  -> ies
    page("heroes") shouldBe Some("Hero")                                  // o  -> oes
    page("medusae") shouldBe Some("Medusa")                               // a  -> ae
  }

  test("the plural can be on the head of the name rather than its tail") {
    page("acolytes of the cult") shouldBe Some("Acolyte of the Cult")
  }

  test("a race the endpoint reports in the singular still resolves") {
    page("muglex clan feetman") shouldBe Some("Muglex Clan Feetman")
  }

  test("the lookup does not care how the endpoint cased it") {
    page("GRIM REAPERS") shouldBe Some("Grim Reaper")
    page("  grim reapers  ") shouldBe Some("Grim Reaper")
  }

  test("a race nothing on the list pluralises to is left alone") {
    page("cyclopes") shouldBe None
    page("sabreteeth") shouldBe None
    page("players") shouldBe None
    wiki.urlFor("cyclopes") shouldBe None
  }

  test("a form two pages both claim is dropped rather than guessed between") {
    // Fox pluralises to foxes; so does Foxe. Neither page gets the link.
    val ambiguous = new CreatureWiki(List("Fox", "Foxe"))
    ambiguous.titleFor("foxes") shouldBe None
    ambiguous.titleFor("fox") shouldBe Some("Fox")
  }

  test("a page really called that beats another page's guess at it") {
    val both = new CreatureWiki(List("Hunter", "Hunters"))
    both.titleFor("hunters") shouldBe Some("Hunters")
  }

  test("parentheses are escaped, so a disambiguated title survives as a link") {
    val url = wiki.urlFor("amarie (creature)")
    url shouldBe Some("https://tibia.fandom.com/wiki/Amarie_%28Creature%29")
    // Discord ends a link at the first ')', so an unescaped one breaks the row.
    url.get should not include ")"
  }

  test("a page name becomes the wiki's own URL") {
    wiki.urlFor("grim reapers") shouldBe Some("https://tibia.fandom.com/wiki/Grim_Reaper")
    CreatureWiki.urlForTitle("Goshnar's Megalomania") shouldBe
      "https://tibia.fandom.com/wiki/Goshnar's_Megalomania"
  }

  test("a hand-kept override reaches a page no ending could pluralise into") {
    // "cyclopes" is not "cyclops" plus a suffix, so only the table gets there.
    val withTable = new CreatureWiki(List("Grim Reaper"), Map("cyclopes" -> "Cyclops"))
    withTable.titleFor("cyclopes") shouldBe Some("Cyclops")
    withTable.urlFor("cyclopes") shouldBe Some("https://tibia.fandom.com/wiki/Cyclops")
    // Still keyed loosely, like everything else the endpoint reports.
    withTable.titleFor("CYCLOPES") shouldBe Some("Cyclops")
    new CreatureWiki(List("Grim Reaper")).titleFor("cyclopes") shouldBe None
  }

  test("an override outranks both a spelled-out title and a guessed plural") {
    val table = Map("monks" -> "Monk (Creature)")
    new CreatureWiki(List("Monk", "Monks"), table).titleFor("monks") shouldBe Some("Monk (Creature)")
  }

  test("an override onto a disambiguated page still prints the reported race") {
    // The title has a word the race does not, so the casing falls back rather
    // than trying to line "Avalanche" up against "Avalanche (Creature)".
    CreatureWiki.casedLike("avalanche", "Avalanche (Creature)") shouldBe "Avalanche"
    CreatureWiki.casedLike("cyclopes drone", "Cyclops Drone") shouldBe "Cyclopes Drone"
  }

  test("the matched page decides the casing, which the regex alone cannot") {
    // Same punctuation, opposite answers, and only the page title knows which.
    CreatureWiki.casedLike("druid's apparitions", "Druid's Apparition") shouldBe "Druid's Apparitions"
    CreatureWiki.casedLike("mooh'tah warriors", "Mooh'Tah Warrior") shouldBe "Mooh'Tah Warriors"
    Urls.titleCase("druid's apparitions") shouldBe "Druid'S Apparitions"
  }

  test("casing follows the title word for word, including the ones it keeps down") {
    CreatureWiki.casedLike("acolytes of the cult", "Acolyte of the Cult") shouldBe "Acolytes of the Cult"
    CreatureWiki.casedLike("minions of Gaz'haragoth", "Minion of Gaz'haragoth") shouldBe
      "Minions of Gaz'haragoth"
    CreatureWiki.casedLike("flimsy lost souls", "Flimsy Lost Soul") shouldBe "Flimsy Lost Souls"
  }

  test("a title that does not line up word for word falls back to the plain rules") {
    CreatureWiki.casedLike("some unexpected shape here", "Short Title") shouldBe "Some Unexpected Shape Here"
  }

  test("es is only offered where English offers it, which is what keeps slimes unambiguous") {
    CreatureWiki.pluralForms("Slim") should not contain "Slimes"
    CreatureWiki.pluralForms("Slime") should contain("Slimes")
    new CreatureWiki(List("Slim", "Slime")).titleFor("slimes") shouldBe Some("Slime")
  }
}
