package com.tibiabot.observer

import com.tibiabot.persistence.ObserverRaidRepository
import com.tibiabot.presentation.ObserverEmbeds
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.MessageEmbed

import java.time.{Duration, Instant}

/** Drives the raids channels: pools raids across every linked account, and posts
 *  each new one — rank-ordered — to the raids channel of every guild that tracks its
 *  world. So Discords tracking the same world share coverage: one member's
 *  exploration, anywhere, feeds them all.
 *
 *  `guildsTrackingWorld` maps a world to the guild ids following it (the bot's
 *  existing world→discord map); `post` sends one embed to one channel through the
 *  bot's rate-limited lane. Dedup on (guild, raidId, category) keeps the three
 *  stages of a raid, and repeated polls, from repeating. */
final class ObserverRaidPoller(
  observerService: ObserverService,
  raidRepository: ObserverRaidRepository,
  guildsTrackingWorld: String => List[String],
  post: (String, String, MessageEmbed) => Unit
) extends StrictLogging {

  def poll(): Unit =
    try {
      observerService.pooledRaidsByWorld().foreach { case (world, raids) =>
        val ordered = RaidRanking.order(raids)
        guildsTrackingWorld(world).distinct.foreach { guildId =>
          raidRepository.channelFor(guildId).foreach { channelId =>
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

  /** Mark every currently-active raid for this guild's worlds as already posted,
   *  without sending — used when a guild first sets its channel, so it starts with
   *  raids going forward rather than a dump of everything live right now. */
  def seedPosted(guildId: String, worlds: Set[String]): Unit =
    try {
      val byWorld = observerService.pooledRaidsByWorld()
      worlds.foreach { world =>
        byWorld.getOrElse(world, Nil).foreach(r => raidRepository.markPostedIfNew(guildId, r.raidId, r.category))
      }
    } catch {
      case ex: Throwable => logger.warn(s"Observer raid seed failed for guild '$guildId'", ex)
    }
}
