package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GuildActivitySpec extends AnyFunSuite with Matchers {

  test("activityColor: hunted is red, allied is green, otherwise yellow") {
    GuildActivity.activityColor(huntedGuild = true, alliedGuild = false) shouldBe 13773097
    GuildActivity.activityColor(huntedGuild = false, alliedGuild = true) shouldBe 36941
    GuildActivity.activityColor(huntedGuild = false, alliedGuild = false) shouldBe 14397256
  }

  test("activityColor prefers hunted when both flags are set") {
    GuildActivity.activityColor(huntedGuild = true, alliedGuild = true) shouldBe 13773097
  }

  test("guildType: hunted / allied / neutral label") {
    GuildActivity.guildType(huntedGuild = true, alliedGuild = false) shouldBe "hunted"
    GuildActivity.guildType(huntedGuild = false, alliedGuild = true) shouldBe "allied"
    GuildActivity.guildType(huntedGuild = false, alliedGuild = false) shouldBe "neutral"
  }

  test("guildType prefers hunted when both flags are set") {
    GuildActivity.guildType(huntedGuild = true, alliedGuild = true) shouldBe "hunted"
  }
}
