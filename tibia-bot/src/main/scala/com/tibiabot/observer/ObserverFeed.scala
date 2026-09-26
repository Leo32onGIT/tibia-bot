package com.tibiabot.observer

import com.tibiabot.domain.{MiniWorldChange, RaidAnnouncement}
import com.tibiabot.domain.time.Clock
import com.tibiabot.persistence.RedisCache
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/** Where a bot's Observer data comes from: the pooled mini world changes and raids
 *  the notifications message, the boosted DM and the raids channels are built from.
 *
 *  The same shape as the fansite pipeline. Every Observer request leaves from one
 *  bot — the primary, which runs the sidecar — so only it fetches, and it publishes
 *  what it fetched to Redis. A secondary never calls the API: it reads the
 *  primary's copy. A bot that is neither (a single-bot deployment) fetches for
 *  itself and publishes nothing.
 *
 *  ==How long a copy is good for==
 *  Mini world changes are fixed from one server save to the next, so a pool
 *  fetched since the latest server save is current however long ago that was, and
 *  one fetched before it is yesterday's. The published copy is stamped with when
 *  it was fetched, and a pool is used — by a secondary reading the copy, or by any
 *  bot falling back on its last good pool when a fetch fails — exactly while it was
 *  fetched since the latest server save. A secondary whose primary has stopped
 *  therefore goes on with the day's changes, and shows none rather than
 *  yesterday's once a server save passes without a fresh copy.
 *
 *  Being fetched since the server save is not enough on its own, though: the feed
 *  goes on reporting yesterday's changes for a while after it. So the copy also
 *  keeps, per world, since when its changes have been what they are, and a world's
 *  are only handed out once they differ from what it had before the save — see
 *  `todays`. Until then the world has none, so the server-save message goes out
 *  without them, and MiniWorldChangeWatcher adds them when they come in.
 *
 *  Raids change by the minute, so their copy just expires: after
 *  [[ObserverFeed.PublishedFor]], a little over two of the primary's slowest polls.
 *
 *  @param fetchMwc       the pool straight from the API; None when an account's
 *                        fetch failed with no share of it since the latest server
 *                        save to fall back on, since a partial pool would read as
 *                        changes ending (see ObserverService.fetchPooledMwc)
 *  @param fetchRaids     the raid pool straight from the API
 *  @param lastServerSave the latest server save at or before an instant */
