package com.tibiabot.scheduler

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class GuildPruneRuleSpec extends AnyFunSuite with Matchers {

  private val now = ZonedDateTime.parse("2026-07-24T00:00:00Z")

  test("worldless for less than the threshold never leaves, regardless of activity") {
    val worldlessSince = now.minusDays(13)
    GuildPruneRule.shouldLeave(worldlessSince, None, now) shouldBe false
  }

  test("worldless for at least the threshold with no recorded activity leaves") {
    val worldlessSince = now.minusDays(14)
    GuildPruneRule.shouldLeave(worldlessSince, None, now) shouldBe true
  }

  test("worldless for at least the threshold, but with activity inside the activity window, does not leave") {
    val worldlessSince = now.minusDays(20)
    val lastCommandAt = now.minusDays(29)
    GuildPruneRule.shouldLeave(worldlessSince, Some(lastCommandAt), now) shouldBe false
  }

  test("activity exactly at the activity-day boundary no longer counts as recent") {
    val worldlessSince = now.minusDays(20)
    val lastCommandAt = now.minusDays(30)
    GuildPruneRule.shouldLeave(worldlessSince, Some(lastCommandAt), now) shouldBe true
  }

  test("activity just inside the activity-day boundary still counts as recent") {
    val worldlessSince = now.minusDays(20)
    val lastCommandAt = now.minusDays(29).minusHours(23)
    GuildPruneRule.shouldLeave(worldlessSince, Some(lastCommandAt), now) shouldBe false
  }

  test("thresholds are configurable") {
    val worldlessSince = now.minusDays(5)
    GuildPruneRule.shouldLeave(worldlessSince, None, now, worldlessThresholdDays = 5) shouldBe true
    GuildPruneRule.shouldLeave(worldlessSince, None, now, worldlessThresholdDays = 6) shouldBe false
  }
}
