package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Golden-string characterization of the extracted URL builders. */
class UrlsSpec extends AnyFunSuite with Matchers {

  test("charUrl builds a community lookup URL") {
    Urls.charUrl("Bobeek") shouldBe "https://www.tibia.com/community/?name=Bobeek"
  }

  test("charUrl URL-encodes spaces and special characters") {
    Urls.charUrl("Violent Beams") shouldBe "https://www.tibia.com/community/?name=Violent+Beams"
    Urls.charUrl("Mooh'Tah") shouldBe "https://www.tibia.com/community/?name=Mooh%27Tah"
  }

  test("guildUrl builds a guild view URL") {
    Urls.guildUrl("Red Rose") shouldBe
      "https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=Red+Rose"
  }
}
