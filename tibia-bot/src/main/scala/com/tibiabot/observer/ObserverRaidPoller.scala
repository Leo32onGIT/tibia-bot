package com.tibiabot.observer

import com.tibiabot.domain.RaidAnnouncement
import com.tibiabot.persistence.ObserverRaidRepository
import com.tibiabot.presentation.ObserverEmbeds
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.MessageEmbed

import java.time.{Duration, Instant}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

/** A raid we are unfurling. `anchor` is the raid's start instant (the feed's
 *  startDate, or when we first saw it started); broadcast timings are measured from
 *  it. `None` until a start is known — the imminent post still goes out, and the
 *  broadcasts are scheduled once a start is known. */
private final case class TrackedRaid(world: String, raidTypeId: Int, anchor: Option[Instant], firstSeen: Instant)

/** Drives the per-world raids channels.
 *
 *  [[poll]] is the detection pass and the only one that touches the API: it pools
 *  raids across every linked account and, on a raid's first sighting (at whichever
 *  stage a member's exploration reveals it — area or subarea), posts one
 *  imminent-raid heads-up to every guild's raids channel for that world. Then, once
 *  the raid's start is known, it **schedules each broadcast line as a one-shot timer**
 *  at its exact moment (`startDate + millis`, from the deterministic catalogue). No
 *  further feed calls, and no polling for the drip — a line fires at its instant and
 *  is posted to every guild's raids channel for that world, so Discords sharing a
 *  world share the coverage and the raid unfolds live in each.
 *
 *  Dedup is durable (in the database), keyed on `(guild, raidId, key)` where `key` is
 *  `"imminent"` or `"line:i"`; the in-memory registry is only bookkeeping, so a
 *  restart re-hydrates from the next detection poll (which re-schedules the remaining
 *  lines) without re-posting. `post` sends one embed to one channel through the bot's
 *  rate-limited lane; `schedule` runs a task once after a delay (the actor scheduler). */
