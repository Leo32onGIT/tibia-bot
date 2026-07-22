package com.tibiabot.tracking

import java.util.concurrent.ConcurrentHashMap

/** Owns one [[RecentEvents]] per world, created on first access. A single
 *  instance lives in the composition root (BotApp) and is shared by every
 *  per-world stream (each `TibiaBot` gets just its own world's `RecentEvents`,
 *  not this registry) and by the status endpoint. Keeping one buffer per
 *  world (rather than one shared bot-wide buffer) means a busy world's
 *  events can't push a quiet world's events out of the window — each
 *  world's most-recent-50 is its own. */
final class RecentEventsRegistry {
  private val byWorld = new ConcurrentHashMap[String, RecentEvents]()

  /** Get this world's recent-events log, creating it on first access. */
  def forWorld(world: String): RecentEvents =
    byWorld.computeIfAbsent(world, _ => new RecentEvents())
}