final class ObserverFeed(
  mode: ObserverFeed.Mode,
  fetchMwc: () => Option[Map[String, List[MiniWorldChange]]],
  fetchRaids: () => Map[String, List[RaidAnnouncement]],
  cache: RedisCache,
  now: () => Instant = () => Instant.now(),
  lastServerSave: Instant => Instant = at =>
    ServerSaveSchedule.lastServerSave(at.atZone(Clock.Berlin)).toInstant
) extends StrictLogging {
  import ObserverFeed._

  /** The last good mini world change pool this bot saw, and when it last asked for
   *  one (for `ReuseFor`) — which on a secondary is not when it was fetched. */
  @volatile private var mwcSeen: Option[(Instant, MwcCopy)] = None

  /** How the primary's copy last read, on a secondary: logged when it changes. */
  @volatile private var copyState: CopyState = CopyState.Current

  /** Set when the pool was asked for and there was none to give, so a message went
   *  out without its changes — see `takeAnsweredWithout`. */
  private val answeredWithout = new AtomicBoolean(false)

  private def current(copy: MwcCopy, at: Instant): Boolean =
    !copy.fetchedAt.isBefore(lastServerSave(at))

  /** The worlds in a copy whose changes are the day's. A copy fetched before the
   *  latest server save has none. One fetched since has a world's changes once they
   *  have been what they are only since the save — they moved on from yesterday's,
   *  or the world had none before it. A world that really does keep yesterday's
   *  changes gets them from a copy fetched `SameAsYesterdayAfter` past the save,
   *  when the feed has had its chance to roll over. */
  private def todays(copy: MwcCopy, at: Instant): Map[String, List[MiniWorldChange]] = {
    val save = lastServerSave(at)
    if (copy.fetchedAt.isBefore(save)) Map.empty
    else if (!copy.fetchedAt.isBefore(save.plus(SameAsYesterdayAfter))) copy.pool
    else copy.pool.filter { case (world, _) => !copy.seenSince(world).isBefore(save) }
  }

  /** Since when each world's changes in a fresh pool have been what they are:
   *  carried over from the last good copy for a world whose changes are the same
   *  as there, `at` for one whose changes are new. A primary that has not fetched
   *  since it started carries them over from its own published copy, so a restart
   *  just after server save does not take yesterday's changes for new ones. */
  private def seenSince(pool: Map[String, List[MiniWorldChange]], at: Instant): Map[String, Instant] = {
    val previous = mwcSeen.map(_._2).orElse(if (mode == Publisher) read(MwcKey).flatMap(FeedJson.parseMwc) else None)
    val before = previous.map(p => MiniWorldChangeWatcher.signatures(p.pool)).getOrElse(Map.empty)
    MiniWorldChangeWatcher.signatures(pool).map { case (world, changes) =>
      world -> previous.filter(_ => before.get(world).contains(changes)).map(_.seenSince(world)).getOrElse(at)
    }
  }

  /** The day's mini world changes now (see `todays`), keyed by lower-cased world:
   *  fetched (and published) on a bot that talks to the API, read from the
   *  primary's copy on one that does not. None when that failed, or the copy is
   *  missing or from before the latest server save — the watcher sits such a poll
   *  out rather than read it as every change ending. */
  def refreshMwc(): Option[Map[String, List[MiniWorldChange]]] = {
    val at = now()
    val result = mode match {
      case Off      => None
      case Consumer => readCopy(at)
      case Standalone | Publisher =>
        val fetched = fetchMwc().map(pool => MwcCopy(at, pool, seenSince(pool, at)))
        if (mode == Publisher) fetched.foreach(copy => write(MwcKey, FeedJson.mwc(copy), MwcKeptFor))
        fetched
    }
    result.foreach(copy => mwcSeen = Some(at -> copy))
    result.map(todays(_, at))
  }

  /** The primary's copy, when it is current. Whether it is missing, from before the
   *  latest server save, or back again is logged once each time that changes —
   *  nothing else on a secondary would say why its messages have no changes. */
  private def readCopy(at: Instant): Option[MwcCopy] = {
    val copy = read(MwcKey).flatMap(FeedJson.parseMwc)
    val state = copy match {
      case None                        => CopyState.Missing
      case Some(c) if !current(c, at)  => CopyState.Stale
      case Some(_)                     => CopyState.Current
    }
    if (state != copyState) {
      copyState = state
      state match {
        case CopyState.Missing =>
          logger.warn(s"The primary's mini world changes are missing from Redis ('$MwcKey'); is it publishing them?")
        case CopyState.Stale =>
          logger.info(s"The primary's mini world changes were fetched at ${copy.map(_.fetchedAt).orNull}, " +
            "before the latest server save; waiting for a fresh copy")
        case CopyState.Current =>
          logger.info(s"Reading the primary's mini world changes again (fetched at ${copy.map(_.fetchedAt).orNull})")
      }
    }
    copy.filter(_ => state == CopyState.Current)
  }

  /** Whether a message has gone out without its changes since this was last asked
   *  — the pool was asked for and none since the latest server save could be had.
   *  Asking clears it. The watcher asks after each good poll and amends every
   *  world's message then, since those messages would otherwise wait for their
   *  world's changes to move on. */
  def takeAnsweredWithout(): Boolean = answeredWithout.getAndSet(false)

  /** A world's active mini world changes, for the server-save message: none while
   *  the feed still reports yesterday's (see `todays`). */
  def mwcForWorld(world: String): List[MiniWorldChange] =
    mwcNow().getOrElse(world.toLowerCase, Nil)

  /** The pool, reused for `ReuseFor` — the server-save repost asks once per
   *  guild — and refreshed after that; one fetched once the day's changes had
   *  settled, `SameAsYesterdayAfter` past the save, is reused until the next save,
   *  since they don't change before it. A refresh that fails falls back to the last
   *  good pool while it is from since the latest server save, so a failed fetch at
   *  any point in the day never costs a message the day's changes. */
  private def mwcNow(): Map[String, List[MiniWorldChange]] = {
    val at = now()
    def settled(copy: MwcCopy) = !copy.fetchedAt.isBefore(lastServerSave(at).plus(SameAsYesterdayAfter))
    mwcSeen match {
      case Some((askedAt, copy)) if current(copy, at) && (askedAt.plus(ReuseFor).isAfter(at) || settled(copy)) =>
        todays(copy, at)
      case _ =>
        refreshMwc().orElse(mwcSeen.map(_._2).filter(current(_, at)).map(todays(_, at))).getOrElse {
          answeredWithout.set(true)
          Map.empty
        }
    }
  }

  /** The raids announced now, keyed by world: fetched (and published) on a bot that
   *  talks to the API, the primary's copy on one that does not. Empty when that
   *  failed or the copy is missing. */
  def raidsByWorld(): Map[String, List[RaidAnnouncement]] = mode match {
    case Off      => Map.empty
    case Consumer => read(RaidsKey).flatMap(FeedJson.parseRaids).getOrElse(Map.empty)
    case Standalone | Publisher =>
      val fetched = fetchRaids()
      if (mode == Publisher) {
        val copy = FeedJson.raids(fetched)
        write(RaidsKey, copy, PublishedFor)
        // After the write, so a secondary that reacts reads the new copy. Only on a
        // change: every poll would otherwise send every secondary polling too.
        if (!lastRaidsCopy.contains(copy)) {
          lastRaidsCopy = Some(copy)
          Try(Await.result(cache.publish(RaidsChangedChannel, now().toString), 5.seconds)).failed.foreach { ex =>
            logger.warn(s"Could not announce the new Observer raids copy: ${ex.getMessage}")
          }
        }
      }
      fetched
  }

  /** The raids copy this bot last published, to announce only real changes. */
  @volatile private var lastRaidsCopy: Option[String] = None

  /** On a secondary: run `react` whenever the primary announces a new raids copy,
   *  so the raids channels follow the primary within moments instead of on a
   *  guessed delay. `react` is handed the work to run elsewhere — it is called on
   *  the Redis connection's thread. The returned Future fails when the subscription
   *  could not be set up; the secondary's own sweep still runs either way. */
  def onRaidsChanged(react: () => Unit): scala.concurrent.Future[Unit] =
    cache.subscribe(RaidsChangedChannel)(_ => react())

  private def read(key: String): Option[String] =
    Try(Await.result(cache.get(key), 5.seconds)).toOption.flatten

  private def write(key: String, value: String, keepFor: FiniteDuration): Unit =
    Try(Await.result(cache.setEx(key, value, keepFor), 5.seconds)).failed.foreach { ex =>
      logger.warn(s"Could not publish the Observer feed to '$key': ${ex.getMessage}")
    }
}

