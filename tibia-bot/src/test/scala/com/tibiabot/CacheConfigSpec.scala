package com.tibiabot

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.DurationConverters._

/** Guards the centralised `cache { }` block in discord.conf: confirms every cache
 *  TTL key is present, parses as a HOCON duration, and has the intended default —
 *  the same getDuration(...).toScala path Config.Cache uses. Hermetic: resolves
 *  with allowUnresolved so it doesn't need TOKEN/POSTGRES_* env substitutions. */
class CacheConfigSpec extends AnyFunSuite with Matchers {

  private val cache = ConfigFactory.parseResources("discord.conf")
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
    .getConfig("discord-config").getConfig("cache")

  test("every centralised cache TTL key is present with its expected default") {
    cache.getDuration("boosted-ttl").toScala.toMinutes shouldBe 30
    cache.getDuration("highscores-ttl").toScala.toMinutes shouldBe 30
    cache.getDuration("world-list-ttl").toScala.toHours shouldBe 1
    cache.getDuration("character-snapshot-ttl").toScala.toDays shouldBe 7
    cache.getDuration("character-snapshot-interval").toScala.toSeconds shouldBe 60
  }
}
