package com.tibiabot
package app

import org.apache.pekko.actor.ActorSystem
import com.tibiabot.tibiadata.{TibiaApi, TibiaDataClient}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/** Builds the character-fetching stack one world polls through.
 *
 *  Extracted from [[com.tibiabot.TibiaBot]] because a second caller now needs
 *  exactly the same arrangement: the primary's fetch-only poller, which exists
 *  to fill the shared cache for worlds only a secondary serves (see
 *  [[UnionFetchReconciler]]). Two hand-rolled copies of this would drift, and
 *  the order of these decorators is not arbitrary — each layer's doc explains
 *  why it sits where it does, and a fetcher assembled slightly differently
 *  would publish subtly different things to the cache the other bots read.
 *
 *  One instance per world, since the age cache inside holds that world's
 *  characters and nothing else. */
object CharacterApiStack {

  /** One presence check for the whole process, shared by every world's stack.
   *  It caches its answer, so sharing it costs one Redis read per refresh
   *  window rather than one per world per poll.
   *
   *  `None` — anything but a consume-only secondary — restores the original
   *  behaviour of fetching a character the primary has not published. */
  private val sharedPresenceHolder = new java.util.concurrent.atomic.AtomicReference[tibiadata.PrimaryPresence]()

  private def presenceFor()(implicit ec: ExecutionContext): Option[tibiadata.PrimaryPresence] =
    if (!Config.BotRole.consumeOnlyActive) None
    else {
      val existing = sharedPresenceHolder.get()
      if (existing != null) Some(existing)
      else {
        val created = new tibiadata.PrimaryPresence(
          persistence.RedisCacheProvider.cache, Config.BotRole.heartbeatInterval)
        sharedPresenceHolder.compareAndSet(null, created)
        Some(sharedPresenceHolder.get())
      }
    }

  /** @param pollInterval the caller's real tick — the age cache rounds each
   *                      character's next fetch to the nearest one, so a value
   *                      that disagreed with the actual schedule would quietly
   *                      cost a whole interval of latency. */
  def forWorld(pollInterval: FiniteDuration)(implicit system: ActorSystem, ec: ExecutionContext): TibiaApi = {
    val caching = new tibiadata.CachingTibiaApi(new TibiaDataClient(), persistence.RedisCacheProvider.cache)
    val shared =
      if (Config.BotRole.sharingEnabled)
        new tibiadata.SharedWorldTibiaApi(caching, persistence.RedisCacheProvider.cache, Config.BotRole.current,
          characterTtl = Config.CharacterCache.ttl,
          primaryPresence = presenceFor())
      else caching

    // Age cache outermost over each source, so a skippable character fetch
    // costs nothing at all — not the request, and not the shared-cycle Redis
    // read in front of it either.
    def ageCached(source: TibiaApi): TibiaApi =
      if (Config.CharacterCache.enabled) new tibiadata.AgeCachedTibiaApi(source, Config.CharacterCache.settings(pollInterval))
      else source

    val tibiaDataSource = ageCached(shared)
    if (Config.FansiteApi.enabled) {
      // Each source gets its own age cache, so each keeps its own schedule and
      // its own phase; DualCharacterApi only chooses between what they hold.
      // The fansite client's delegate is never reached through this path — the
      // endpoints it would serve are routed to TibiaData above it — but it is
      // wired to the real stack rather than to a stub so the class stays usable
      // on its own.
      val fansiteClient = new fansiteapi.FansiteApiClient(shared, Config.FansiteApi.token)
      // Published under its own Redis prefix, so a shared-cycle secondary can
      // read both sources' sheets and race them to the same answer the primary
      // reached, without calling either API. The world endpoint on this
      // instance is never reached (DualCharacterApi routes it to TibiaData), so
      // it cannot contend for the shared world key.
      val fansiteShared =
        if (Config.BotRole.sharingEnabled)
          new tibiadata.SharedWorldTibiaApi(fansiteClient, persistence.RedisCacheProvider.cache, Config.BotRole.current,
            characterTtl = Config.CharacterCache.ttl,
            characterKeyPrefix = tibiadata.SharedWorldTibiaApi.FansiteCharacterKeyPrefix,
            primaryPresence = presenceFor())
        else fansiteClient
      new fansiteapi.DualCharacterApi(
        tibiaData = tibiaDataSource,
        fansite = ageCached(fansiteShared),
        mode = Config.FansiteApi.mode,
        phaseOffset = pollInterval * Config.FansiteApi.phaseOffsetTicks.toLong,
        maxStale = Config.CharacterCache.maxStale,
        secondaryGrace = Config.FansiteApi.secondaryGrace,
        scheduler = system.scheduler,
        // Only the characters somebody asked to watch, and only as many of
        // those as the paced lane can afford — see fansiteapi.FansiteRoster.
        fansiteEligible = fansiteapi.FansiteRoster.shared.admits)
    } else tibiaDataSource
  }
}
