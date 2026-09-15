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

  test("topExperienceUrl points at guildstats, with the world as a path segment") {
    Urls.topExperienceUrl("Victoris") shouldBe "https://guildstats.eu/top-experience/Victoris"
  }

  test("topExperienceUrl escapes a space as a path segment would, not as a query would") {
    // URLEncoder is built for query strings, where a space is '+'; in a path
    // that is a literal plus sign and a different world.
    Urls.topExperienceUrl("Nice World") shouldBe "https://guildstats.eu/top-experience/Nice%20World"
  }
}