object ObserverFeed {

  /** What this bot does with the Observer feeds. */
  sealed trait Mode
  /** Observer is switched off here. */
  case object Off extends Mode
  /** A single-bot deployment: fetches for itself, publishes nothing. */
  case object Standalone extends Mode
  /** The primary: fetches, and publishes for the secondaries. */
  case object Publisher extends Mode
  /** A secondary: reads the primary's copy and never calls the API. */
  case object Consumer extends Mode

  val MwcKey = "tibia:observer:mwc"
  val RaidsKey = "tibia:observer:raids"

  /** Where the primary announces that the raids copy changed. */
  val RaidsChangedChannel = "tibia:observer:raids-changed"

  /** How long the raids copy lives: a little over two of the primary's slowest
   *  polls (every 15 minutes outside the server-save window), so one slow or failed
   *  poll does not blank the secondaries. */
  val PublishedFor: FiniteDuration = 35.minutes

  /** How long the mini world change copy lives. Not what decides whether it is
   *  used — its stamp does, against the latest server save — only a clean-up, a
   *  little over a day so a stopped primary's copy does not sit in Redis for good. */
  val MwcKeptFor: FiniteDuration = 25.hours

  /** How long a pool is reused before asking again. */
  val ReuseFor: Duration = Duration.ofMinutes(2)

  /** How long after server save a world's changes can still be yesterday's: the
   *  server-save window, which the watcher polls closely for them to roll over.
   *  A copy fetched later than this shows a world's changes even when they are the
   *  same as before the save — that world really does have them again. */
  val SameAsYesterdayAfter: Duration = Duration.ofMinutes(45)

