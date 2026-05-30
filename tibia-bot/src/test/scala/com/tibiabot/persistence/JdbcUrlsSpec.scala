package com.tibiabot.persistence

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class JdbcUrlsSpec extends AnyFunSuite with Matchers {

  private val host = "sqlhost"

  test("guild url targets the per-guild _<guildId> database") {
    JdbcUrls.guild(host, "912739993015947324") shouldBe
      "jdbc:postgresql://sqlhost:5432/_912739993015947324"
  }

  test("cache, admin and premium urls match the originals") {
    JdbcUrls.cache(host) shouldBe "jdbc:postgresql://sqlhost:5432/bot_cache"
    JdbcUrls.admin(host) shouldBe "jdbc:postgresql://sqlhost:5432/postgres"
    JdbcUrls.premium(host) shouldBe "jdbc:postgresql://sqlhost:5432/premium"
  }
}
