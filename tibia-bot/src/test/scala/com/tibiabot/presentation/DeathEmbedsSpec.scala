package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DeathEmbedsSpec extends AnyFunSuite with Matchers {

  test("assembles title, description, thumbnail and colour") {
    val e = DeathEmbeds.build("Bobeek", "Elite Knight", "Died at level 100", "https://x/shot.gif", 3092790).build()
    e.getTitle shouldBe ":shield: Bobeek :shield:"
    e.getDescription shouldBe "Died at level 100"
    e.getThumbnail.getUrl shouldBe "https://x/shot.gif"
    (e.getColor.getRGB & 0xFFFFFF) shouldBe 3092790
  }

  test("title links to the character page and uses the monk emoji where applicable") {
    val e = DeathEmbeds.build("Some Monk", "Exalted Monk", "desc", "https://x/t.gif", 1).build()
    e.getUrl shouldBe "https://www.tibia.com/community/?name=Some+Monk"
    e.getTitle should startWith(":fist::skin-tone-3:")
  }
}
