package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WorldNameSpec extends AnyFunSuite with Matchers {

  test("formal normalises any casing of a single-word world to Titlecase") {
    WorldName.formal("antica") shouldBe "Antica"
    WorldName.formal("ANTICA") shouldBe "Antica"
    WorldName.formal("Antica") shouldBe "Antica"
    WorldName.formal("aNtIcA") shouldBe "Antica"
  }

  test("formal is idempotent") {
    WorldName.formal(WorldName.formal("belobra")) shouldBe "Belobra"
  }

  test("formal handles an empty string") {
    WorldName.formal("") shouldBe ""
  }
}
