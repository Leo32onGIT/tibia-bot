package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LevelVisibilitySpec extends AnyFunSuite with Matchers {

  // helper: a neutral, at-or-above-floor level-up with everything enabled
  private def post(
    isNeutral: Boolean = false, isAlly: Boolean = false, isEnemy: Boolean = false,
    showNeutral: String = "true", showAllies: String = "true", showEnemies: String = "true",
    level: Int = 100, minimumLevel: Int = 8
  ): Boolean = LevelVisibility.shouldPost(
    isNeutral, isAlly, isEnemy, showNeutral, showAllies, showEnemies, level, minimumLevel)

  test("a neutral level-up is suppressed only when showNeutral is off") {
    post(isNeutral = true, showNeutral = "false") shouldBe false
    post(isNeutral = true, showNeutral = "true") shouldBe true
    // an enemy/ally flag being off must not affect a neutral level-up
    post(isNeutral = true, showEnemies = "false", showAllies = "false") shouldBe true
  }

  test("an ally level-up is suppressed only when showAllies is off") {
    post(isAlly = true, showAllies = "false") shouldBe false
    post(isAlly = true, showAllies = "true", showNeutral = "false") shouldBe true
  }

  test("an enemy level-up is suppressed only when showEnemies is off") {
    post(isEnemy = true, showEnemies = "false") shouldBe false
    post(isEnemy = true, showEnemies = "true", showAllies = "false") shouldBe true
  }

  test("the minimum-level floor applies once the category is allowed") {
    post(isNeutral = true, level = 7, minimumLevel = 8) shouldBe false
    post(isNeutral = true, level = 8, minimumLevel = 8) shouldBe true
  }

  test("a suppressed category is dropped even when above the level floor") {
    post(isEnemy = true, showEnemies = "false", level = 999, minimumLevel = 8) shouldBe false
  }

  test("an unrecognised category is gated by the level floor alone") {
    post(level = 9, minimumLevel = 8) shouldBe true
    post(level = 5, minimumLevel = 8) shouldBe false
  }
}
