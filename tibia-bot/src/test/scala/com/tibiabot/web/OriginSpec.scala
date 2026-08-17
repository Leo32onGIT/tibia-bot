package com.tibiabot.web

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Where a link the bot posts actually points.
 *
 *  A board thread carries a link to the dashboard, and every bot in the fleet
 *  posts one — but only the bot serving a dashboard has a `status-domain`. An
 *  origin assembled from that setting while it is blank is still a plausible
 *  string, which is how `https://dashboard` came to be pinned in guilds run by
 *  every other bot.
 */
class OriginSpec extends AnyFunSuite with Matchers {

  /** The conf parsed from resources with `allowUnresolved`, as
   *  [[com.tibiabot.CacheConfigSpec]] does it: `Config` itself cannot be loaded
   *  here, since it wants a deployment's TOKEN/POSTGRES_* behind it. */
  private val web = ConfigFactory.parseResources("discord.conf")
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
    .getConfig("discord-config").getConfig("web")

  test("the dashboard's public address has a real default, not a blank one") {
    // The two settings it falls back from are blank by default and filled in per
    // deployment. This one is the floor, so a blank here is the bug itself.
    web.getString("dashboard-domain") shouldBe "violentbot.xyz"
    Origin.of("", "", web.getString("dashboard-domain")) shouldBe "https://violentbot.xyz"
  }

  test("a bot serving its own dashboard links to itself") {
    Origin.of("", "bot.example", "violentbot.xyz") shouldBe "https://bot.example"
  }

  test("an explicit base-url wins over both, so a local run stays local") {
    Origin.of("http://localhost:8081", "violentbot.xyz", "violentbot.xyz") shouldBe "http://localhost:8081"
  }

  test("a path can be appended to whatever comes back") {
    // What the malformed link was made of: the parts were joined without asking
    // what shape they had arrived in.
    Origin.of("https://violentbot.xyz/", "", "violentbot.xyz") shouldBe "https://violentbot.xyz"
    Origin.of("", "https://violentbot.xyz/", "elsewhere") shouldBe "https://violentbot.xyz"
    Origin.of("", "  ", "violentbot.xyz") shouldBe "https://violentbot.xyz"
  }
}
