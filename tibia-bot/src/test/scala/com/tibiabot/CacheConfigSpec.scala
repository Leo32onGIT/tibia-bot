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

  private val config = ConfigFactory.parseResources("discord.conf")
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
    .getConfig("discord-config")
  private val cache = config.getConfig("cache")
  private val characterCache = config.getConfig("character-cache")

  test("every centralised cache TTL key is present with its expected default") {
    cache.getDuration("boosted-ttl").toScala.toMinutes shouldBe 1
    cache.getDuration("world-list-ttl").toScala.toHours shouldBe 1
    cache.getDuration("online-duration-ttl").toScala.toMinutes shouldBe 20
    cache.getDuration("killer-level-ttl").toScala.toMinutes shouldBe 10
  }

  test("the character age cache is configured with the measured upstream TTL and its guard rails") {
    characterCache.getBoolean("enabled") shouldBe true
    // 300s is the Kong TTL measured on /v4/character — the one value here that
    // describes the upstream rather than our own policy.
    characterCache.getDuration("ttl").toScala.toSeconds shouldBe 300
    characterCache.getDuration("margin").toScala.toSeconds shouldBe 5
    characterCache.getDuration("max-stale").toScala.toMinutes shouldBe 15
    characterCache.getDouble("canary-fraction") shouldBe 0.02
    characterCache.getInt("max-entries") shouldBe 20000
  }

  test("the canary fraction stays a small, sane share of fetches") {
    // Zero would blind the age histogram that validates the ttl above; a large
    // share would give back the saving the cache exists for.
    val fraction = characterCache.getDouble("canary-fraction")
    fraction should be > 0.0
    fraction should be < 0.1
  }
}
