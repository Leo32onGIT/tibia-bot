package com.tibiabot.observer

import com.tibiabot.persistence.ObserverRaidRepository
import com.tibiabot.presentation.ObserverEmbeds
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.MessageEmbed

import java.time.{Duration, Instant}

/** Drives the per-world raids channels: pools raids across every linked account, and
 *  posts each new one — rank-ordered — to the raids channel of every guild that has
 *  one for that world. So Discords with a raids channel for the same world share
 *  coverage: one member's exploration, anywhere, feeds them all.
 *
 *  `post` sends one embed to one channel (by guild + channel id) through the bot's
 *  rate-limited lane. Dedup on (guild, raidId, category) keeps the three stages of a
 *  raid, and repeated polls, from repeating. */
final class ObserverRaidPoller(
  observerService: ObserverService,
  raidRepository: ObserverRaidRepository,
  post: (String, String, MessageEmbed) => Unit
) extends StrictLogging {

  def poll(): Unit =
    try {
      observerService.pooledRaidsByWorld().foreach { case (world, raids) =>
        val channels = raidRepository.channelsForWorld(world)
        if (channels.nonEmpty) {
          val ordered = RaidRanking.order(raids)
          channels.foreach { case (guildId, channelId) =>
            ordered.foreach { raid =>
              if (raidRepository.markPostedIfNew(guildId, raid.raidId, raid.category))
                post(guildId, channelId, ObserverEmbeds.raidEmbed(raid))
            }
          }
        }
      }
      // Raids are short-lived; dedup rows older than this are never consulted again.
      raidRepository.prunePostedOlderThan(Instant.now().minus(Duration.ofHours(6)))
    } catch {
      case ex: Throwable => logger.warn("Observer raid poll failed", ex)
    }

  /** Mark the currently-active raids on `world` as already posted for this guild,
   *  without sending — used when a guild's raids channel for that world is first
   *  created, so it starts with raids going forward rather than a dump of what's live. */
  def seedPosted(guildId: String, world: String): Unit =
    try observerService.pooledRaidsByWorld().getOrElse(world, Nil)
      .foreach(r => raidRepository.markPostedIfNew(guildId, r.raidId, r.category))
    catch {
      case ex: Throwable => logger.warn(s"Observer raid seed failed for guild '$guildId', world '$world'", ex)
    }
}
