package com.tibiabot.scheduler

import java.time.{Duration, ZonedDateTime}

/** Pure decision for the daily inactive-guild prune sweep — see
 *  BotApp.pruneInactiveGuilds. */
object GuildPruneRule {

  /** A guild with no worlds tracked leaves once it's been worldless for at
   *  least `worldlessThresholdDays`, unless a command's been run there
   *  within `activityThresholdDays` — the wider activity window overrides
   *  the shorter worldless one, since someone using personal commands
   *  (galthen/boosted) is still genuinely using the bot, just not for world
   *  tracking. */
  def shouldLeave(
    worldlessSince: ZonedDateTime,
    lastCommandAt: Option[ZonedDateTime],
    now: ZonedDateTime,
    worldlessThresholdDays: Long = 14,
    activityThresholdDays: Long = 30
  ): Boolean = {
    val daysWorldless = Duration.between(worldlessSince, now).toDays
    val recentlyActive = lastCommandAt.exists(at => Duration.between(at, now).toDays < activityThresholdDays)
    daysWorldless >= worldlessThresholdDays && !recentlyActive
  }
}
