package com.tibiabot

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.settings.ConnectionPoolSettings
import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/** Guards the per-host pool split in pekko.conf. The per-host-override syntax is
 *  HOCON parsed at runtime, so this confirms pekko-http actually ACCEPTS the block
 *  (ConnectionPoolSettings construction throws on a malformed override) and that
 *  the intended values are present: default 16 (the rate-sensitive local instance)
 *  vs. the api.tibiadata.com override (the public firehose). Loads pekko.conf with
 *  pekko's reference defaults only — not discord.conf — so it is hermetic and does
 *  not depend on env-var substitutions. */
class PekkoPoolConfigSpec extends AnyFunSuite with Matchers {

  test("pekko.conf parses, the api.tibiadata.com per-host-override is accepted, and values are as intended") {
    val cfg = ConfigFactory.parseResources("pekko.conf")
      .withFallback(ConfigFactory.defaultReference())
      .resolve()
    val system = ActorSystem("pool-config-test", cfg)
    try {
      // Throws if per-host-override is structurally malformed for pekko-http.
      val settings = ConnectionPoolSettings(system)
      settings.maxConnections shouldBe 16 // default governs the throttled local instance

      val overrides = cfg.getConfigList("pekko.http.host-connection-pool.per-host-override")
      overrides.size shouldBe 1
      overrides.get(0).getString("host-pattern") shouldBe "api.tibiadata.com"
      overrides.get(0).getInt("max-connections") shouldBe 64
    } finally Await.result(system.terminate(), 10.seconds)
  }
}
