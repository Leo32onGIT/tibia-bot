package com.tibiabot

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ConnectionPoolSettings
import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/** Guards the per-host pool split in akka.conf. The per-host-override syntax is
 *  HOCON parsed at runtime, so this confirms akka-http actually ACCEPTS the block
 *  (ConnectionPoolSettings construction throws on a malformed override) and that
 *  the intended values are present: default 16 (the rate-sensitive local instance)
 *  vs. the api.tibiadata.com override (the public firehose). Loads akka.conf with
 *  akka's reference defaults only — not discord.conf — so it is hermetic and does
 *  not depend on env-var substitutions. */
class AkkaPoolConfigSpec extends AnyFunSuite with Matchers {

  test("akka.conf parses, the api.tibiadata.com per-host-override is accepted, and values are as intended") {
    val cfg = ConfigFactory.parseResources("akka.conf")
      .withFallback(ConfigFactory.defaultReference())
      .resolve()
    val system = ActorSystem("pool-config-test", cfg)
    try {
      // Throws if per-host-override is structurally malformed for akka-http.
      val settings = ConnectionPoolSettings(system)
      settings.maxConnections shouldBe 16 // default governs the throttled local instance

      val overrides = cfg.getConfigList("akka.http.host-connection-pool.per-host-override")
      overrides.size shouldBe 1
      overrides.get(0).getString("host-pattern") shouldBe "api.tibiadata.com"
      overrides.get(0).getInt("max-connections") shouldBe 64
    } finally Await.result(system.terminate(), 10.seconds)
  }
}