final class ObserverRaidPoller(
  observerService: ObserverService,
  raidRepository: ObserverRaidRepository,
  post: (String, String, MessageEmbed) => Unit,
  schedule: (FiniteDuration, () => Unit) => Unit
) extends StrictLogging {

  private val tracked = TrieMap.empty[String, TrackedRaid]
  // Raids whose broadcast lines have already been scheduled this process — so a raid
  // that stays in the feed across polls is not scheduled twice (the database dedup is
  // the durable guard; this just avoids redundant timers).
  private val scheduledRaids = TrieMap.empty[String, Unit]

  private def typePriority(id: Int): Int = if (RaidTypeCatalog.get(id).isDefined) 1 else 0

  def poll(): Unit =
    try {
      val now = Instant.now()
      observerService.pooledRaidsByWorld().foreach { case (world, raids) =>
        val channels = raidRepository.channelsForWorld(world)
        // The feed lists a raid once per stage; collapse to one entry per raid.
        val perRaid = raids.groupBy(_.raidId).values.map(represent).toList
        RaidRanking.order(perRaid, typePriority).foreach { raid =>
          val startedNow = raids.exists(r => r.raidId == raid.raidId && r.category == "raidStarted")
          register(raid, world, if (startedNow) Some(now) else None)
          if (channels.nonEmpty)
            channels.foreach { case (guildId, channelId) =>
              if (raidRepository.markPostedIfNew(guildId, raid.raidId, "imminent"))
                post(guildId, channelId, ObserverEmbeds.imminentEmbed(raid, RaidTypeCatalog.get(raid.raidTypeId)))
            }
          // Once we know when it starts, schedule every broadcast line precisely — once.
          tracked.get(raid.raidId).flatMap(_.anchor).foreach { anchor =>
            if (scheduledRaids.putIfAbsent(raid.raidId, ()).isEmpty)
              scheduleLines(raid.raidId, world, raid.raidTypeId, anchor, now)
          }
        }
      }
      pruneTracked(now)
      // Raids are short-lived; dedup rows older than this are never consulted again.
      raidRepository.prunePostedOlderThan(now.minus(Duration.ofHours(6)))
    } catch {
      case ex: Throwable => logger.warn("Observer raid poll failed", ex)
    }

  /** Schedule each of a raid's broadcasts to post at its exact moment. A line whose
   *  moment has already passed (a raid caught late, or already in progress) is given a
   *  zero delay, so it posts on the next scheduler tick rather than being lost. */
  private def scheduleLines(raidId: String, world: String, raidTypeId: Int, anchor: Instant, now: Instant): Unit = {
    val broadcasts = RaidTypeCatalog.get(raidTypeId).map(_.broadcasts).getOrElse(Vector.empty)
    broadcasts.zipWithIndex.foreach { case (event, index) =>
      event.message.foreach { message =>
        val delayMs = math.max(0L, Duration.between(now, anchor.plusMillis(event.millis)).toMillis)
        schedule(delayMs.millis, () => postLine(raidId, world, index, message))
      }
    }
  }

  /** Post one broadcast line, when its timer fires, to every guild's raids channel for
   *  the world — channels resolved now, so one created since scheduling is included.
   *  Deduped per `(guild, raidId, line:index)`, so a re-scheduled line never repeats. */
  private def postLine(raidId: String, world: String, index: Int, message: String): Unit =
    try raidRepository.channelsForWorld(world).foreach { case (guildId, channelId) =>
      if (raidRepository.markPostedIfNew(guildId, raidId, s"line:$index"))
        post(guildId, channelId, ObserverEmbeds.raidLineEmbed(message))
    } catch {
      case ex: Throwable => logger.warn(s"Observer raid line post failed for raid '$raidId' line $index", ex)
    }

  /** Mark the currently-active raids on `world` as already posted for this guild —
   *  the imminent heads-up and every broadcast line — used when a guild's raids
   *  channel for that world is first created, so it starts with the next new raid
   *  rather than backfilling raids already in progress. */
  def seedPosted(guildId: String, world: String): Unit =
    try observerService.pooledRaidsByWorld().getOrElse(world, Nil)
      .groupBy(_.raidId).foreach { case (raidId, entries) =>
        raidRepository.markPostedIfNew(guildId, raidId, "imminent")
        val broadcasts = RaidTypeCatalog.get(entries.head.raidTypeId).map(_.broadcasts).getOrElse(Vector.empty)
        broadcasts.indices.foreach(i => raidRepository.markPostedIfNew(guildId, raidId, s"line:$i"))
      }
    catch {
      case ex: Throwable => logger.warn(s"Observer raid seed failed for guild '$guildId', world '$world'", ex)
    }

  /** Pick one entry to represent a raid across its stages: prefer one carrying a start
   *  time (needed to time the broadcasts), else the first — location and creatures come
   *  from the catalogue regardless of which stage revealed it. */
  private def represent(entries: List[RaidAnnouncement]): RaidAnnouncement =
    entries.find(_.startDate.isDefined).getOrElse(entries.head)

  /** Record a raid, keeping the first start we learn and filling it in once known. */
  private def register(raid: RaidAnnouncement, world: String, startedAnchor: Option[Instant]): Unit = {
    val incoming = raid.startDate.orElse(startedAnchor)
    tracked.get(raid.raidId) match {
      case Some(t) =>
        val anchor = t.anchor.orElse(incoming)
        if (anchor != t.anchor) tracked.update(raid.raidId, t.copy(anchor = anchor))
      case None =>
        tracked.update(raid.raidId, TrackedRaid(world, raid.raidTypeId, incoming, Instant.now()))
    }
  }

  /** Forget raids that have fully unfurled (last broadcast well past) or that were
   *  never anchored and have sat unstarted too long. */
  private def pruneTracked(now: Instant): Unit =
    tracked.foreach { case (raidId, t) =>
      val lastMillis = RaidTypeCatalog.get(t.raidTypeId).map(_.broadcasts).filter(_.nonEmpty)
        .map(_.last.millis).getOrElse(0L)
      val done = t.anchor.exists(a => now.isAfter(a.plusMillis(lastMillis).plus(Duration.ofMinutes(30))))
      val stale = t.anchor.isEmpty && now.isAfter(t.firstSeen.plus(Duration.ofHours(2)))
      if (done || stale) {
        tracked.remove(raidId)
        scheduledRaids.remove(raidId)
      }
    }
}
