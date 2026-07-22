package com.tibiabot.tracking

import java.util.concurrent.ConcurrentHashMap

/** Owns one [[WorldMetrics]] per world, created on first access. A single
 *  instance lives in the composition root (BotApp) and is shared by every
 *  per-world stream (each `TibiaBot` gets just its own world's `WorldMetrics`,
 *  not this registry) and by the status endpoint (which reads [[snapshotAll]]). */
final class WorldMetricsRegistry {
  private val byWorld = new ConcurrentHashMap[String, WorldMetrics]()

  /** Get this world's metrics, creating them on first access. */
  def forWorld(world: String): WorldMetrics =
    byWorld.computeIfAbsent(world, _ => new WorldMetrics())

  /** Reset every tracked world's 15-minute counters. Intended to be called on
   *  a fixed schedule (every 15 minutes), not on read. */
  def resetAllCounters(): Unit = {
    val it = byWorld.values().iterator()
    while (it.hasNext) it.next().resetCounters()
  }

  def snapshotAll(): Map[String, WorldSnapshot] = {
    val builder = Map.newBuilder[String, WorldSnapshot]
    val it = byWorld.entrySet().iterator()
    while (it.hasNext) {
      val entry = it.next()
      builder += entry.getKey -> entry.getValue.snapshot()
    }
    builder.result()
  }
}
