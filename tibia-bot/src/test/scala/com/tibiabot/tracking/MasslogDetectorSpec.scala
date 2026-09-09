package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MasslogDetectorSpec extends AnyFunSuite with Matchers {

  test("base percentage thresholds by enemy count") {
    MasslogDetector.basePercentage(5) shouldBe 0.60
    MasslogDetector.basePercentage(6) shouldBe 0.55
    MasslogDetector.basePercentage(10) shouldBe 0.55
    MasslogDetector.basePercentage(11) shouldBe 0.40
    MasslogDetector.basePercentage(20) shouldBe 0.40
    MasslogDetector.basePercentage(21) shouldBe 0.32
  }

  test("sensitivity 0 (today's fixed value) is the strict 1.20 multiplier") {
    MasslogDetector.sensitivityModifier(0) shouldBe 1.20
  }

  test("required zap count never drops below the floor of 3") {
    MasslogDetector.requiredZapCount(0) shouldBe 3
    MasslogDetector.requiredZapCount(1) shouldBe 3 // ceil(1*0.60*1.20)=1 -> floored to 3
    MasslogDetector.requiredZapCount(5) shouldBe 4 // ceil(5*0.60*1.20)=ceil(3.6)=4
  }

  test("required zap count scales with enemy count") {
    MasslogDetector.requiredZapCount(10) shouldBe 7  // ceil(10*0.55*1.20)=ceil(6.6)=7
    MasslogDetector.requiredZapCount(20) shouldBe 10 // ceil(20*0.40*1.20)=ceil(9.6)=10
    MasslogDetector.requiredZapCount(21) shouldBe 9  // ceil(21*0.32*1.20)=ceil(8.064)=9
  }

  test("isMasslog compares zap count against the requirement") {
    MasslogDetector.isMasslog(zapCount = 4, enemyCount = 5) shouldBe true
    MasslogDetector.isMasslog(zapCount = 3, enemyCount = 5) shouldBe false
  }

  test("a floor applied to the enemy count alone would make this fire more, not less") {
    // Why TibiaBot.onlineList filters the zap count on the same level floor as
    // the enemies list itself (`/filter online`).
    //
    // Twenty-five enemies online, twenty of them level-8 throwaways that have
    // just logged in. Unfiltered that is well short of a mass log.
    MasslogDetector.isMasslog(zapCount = 20, enemyCount = 25) shouldBe true

    // Hide the throwaways from the list and the denominator falls to 5, which
    // *raises* the required fraction from 32% to 60%. Keeping them in the
    // numerator would then clear a requirement they no longer belong to — a
    // filter meant to quieten the list would have made the alert louder.
    MasslogDetector.requiredZapCount(25) shouldBe 10
    MasslogDetector.requiredZapCount(5) shouldBe 4
    MasslogDetector.isMasslog(zapCount = 20, enemyCount = 5) shouldBe true

    // Filtered on both sides, the five real enemies are judged on their own and
    // none of them logged in recently.
    MasslogDetector.isMasslog(zapCount = 0, enemyCount = 5) shouldBe false
  }
}
