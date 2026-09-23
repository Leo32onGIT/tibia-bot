package com.tibiabot.observer

import com.tibiabot.domain.{MiniWorldChange, RaidAnnouncement}
import com.tibiabot.persistence.RedisCache
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import java.time.{Duration, Instant}
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
 *  The copies carry a TTL of [[ObserverFeed.PublishedFor]], a little over two of
 *  the primary's slowest polls. A secondary whose primary has stopped therefore
 *  sees the feed go *missing* — which reads as a failed fetch, never as every
 *  change ending — rather than going on serving a morning-old copy as current.
 *
 *  @param fetchMwc   the pool straight from the API; None when any account's fetch
 *                    failed, since a partial pool would read as changes ending
 *  @param fetchRaids the raid pool straight from the API */
final class ObserverFeed(
  mode: ObserverFeed.Mode,
  fetchMwc: () => Option[Map[String, List[MiniWorldChange]]],
  fetchRaids: () => Map[String, List[RaidAnnouncement]],
  cache: RedisCache,
  now: () => Instant = () => Instant.now()
) extends StrictLogging {
  import ObserverFeed._

  /** The last good mini world change pool this bot saw, and when. */
  @volatile private var mwcSeen: (Instant, Map[String, List[MiniWorldChange]]) = (Instant.EPOCH, Map.empty)

  /** The mini world changes now, keyed by lower-cased world: fetched (and published)
   *  on a bot that talks to the API, read from the primary's copy on one that does
   *  not. None when that failed or the copy is missing — the watcher sits such a
   *  poll out rather than read it as every change ending. */
  def refreshMwc(): Option[Map[String, List[MiniWorldChange]]] = {
    val result = mode match {
      case Off      => None
      case Consumer => read(MwcKey).flatMap(FeedJson.parseMwc)
      case Standalone | Publisher =>
        val fetched = fetchMwc()
        if (mode == Publisher) fetched.foreach(pool => write(MwcKey, FeedJson.mwc(pool)))
        fetched
    }
    result.foreach(pool => mwcSeen = (now(), pool))
    result
  }

  /** A world's active mini world changes, for the server-save message. */
  def mwcForWorld(world: String): List[MiniWorldChange] =
    mwcNow().getOrElse(world.toLowerCase, Nil)

  /** The active changes across several worlds — a linked account's, for the
   *  boosted DM. */
  def mwcForWorlds(worlds: Seq[String]): List[MiniWorldChange] = {
    val pool = mwcNow()
    worlds.map(_.toLowerCase).distinct.flatMap(w => pool.getOrElse(w, Nil)).toList
  }

  /** The pool, reused for `ReuseFor` — the server-save repost asks once per
   *  guild — and refreshed after that. A refresh that fails falls back to the last
   *  good pool while it is younger than `PublishedFor`, so one sidecar hiccup at
   *  server save does not leave every guild's message without its changes. */
  private def mwcNow(): Map[String, List[MiniWorldChange]] = {
    val (seenAt, seen) = mwcSeen
    if (seenAt.plus(ReuseFor).isAfter(now())) seen
    else refreshMwc().getOrElse {
      if (seenAt.plus(Duration.ofMillis(PublishedFor.toMillis)).isAfter(now())) seen else Map.empty
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
      if (mode == Publisher) write(RaidsKey, FeedJson.raids(fetched))
      fetched
  }

  private def read(key: String): Option[String] =
    Try(Await.result(cache.get(key), 5.seconds)).toOption.flatten

  private def write(key: String, value: String): Unit =
    Try(Await.result(cache.setEx(key, value, PublishedFor), 5.seconds)).failed.foreach { ex =>
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

  /** How long a published copy lives: a little over two of the primary's slowest
   *  polls (every 15 minutes outside the server-save window), so one slow or failed
   *  poll does not blank the secondaries. */
  val PublishedFor: FiniteDuration = 35.minutes

  /** How long a pool is reused before asking again. */
  val ReuseFor: Duration = Duration.ofMinutes(2)
}

/** The published copies' wire format: `{world: [change, …]}` and `{world: [raid, …]}`. */
private[observer] object FeedJson {

  private def str(o: JsObject, key: String): Option[String] =
    o.fields.get(key).collect { case JsString(s) => s }

  def mwc(pool: Map[String, List[MiniWorldChange]]): String =
    JsObject(pool.map { case (world, changes) =>
      world -> JsArray(changes.map(c =>
        JsObject("world" -> JsString(c.world), "title" -> JsString(c.title), "body" -> JsString(c.body))).toVector)
    }).compactPrint

  def parseMwc(body: String): Option[Map[String, List[MiniWorldChange]]] =
    Try(body.parseJson.asJsObject.fields.map { case (world, value) =>
      world -> (value match {
        case JsArray(items) => items.collect { case o: JsObject =>
          MiniWorldChange(str(o, "world").getOrElse(""), str(o, "title").getOrElse(""), str(o, "body").getOrElse(""))
        }.toList
        case _ => Nil
      })
    }).toOption

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
