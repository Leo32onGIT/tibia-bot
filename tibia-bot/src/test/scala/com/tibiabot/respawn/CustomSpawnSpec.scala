package com.tibiabot.respawn

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** What a moderator may add to a guild's catalogue from the dashboard.
 *
 *  The rules are checked here rather than through the route because they are the
 *  same rules whichever way in is used, and because a refusal is a sentence
 *  somebody reads — the wording is part of the behaviour, not decoration.
 */
class CustomSpawnSpec extends AnyFunSuite with Matchers {

  private def fault(code: String, region: String = "Edron",
                    name: String = "Deep Cave", creature: String = "Orc Warlord") =
    RespawnService.spawnFault(code, region, name, creature)

  test("the four fields the bundled file carries are enough") {
    fault("415") shouldBe None
  }

  test("a city and a creature are both optional") {
    // The bundled file treats them that way too: no city groups a spawn under
    // "Elsewhere" and no creature means the card simply has no picture.
    fault("415", region = "", creature = "") shouldBe None
  }

  test("a spawn without a code or a name is refused") {
    fault("") should not be empty
    fault("415", name = "") should not be empty
  }

  test("a code stays to characters that mean the same thing everywhere") {
    // It travels in a URL and in a Discord thread title, and is typed by hand
    // into a claim box.
    fault("415a") shouldBe None
    fault("wyrm-hills") shouldBe None
    fault("415/../secret") should not be empty
    fault("415 a") should not be empty
    fault("<script>") should not be empty
  }

  test("nothing may be long enough to distort the board image") {
    // Every name is drawn in full on one shared picture, so one absurd entry is
    // paid for by everybody who opens it.
    fault("4" * 17) should not be empty
    fault("415", name = "n" * 61) should not be empty
    fault("415", region = "r" * 41) should not be empty
  }

  test("a creature with no fetchable picture is refused rather than silently dropped") {
    // Same rule the sprite path itself applies (CreatureSprites.safeFileName):
    // being told now beats wondering later why one card has no monster on it.
    fault("415", creature = "Orc Warlord") shouldBe None
    fault("415", creature = "Mooh'Tah Warrior") shouldBe None
    fault("415", creature = "../../etc/passwd") should not be empty
    fault("415", creature = "Orc.gif") should not be empty
  }

  test("a refusal says which value it is about") {
    // A form with four boxes and the message "invalid" is a form filled in wrong
    // twice.
    fault("415 a").value should include("415 a")
    fault("415", creature = "Orc.gif").value should include("Orc.gif")
  }

  private implicit class Value(fault: Option[String]) {
    def value: String = fault.getOrElse(fail("expected a refusal, got none"))
  }
}
