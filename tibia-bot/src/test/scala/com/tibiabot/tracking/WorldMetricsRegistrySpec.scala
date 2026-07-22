package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WorldMetricsRegistrySpec extends AnyFunSuite with Matchers {

  test("forWorld creates metrics on first access and returns the same instance thereafter") {
    val registry = new WorldMetricsRegistry
    val first = registry.forWorld("Antica")
    first.incrementDeaths()
    val second = registry.forWorld("Antica")
    second.snapshot().deaths shouldBe 1 // same underlying instance
  }

  test("different worlds get independent metrics") {
    val registry = new WorldMetricsRegistry
    registry.forWorld("Antica").incrementDeaths()
    registry.forWorld("Secura").incrementLevels()

    registry.forWorld("Antica").snapshot().deaths shouldBe 1
    registry.forWorld("Antica").snapshot().levels shouldBe 0
    registry.forWorld("Secura").snapshot().deaths shouldBe 0
    registry.forWorld("Secura").snapshot().levels shouldBe 1
  }

  test("snapshotAll returns every tracked world, keyed by name") {
    val registry = new WorldMetricsRegistry
    registry.forWorld("Antica")
    registry.forWorld("Secura")

    val snap = registry.snapshotAll()
    snap.keySet shouldBe Set("Antica", "Secura")
  }

  test("snapshotAll on an empty registry is empty") {
    val registry = new WorldMetricsRegistry
    registry.snapshotAll() shouldBe empty
  }

  test("resetAllCounters resets every tracked world's counters") {
    val registry = new WorldMetricsRegistry
    registry.forWorld("Antica").incrementDeaths()
    registry.forWorld("Secura").incrementLevels()

    registry.resetAllCounters()

    registry.forWorld("Antica").snapshot().deaths shouldBe 0
    registry.forWorld("Secura").snapshot().levels shouldBe 0
  }
}
