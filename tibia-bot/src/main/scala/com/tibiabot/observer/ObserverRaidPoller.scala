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
 *  it. `raidTypeId` is the best the feed has said so far — often nothing known until
 *  the raid starts. */
private final case class TrackedRaid(world: String, raidTypeId: Int, anchor: Option[Instant], firstSeen: Instant)

/** Drives the per-world raids channels: a post for each stage of a raid, then its
 *  broadcast lines.
 *
 *  ==The stages==
 *  A raid reaches the feed in three: its area is revealed an hour before it starts,
 *  its subarea 15 minutes before, and then it starts. The feed's time is the start
 *  at every stage. An account with limited discoveries is not told *which* raid it
 *  is until it starts — so nothing about a raid can be relied on before then but
 *  its area, its subarea and its start. Each stage therefore gets its own post, and
 *  nothing posted is edited:
 *
 *   - area revealed: the imminent-raid post — the area, and when the subarea reveals;
 *   - subarea revealed: the subarea, and when the raid starts;
 *   - raid started: the raid by name, when no earlier post could name it, then the
 *     broadcast lines, each timed from the start off the catalogue.
 *
 *  A raid first seen past its area stage gets only its latest stage's post, and one
 *  first seen already started gets the subarea post (saying when it started) ahead
 *  of its lines. A raid already over — its last broadcast `FinishedAfter` past —
 *  gets nothing: it is still in the feed, but it is no longer news. What a post shows grows with what is known: when a better-explored
 *  account's feed already names the raid, its name, creatures and picture come too.
 *  Everything every linked account's feed says about a raid is combined — see
 *  [[ObserverRaidPoller.merge]].
 *
 *  ==When it looks==
 *  [[tick]] runs every minute and sweeps the feed every `sweepEvery` for new raids.
 *  Once a raid's start is known, its next stages are at known moments, so a one-off
 *  poll is scheduled just after each (`wakeAfter`): the subarea post and the first
 *  lines arrive within seconds of the stage changing, not at the next sweep.
 *
 *  Lines are scheduled once — as one-shot timers at `start + millis` — when both the
 *  start and the raid are known. A line whose moment has passed fires at once.
 *
 *  Dedup is durable, in the database, keyed on `(guild, raidId, key)` where `key`
 *  is `"imminent"`, `"subarea"`, `"named"` (set by whichever post first names the
 *  raid) or `"line:i"`; the in-memory registry is only
 *  bookkeeping, so a restart re-hydrates from the next poll without re-posting — and
 *  without posting raids that finished while it was down.
 *
 *  `pooledRaids` is the feed — fetched on the bot that talks to the API, the
 *  primary's published copy on one that does not (see [[ObserverFeed]]). The raids
 *  channels and the dedup rows live in the shared cache database, so every bot sees
 *  every guild's channels; `servesGuild` narrows them to this bot's own. Without it,
 *  one bot would mark another's guilds posted and then fail to post there, and the
 *  bot that runs them would never post at all. */
