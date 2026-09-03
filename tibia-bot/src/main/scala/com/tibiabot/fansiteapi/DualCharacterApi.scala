package com.tibiabot.fansiteapi

import com.tibiabot.Config
import com.tibiabot.tibiadata.response._
import com.tibiabot.tibiadata.{OriginTimestamp, TibiaApi}
import com.typesafe.scalalogging.StrictLogging

import org.apache.pekko.actor.Scheduler
import org.apache.pekko.pattern.after

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Runs both character upstreams and decides what the bot is told.
 *
 *  Each source arrives already wrapped in its own
 *  [[com.tibiabot.tibiadata.AgeCachedTibiaApi]], which keeps this class small: the
 *  scheduling, skipping and replay happen below, so this sees each source's latest
 *  copy and only has to choose.
 *
 *  '''Why running both halves the wait rather than doubling the work.''' Both
 *  upstreams cache a character for 300s and rebuild lazily on the first request
 *  after expiry, so each window's phase is set by when we first ask — ours to
 *  impose. Seeding the second `phaseOffset` after the first leaves them rebuilding
 *  in turn, and a death becomes visible at the next rebuild of *either*: mean wait
 *  falls from about half a window to a quarter, for one extra request per
 *  character per window.
 *
 *  '''Monotonicity is not optional.''' Alternating sources means the sheet handed
 *  downstream could go backwards whenever a fetch fails or phases drift, and the
 *  stream reacts badly — `TibiaBot` already carries a six-minute settle on rename
 *  detection because a regressing sheet makes a rename appear, vanish and reappear.
 *  So a copy older than the last one served is never returned. With correct phases
 *  this never fires; it exists for when they are not. */
