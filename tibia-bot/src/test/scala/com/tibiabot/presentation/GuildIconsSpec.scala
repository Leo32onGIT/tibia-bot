package com.tibiabot.presentation

import com.tibiabot.presentation.GuildIcons.Relation._
import com.tibiabot.presentation.GuildIcons.{ListRelation, classifyList}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the priority ordering of the guild-relation classifier extracted from
 *  the two identical `guildIcon` matches in TibiaBot. (Config-free, so no real
 *  API payload applies here — this is the decision logic, not a decoder.) */
class GuildIconsSpec extends AnyFunSuite with Matchers {

  private def classify(guild: String, ag: Boolean, hg: Boolean, ap: Boolean, hp: Boolean) =
    GuildIcons.classify(guild, ag, hg, ap, hp)

  test("an allied guild outranks every other signal") {
    // even if the same character is also flagged hunted at every level
    classify("Some Guild", ag = true, hg = true, ap = true, hp = true) shouldBe AllyGuild
    classify("", ag = true, hg = false, ap = false, hp = false) shouldBe AllyGuild
  }

  test("a hunted guild outranks player-level flags but not an allied guild") {
    classify("Some Guild", ag = false, hg = true, ap = true, hp = true) shouldBe HuntedGuild
    classify("Some Guild", ag = true, hg = true, ap = false, hp = false) shouldBe AllyGuild
  }

  test("allied player: no-guild and neutral-guild are distinguished") {
    classify("", ag = false, hg = false, ap = true, hp = false) shouldBe AllyPlayerNoGuild
    classify("Neutral Guild", ag = false, hg = false, ap = true, hp = false) shouldBe AllyPlayerNeutralGuild
  }

  test("allied player outranks hunted player at the same tier") {
    classify("", ag = false, hg = false, ap = true, hp = true) shouldBe AllyPlayerNoGuild
    classify("Neutral Guild", ag = false, hg = false, ap = true, hp = true) shouldBe AllyPlayerNeutralGuild
  }

  test("hunted player: no-guild and neutral-guild are distinguished") {
    classify("", ag = false, hg = false, ap = false, hp = true) shouldBe HuntedPlayerNoGuild
    classify("Neutral Guild", ag = false, hg = false, ap = false, hp = true) shouldBe HuntedPlayerNeutralGuild
  }

  test("unaffiliated characters: no-guild vs neutral-guild") {
    classify("", ag = false, hg = false, ap = false, hp = false) shouldBe NeutralNoGuild
    classify("Neutral Guild", ag = false, hg = false, ap = false, hp = false) shouldBe NeutralGuild
  }

  // --- list variant (/allies list, /hunted list): arg-aware classification ---

  test("allies list: guild status determines the icon, crossing with the list context") {
    classifyList("Ally Guild", allyGuild = true, huntedGuild = false, "allies") shouldBe ListRelation.AlliedGuild
    // listed as an ally but their guild is actually hunted
    classifyList("Hunted Guild", allyGuild = false, huntedGuild = true, "allies") shouldBe ListRelation.AlliedPlayerInHuntedGuild
    classifyList("", allyGuild = false, huntedGuild = false, "allies") shouldBe ListRelation.AlliedPlayerNoGuild
    classifyList("Neutral Guild", allyGuild = false, huntedGuild = false, "allies") shouldBe ListRelation.AlliedPlayerNeutralGuild
  }

  test("hunted list: guild status determines the icon, crossing with the list context") {
    classifyList("Hunted Guild", allyGuild = false, huntedGuild = true, "hunted") shouldBe ListRelation.HuntedGuild
    // listed as hunted but their guild is actually allied
    classifyList("Ally Guild", allyGuild = true, huntedGuild = false, "hunted") shouldBe ListRelation.HuntedPlayerInAlliedGuild
    classifyList("", allyGuild = false, huntedGuild = false, "hunted") shouldBe ListRelation.HuntedPlayerNoGuild
    classifyList("Neutral Guild", allyGuild = false, huntedGuild = false, "hunted") shouldBe ListRelation.HuntedPlayerNeutralGuild
  }

  test("hunted-guild precedence differs by list: allies keeps the guild flag, both honour their own list") {
    // in the allies list a hunted-guild member is flagged ally-in-enemy-guild;
    // in the hunted list the same hunted guild is a plain hunted guild
    classifyList("X", allyGuild = false, huntedGuild = true, "allies") shouldBe ListRelation.AlliedPlayerInHuntedGuild
    classifyList("X", allyGuild = false, huntedGuild = true, "hunted") shouldBe ListRelation.HuntedGuild
  }

  test("an unrecognised list context is unclassified (no icon)") {
    classifyList("Ally Guild", allyGuild = true, huntedGuild = false, "boosted") shouldBe ListRelation.Unclassified
    classifyList("", allyGuild = false, huntedGuild = false, "") shouldBe ListRelation.Unclassified
  }
}