final class ObserverRaidPoller(
  pooledRaids: () => Map[String, List[RaidAnnouncement]],
  raidRepository: ObserverRaidRepository,
  post: (String, String, MessageEmbed) => Unit,
  schedule: (FiniteDuration, () => Unit) => Unit,
  servesGuild: String => Boolean,
  sweepEvery: Duration = Duration.ofMinutes(15),
  wakeAfter: List[Duration] = List(Duration.ofSeconds(20), Duration.ofMinutes(2)),
  known: Int => Boolean = id => RaidTypeCatalog.get(id).isDefined,
  areaPost: (RaidAnnouncement, Option[RaidType]) => MessageEmbed = ObserverEmbeds.areaEmbed(_, _),
  subareaPost: (RaidAnnouncement, Option[RaidType], Instant) => MessageEmbed = ObserverEmbeds.subareaEmbed(_, _, _),
  linePost: String => MessageEmbed = ObserverEmbeds.raidLineEmbed,
  now: () => Instant = () => Instant.now()
) extends StrictLogging {
  import ObserverRaidPoller._

  /** The raids channels for a world in guilds this bot runs. */
  private def ownChannels(world: String): List[(String, String)] =
    raidRepository.channelsForWorld(world).filter { case (guildId, _) => servesGuild(guildId) }

  private val tracked = TrieMap.empty[String, TrackedRaid]
  // Raids whose broadcast lines have been scheduled this process — so a raid that
  // stays in the feed across polls is not scheduled twice (the database dedup is the
  // durable guard; this just avoids redundant timers). Only ever set once the lines
  // are known: marking a raid before its type arrived is what used to leave it
  // without lines until the next restart.
  private val scheduledRaids = TrieMap.empty[String, Unit]
  // The one-off polls already scheduled, by raid and the stage they wait for.
  private val wakes = TrieMap.empty[(String, Int), Unit]

  @volatile private var lastSweep: Instant = Instant.EPOCH

  private def typePriority(id: Int): Int = if (known(id)) 1 else 0

  /** Meant to run once a minute: sweeps for new raids when one is due. The stage
   *  changes of raids already known are caught by their own one-off polls. */
  def tick(): Unit = {
    val at = now()
    if (!lastSweep.plus(sweepEvery).isAfter(at)) {
      lastSweep = at
      poll()
    }
  }

  /** One pass over the feed. Synchronised: the sweep and a raid's one-off poll can
   *  land together, and each decides what to post from what the other recorded. */
  def poll(): Unit = synchronized {
    try {
      val at = now()
      pooledRaids().foreach { case (world, entries) =>
        val channels = ownChannels(world)
        val raids = entries.groupBy(_.raidId).values.map(merge(_, known)).toList
        RaidRanking.order(raids, typePriority).foreach { raid =>
          register(raid, world, at)
          // A raid already over is history, not news: raids stay in the feed for
          // hours, so a restart (or dedup rows pruned) would otherwise announce and
          // unfurl every one from earlier in the day.
          if (!tracked.get(raid.raidId).exists(over(_, at))) {
            val raidType = RaidTypeCatalog.get(raid.raidTypeId)
            channels.foreach { case (guildId, channelId) => announce(guildId, channelId, raid, raidType, at) }
            wakeForNextStage(raid, at)
            for (anchor <- tracked.get(raid.raidId).flatMap(_.anchor); rt <- raidType)
              if (scheduledRaids.putIfAbsent(raid.raidId, ()).isEmpty)
                scheduleLines(raid.raidId, world, rt, anchor, at)
          }
        }
      }
      pruneTracked(at)
      // Raids are short-lived; dedup rows older than this are never consulted again.
      raidRepository.prunePostedOlderThan(at.minus(Duration.ofHours(6)))
    } catch {
      case ex: Throwable => logger.warn("Observer raid poll failed", ex)
    }
  }

  /** The post for the stage a raid has reached, once per guild. Past the area stage
   *  the area post is marked done too, so an hour-old "imminent" never follows.
   *
   *  A post that names the raid marks it `named`. A raid only identified at its start
   *  (an account with limited discoveries) was named by none of its stage posts, so
   *  the start brings one more: the subarea post again, now with the raid's name,
   *  creatures and picture, saying it has started. That start is noticed by the poll
   *  just after it, and the same poll schedules the lines, so this post is queued
   *  ahead of the first. A raid named in an earlier post gets nothing new here. */
  private def announce(guildId: String, channelId: String, raid: RaidAnnouncement,
                       raidType: Option[RaidType], at: Instant): Unit = {
    def markNamed(): Unit =
      if (raidType.isDefined) raidRepository.markPostedIfNew(guildId, raid.raidId, "named")
    stage(raid.category) match {
      case AreaStage =>
        if (raidRepository.markPostedIfNew(guildId, raid.raidId, "imminent")) {
          markNamed()
          post(guildId, channelId, areaPost(raid, raidType))
        }
      case s if s >= SubareaStage =>
        if (raidRepository.markPostedIfNew(guildId, raid.raidId, "subarea")) {
          raidRepository.markPostedIfNew(guildId, raid.raidId, "imminent")
          markNamed()
          post(guildId, channelId, subareaPost(raid, raidType, at))
        } else if (s >= StartedStage && raidType.isDefined &&
                   raidRepository.markPostedIfNew(guildId, raid.raidId, "named"))
          post(guildId, channelId, subareaPost(raid, raidType, at))
      case _ => ()
    }
  }

  /** Poll again just after the raid's next stage: its subarea reveal, or its start
   *  while the raid (and so its lines) is not yet known. Scheduled once per stage;
   *  a moment already past schedules nothing — the sweep covers that. */
  private def wakeForNextStage(raid: RaidAnnouncement, at: Instant): Unit =
    raid.startDate.foreach { start =>
      val reached = stage(raid.category)
      val next: Option[(Int, Instant)] =
        if (reached < SubareaStage) Some(SubareaStage -> start.minus(SubareaLead))
        else if (reached < StartedStage || !known(raid.raidTypeId)) Some(StartedStage -> start)
        else None
      next.foreach { case (waitingFor, moment) =>
        if (wakes.putIfAbsent((raid.raidId, waitingFor), ()).isEmpty)
          wakeAfter.foreach { offset =>
            val delay = Duration.between(at, moment.plus(offset))
            if (!delay.isNegative) schedule(delay.toMillis.millis, () => poll())
          }
      }
    }

  /** Schedule each of a raid's broadcasts to post at its exact moment. A line whose
   *  moment has already passed (a raid caught late, or already in progress) is given a
   *  zero delay, so it posts on the next scheduler tick rather than being lost. */
  private def scheduleLines(raidId: String, world: String, raidType: RaidType, anchor: Instant, at: Instant): Unit =
    raidType.broadcasts.zipWithIndex.foreach { case (event, index) =>
      event.message.foreach { message =>
        val delayMs = math.max(0L, Duration.between(at, anchor.plusMillis(event.millis)).toMillis)
        schedule(delayMs.millis, () => postLine(raidId, world, index, message))
      }
    }

  /** Post one broadcast line, when its timer fires, to every guild's raids channel for
   *  the world — channels resolved now, so one created since scheduling is included.
   *  Deduped per `(guild, raidId, line:index)`, so a re-scheduled line never repeats. */
  private def postLine(raidId: String, world: String, index: Int, message: String): Unit =
    try ownChannels(world).foreach { case (guildId, channelId) =>
      if (raidRepository.markPostedIfNew(guildId, raidId, s"line:$index"))
        post(guildId, channelId, linePost(message))
    } catch {
      case ex: Throwable => logger.warn(s"Observer raid line post failed for raid '$raidId' line $index", ex)
    }

  /** Mark the currently-active raids on `world` as already posted for this guild —
   *  every stage post and every broadcast line — used when a guild's raids channel
   *  for that world is first created, so it starts with the next new raid rather
   *  than backfilling raids already in progress.
   *
   *  A raid not yet identified can't have its lines marked, so they will still post
   *  at its start. Its named post is left unmarked too, so it arrives with them rather
   *  than the lines arriving alone. */
  def seedPosted(guildId: String, world: String): Unit =
    try pooledRaids().getOrElse(world, Nil)
      .groupBy(_.raidId).values.map(merge(_, known)).foreach { raid =>
        val raidType = RaidTypeCatalog.get(raid.raidTypeId)
        val keys = List("imminent", "subarea") ++ raidType.map(_ => "named") ++
          raidType.toList.flatMap(_.broadcasts.indices.map(i => s"line:$i"))
        keys.foreach(key => raidRepository.markPostedIfNew(guildId, raid.raidId, key))
      }
    catch {
      case ex: Throwable => logger.warn(s"Observer raid seed failed for guild '$guildId', world '$world'", ex)
    }

  /** Record a raid: its start once known, and the raid once the feed says which. */
  private def register(raid: RaidAnnouncement, world: String, at: Instant): Unit = {
    val startedAt = if (stage(raid.category) >= StartedStage) Some(at) else None
    val incoming = raid.startDate.orElse(startedAt)
    tracked.get(raid.raidId) match {
      case Some(t) =>
        val anchor = t.anchor.orElse(incoming)
        val typeId = if (known(t.raidTypeId)) t.raidTypeId else raid.raidTypeId
        if (anchor != t.anchor || typeId != t.raidTypeId)
          tracked.update(raid.raidId, t.copy(anchor = anchor, raidTypeId = typeId))
      case None =>
        tracked.update(raid.raidId, TrackedRaid(world, raid.raidTypeId, incoming, at))
    }
  }

  /** Whether a raid has fully unfurled: its last broadcast is more than
   *  `FinishedAfter` past. */
  private def over(t: TrackedRaid, at: Instant): Boolean = {
    val lastMillis = RaidTypeCatalog.get(t.raidTypeId).map(_.broadcasts).filter(_.nonEmpty)
      .map(_.last.millis).getOrElse(0L)
    t.anchor.exists(a => at.isAfter(a.plusMillis(lastMillis).plus(FinishedAfter)))
  }

  /** Forget raids that have fully unfurled or that were never anchored and have
   *  sat unstarted too long. */
  private def pruneTracked(at: Instant): Unit =
    tracked.foreach { case (raidId, t) =>
      val stale = t.anchor.isEmpty && at.isAfter(t.firstSeen.plus(Duration.ofHours(2)))
      if (over(t, at) || stale) {
        tracked.remove(raidId)
        scheduledRaids.remove(raidId)
        wakes.keys.filter(_._1 == raidId).foreach(wakes.remove)
      }
    }
}

