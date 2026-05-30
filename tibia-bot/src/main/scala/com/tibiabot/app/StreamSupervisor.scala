package com.tibiabot.app

import akka.actor.Cancellable
import com.tibiabot.domain.Discords
import com.typesafe.scalalogging.StrictLogging

/** A running per-world Akka stream and the guilds it serves. */
final case class WorldStream(stream: Cancellable, usedBy: List[Discords])

/**
 * Owns the per-world stream handles (previously `BotApp.botStreams`) and the
 * attach / remove / cancel lifecycle. Streams are started elsewhere and handed
 * in via [[put]]; this type only tracks them and cancels a stream once no guild
 * uses it. Not thread-safe by itself — callers serialise mutations as before.
 */
final class StreamSupervisor extends StrictLogging {

  private var streams = Map.empty[String, WorldStream]

  def contains(world: String): Boolean = streams.contains(world)
  def get(world: String): Option[WorldStream] = streams.get(world)
  def activeWorlds: Set[String] = streams.keySet
  def snapshot: Map[String, WorldStream] = streams

  /** Register an already-started `stream` for `world`, serving `usedBy`. */
  def put(world: String, stream: Cancellable, usedBy: List[Discords]): Unit =
    streams += (world -> WorldStream(stream, usedBy))

  /** Drop `guildId` from every world; cancel and remove any stream left unused. */
  def removeGuild(guildId: String): Unit =
    streams = streams.flatMap { case (world, s) =>
      val updatedUsedBy = s.usedBy.filterNot(_.id == guildId)
      if (updatedUsedBy.isEmpty) {
        s.stream.cancel()
        None
      } else if (s.usedBy != updatedUsedBy) {
        Some(world -> s.copy(usedBy = updatedUsedBy))
      } else {
        Some(world -> s)
      }
    }

  /** Drop `guildId` from a single `world`; cancel and remove if it becomes unused. */
  def removeGuildFromWorld(world: String, guildId: String): Unit =
    streams.get(world) match {
      case Some(s) =>
        val updatedUsedBy = s.usedBy.filterNot(_.id == guildId)
        if (updatedUsedBy.isEmpty) {
          s.stream.cancel()
          streams -= world
        } else {
          streams += (world -> s.copy(usedBy = updatedUsedBy))
        }
      case None =>
        logger.info(s"No stream found for guild '$guildId' and world '$world'.")
    }
}