final class DualCharacterApi(
    tibiaData: TibiaApi,
    fansite: TibiaApi,
    mode: Config.FansiteApi.Mode,
    phaseOffset: FiniteDuration,
    maxStale: FiniteDuration,
    secondaryGrace: FiniteDuration,
    scheduler: Scheduler,
    now: () => Instant = () => Instant.now(),
    fansiteEligible: String => Boolean = _ => true
)(implicit ec: ExecutionContext)
    extends TibiaApi with StrictLogging {

  import DualCharacterApi.Seen

  private val seen = new ConcurrentHashMap[String, Seen]()
  @volatile private var lastPruneAt: Instant = Instant.EPOCH

  private def key(name: String): String = name.toLowerCase

  /** Drop characters nothing is tracking any more, on the same schedule and for
   *  the same reason as the age cache below: this map only grows while a world
   *  is polling, so there is nothing to clean while nothing is being fetched. */
  private def pruneIfDue(at: Instant): Unit =
    if (at.isAfter(lastPruneAt.plusSeconds(maxStale.toSeconds))) {
      lastPruneAt = at
      val cutoff = at.minusSeconds(maxStale.toSeconds)
      seen.entrySet().removeIf(e => e.getValue.firstSeen.isBefore(cutoff) && e.getValue.lastOrigin.forall(_.isBefore(cutoff)))
    }

  /** Whether the second source has been held back long enough to open its
   *  window out of phase with the first.
   *
   *  This is the seeding, and it only has to happen once per character: after
   *  the first fetch the source's own age cache schedules it on its own 300s
   *  rhythm, and the offset persists without further help. */
  private def secondaryDue(entry: Seen, at: Instant): Boolean =
    !at.isBefore(entry.firstSeen.plusSeconds(phaseOffset.toSeconds))

  private def originOf(sheet: CharacterResponse): Option[Instant] = OriginTimestamp.of(sheet.information)

  /** Choose between two answers, newest wins. A Right always beats a Left — an
   *  error from one source is what the other is here to cover, which is where the
   *  second upstream stops being a latency win and becomes failover. Between two
   *  Rights the newer origin wins, and a sheet whose origin cannot be read loses,
   *  since "unknown age" cannot be shown to be an improvement. */
  private def fresher(
      left: Either[String, CharacterResponse],
      right: Either[String, CharacterResponse]
  ): Either[String, CharacterResponse] =
    (left, right) match {
      case (Right(l), Right(r)) =>
        (originOf(l), originOf(r)) match {
          case (Some(lo), Some(ro)) => if (ro.isAfter(lo)) Right(r) else Right(l)
          case (None, Some(_))      => Right(r)
          case _                    => Right(l)
        }
      case (Right(l), _) => Right(l)
      case (_, Right(r)) => Right(r)
      case (l, _)        => l
    }

  /** Refuse a sheet older than the one already served for this character. See
   *  the class doc — this protects the rename and guild-change logic from
   *  seeing history run backwards. */
  private def monotonic(cacheKey: String, entry: Seen, chosen: Either[String, CharacterResponse]): Either[String, CharacterResponse] =
    chosen match {
      case Right(sheet) =>
        val chosenOrigin = originOf(sheet)
        (entry.lastServed, chosenOrigin) match {
          case (Some(previous), Some(current)) if current.isBefore(previous.origin) =>
            logger.debug(s"Ignoring a character sheet for '$cacheKey' older than the one already served (${current} < ${previous.origin})")
            Right(previous.sheet)
          case _ =>
            chosenOrigin.foreach(origin => seen.computeIfPresent(cacheKey, (_, e) => e.served(sheet, origin)))
            chosen
        }
      case left => left
    }

  private def compare(tibiaDataResult: Either[String, CharacterResponse], fansiteResult: Either[String, CharacterResponse]): Unit =
    (tibiaDataResult, fansiteResult) match {
      case (Right(l), Right(r)) =>
        val divergence = CharacterDivergence.between(l, r)
        if (divergence.stable.nonEmpty) logger.warn(s"Fansite shadow divergence — ${divergence.describe}")
        else if (divergence.volatile.nonEmpty) logger.debug(s"Fansite shadow drift — ${divergence.describe}")
      case (Right(_), Left(error)) =>
        logger.debug(s"Fansite shadow: no answer from the fansite API where TibiaData had one: $error")
      case (Left(error), Right(_)) =>
        logger.debug(s"Fansite shadow: the fansite API answered where TibiaData did not: $error")
      case _ => ()
    }

  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    val at = now()
    pruneIfDue(at)
    val cacheKey = key(name)
    val entry = seen.computeIfAbsent(cacheKey, _ => Seen(firstSeen = at, lastServed = None))

    if (!fansiteEligible(name) || !secondaryDue(entry, at)) {
      // Either this character has not earned a place in the paced budget (see
      // FansiteRoster), or the second source is still being held back so its
      // window opens out of phase. Same path either way: one source, no cost.
      tibiaData.getCharacter(name)
    } else {
      // Both are launched together; only the primary is ever waited on.
      val primary = tibiaData.getCharacter(name)
      val secondary = fansite.getCharacter(name)
      primary.flatMap { tibiaDataResult =>
        withinGrace(secondary).map {
          case None =>
            // The secondary did not land in time. Its fetch is still in flight
            // and will fill its own age cache, so the next poll gets the
            // benefit — nothing is wasted by giving up on it here.
            tibiaDataResult
          case Some(fansiteResult) =>
            mode match {
              case Config.FansiteApi.Shadow =>
                compare(tibiaDataResult, fansiteResult)
                tibiaDataResult
              case Config.FansiteApi.Race =>
                monotonic(cacheKey, entry, fresher(tibiaDataResult, fansiteResult))
              case Config.FansiteApi.Off =>
                tibiaDataResult
            }
        }
      }
    }
  }

  /** The secondary's answer if it arrives within `secondaryGrace`, else None.
   *
   *  The second source only ever buys a fresher sheet than the one already in
   *  hand, so waiting on it can never be worth delaying a death — and once its
   *  concurrency is capped low enough not to get the IP blocked, a due fetch
   *  really can sit queued. Bounding the wait is what keeps a throttle on the
   *  new upstream from turning into latency on the old one.
   *
   *  A failure counts as "did not land": the primary's answer already covers
   *  it, and the failure itself is logged where it happens. */
  private def withinGrace(
      secondary: Future[Either[String, CharacterResponse]]
  ): Future[Option[Either[String, CharacterResponse]]] =
    Future.firstCompletedOf(Seq(
      secondary.map(Option(_)).recover { case NonFatal(_) => None },
      after(secondaryGrace, scheduler)(Future.successful(None))
    ))

  /** A one-shot level lookup with no schedule behind it, so there is no phase
   *  to protect and nothing to compare against: ask the source least likely to
   *  refuse. The fansite API answers a missing character with a clean 404 where
   *  TibiaData serves a 502 page, and it is not the upstream carrying the
   *  online-list poll, so it goes first and TibiaData covers a failure. */
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] =
    if (mode == Config.FansiteApi.Off) tibiaData.getKillerFallback(name)
    else
      fansite.getKillerFallback(name).flatMap {
        case right @ Right(_) => Future.successful(right)
        case Left(_)          => tibiaData.getKillerFallback(name)
      }

  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] =
    getCharacter(input._1).map((_, input._1, input._2, input._3))

  /** Endpoints this API has no equivalent for — the online-list poll, guilds and
   *  the boosted pair stay on TibiaData exactly as before. */
  def getWorld(world: String): Future[Either[String, WorldResponse]] = tibiaData.getWorld(world)
  def getWorlds(): Future[Either[String, WorldsResponse]] = tibiaData.getWorlds()
  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = tibiaData.getBoostedBoss()
  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = tibiaData.getBoostedCreature()
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = tibiaData.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = tibiaData.getGuildWithInput(input)

  /** Characters currently being tracked for phase and monotonicity.
   *  Test/diagnostic only. */
  private[fansiteapi] def trackedCharacters: Int = seen.asScala.size
}

private[fansiteapi] object DualCharacterApi {

  /** The last sheet handed downstream for a character, kept so a later, older
   *  one can be refused without re-deriving what "older" means. */
  final case class Served(sheet: CharacterResponse, origin: Instant)

  /** Per-character state: when this character was first asked for (which sets
   *  the second source's phase) and what was last served (which keeps the
   *  sequence monotonic). */
  final case class Seen(firstSeen: Instant, lastServed: Option[Served]) {
    def served(sheet: CharacterResponse, origin: Instant): Seen = copy(lastServed = Some(Served(sheet, origin)))
    def lastOrigin: Option[Instant] = lastServed.map(_.origin)
  }
}