  /** A mini world change pool, when it was fetched from the API, and since when
   *  each world's changes have been what they are. A world with no stamp (a copy
   *  published before copies carried them) counts from when it was fetched. */
  private[observer] final case class MwcCopy(fetchedAt: Instant, pool: Map[String, List[MiniWorldChange]],
                                             since: Map[String, Instant] = Map.empty) {
    def seenSince(world: String): Instant = since.getOrElse(world, fetchedAt)
  }

  /** How the primary's copy read last time, on a secondary. */
  private sealed trait CopyState
  private object CopyState {
    case object Current extends CopyState
    case object Stale extends CopyState
    case object Missing extends CopyState
  }
}

/** The published copies' wire format: `{fetchedAt, worlds: {world: [change, …]},
 *  since: {world: instant}}` and `{world: [raid, …]}`. */
private[observer] object FeedJson {
  import ObserverFeed.MwcCopy

  private def str(o: JsObject, key: String): Option[String] =
    o.fields.get(key).collect { case JsString(s) => s }

  def mwc(copy: MwcCopy): String =
    JsObject(
      "fetchedAt" -> JsString(copy.fetchedAt.toString),
      "worlds" -> JsObject(copy.pool.map { case (world, changes) =>
        world -> JsArray(changes.map(c =>
          JsObject("world" -> JsString(c.world), "title" -> JsString(c.title), "body" -> JsString(c.body))).toVector)
      }),
      "since" -> JsObject(copy.since.map { case (world, at) => world -> JsString(at.toString) })).compactPrint

  /** None for a copy with no stamp — one published before copies carried it — as
   *  well as one that does not parse: there is no telling which day it is from. A
   *  copy without the per-world `since` stamps is fine: its worlds count from when
   *  it was fetched. */
  def parseMwc(body: String): Option[MwcCopy] =
    Try {
      val root = body.parseJson.asJsObject
      val since = root.fields.get("since").collect { case o: JsObject =>
        o.fields.flatMap { case (world, value) =>
          value match {
            case JsString(at) => Try(Instant.parse(at)).toOption.map(world -> _)
            case _            => None
          }
        }
      }.getOrElse(Map.empty)
      for {
        fetchedAt <- str(root, "fetchedAt").flatMap(d => Try(Instant.parse(d)).toOption)
        worlds    <- root.fields.get("worlds").collect { case o: JsObject => o }
      } yield MwcCopy(fetchedAt, worlds.fields.map { case (world, value) =>
        world -> (value match {
          case JsArray(items) => items.collect { case o: JsObject =>
            MiniWorldChange(str(o, "world").getOrElse(""), str(o, "title").getOrElse(""), str(o, "body").getOrElse(""))
          }.toList
          case _ => Nil
        })
      }, since)
    }.toOption.flatten

  def raids(pool: Map[String, List[RaidAnnouncement]]): String =
    JsObject(pool.map { case (world, raids) =>
      world -> JsArray(raids.map(r => JsObject(
        Map(
          "raidId" -> JsString(r.raidId), "world" -> JsString(r.world), "area" -> JsString(r.area),
          "category" -> JsString(r.category), "raidTypeId" -> JsNumber(r.raidTypeId)) ++
          r.subarea.map(s => "subarea" -> JsString(s)) ++
          r.startDate.map(d => "startDate" -> JsString(d.toString))
      )).toVector)
    }).compactPrint

  def parseRaids(body: String): Option[Map[String, List[RaidAnnouncement]]] =
    Try(body.parseJson.asJsObject.fields.map { case (world, value) =>
      world -> (value match {
        case JsArray(items) => items.collect { case o: JsObject =>
          RaidAnnouncement(
            str(o, "raidId").getOrElse(""),
            str(o, "world").getOrElse(""),
            str(o, "area").getOrElse(""),
            str(o, "subarea"),
            str(o, "category").getOrElse(""),
            str(o, "startDate").flatMap(d => Try(Instant.parse(d)).toOption),
            o.fields.get("raidTypeId").collect { case JsNumber(n) => n.toInt }.getOrElse(0))
        }.toList
        case _ => Nil
      })
    }).toOption
}