object ObserverRaidPoller {

  val AreaStage = 1
  val SubareaStage = 2
  val StartedStage = 3

  /** The feed's category for each stage, in order. */
  def stage(category: String): Int = category match {
    case "areaRevealed"    => AreaStage
    case "subareaRevealed" => SubareaStage
    case "raidStarted"     => StartedStage
    case _                 => 0
  }

  /** How long before a raid starts its subarea is revealed. Its area is revealed an
   *  hour before. Observer's own feed for a Carlin raid on Victoris (25 Sep 2026):
   *  area 03:13, subarea 03:58, start 04:13. It was 30 minutes here until then,
   *  so the imminent post named a reveal time that had already passed and the
   *  poll meant to catch the subarea ran a quarter of an hour early. */
  val SubareaLead: Duration = Duration.ofMinutes(15)

  /** How long after its last broadcast a raid counts as over. Until then everything
   *  it has is posted, however late — a restart mid-raid catches up; after, nothing. */
  val FinishedAfter: Duration = Duration.ofMinutes(30)

  /** One view of a raid from every entry the feed has for it — one per stage, and
   *  one per linked account that can see it. Its stage is the furthest any entry
   *  has reached; its area, subarea and start are whichever entry has them; and
   *  its type is one the catalogue `known`s, if any entry names one — an account
   *  with limited discoveries is told nothing of which raid it is until the start,
   *  while a better-explored one may be told at once. */
  def merge(entries: List[RaidAnnouncement], known: Int => Boolean): RaidAnnouncement = {
    val latest = entries.maxBy(e => stage(e.category))
    val typeId = entries.map(_.raidTypeId).find(known)
      .orElse(entries.map(_.raidTypeId).find(_ != 0))
      .getOrElse(latest.raidTypeId)
    latest.copy(
      area = entries.map(_.area).find(a => a != null && a.nonEmpty).getOrElse(latest.area),
      subarea = entries.flatMap(_.subarea).find(_.nonEmpty),
      startDate = latest.startDate.orElse(entries.flatMap(_.startDate).headOption),
      raidTypeId = typeId)
  }
}
