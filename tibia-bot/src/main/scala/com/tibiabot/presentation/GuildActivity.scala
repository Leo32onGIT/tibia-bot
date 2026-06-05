package com.tibiabot.presentation

/** Pure mappings for the guild-tracking activity notifications (a player
 *  joining / leaving / swapping a hunted or allied guild). Extracted from the
 *  activity block in TibiaBot, where both were repeated for each activity case. */
object GuildActivity {

  /** Embed colour for a guild-join/swap activity: a hunted guild is red, an
   *  allied guild is green, and anything else is yellow. */
  def activityColor(huntedGuild: Boolean, alliedGuild: Boolean): Int =
    if (huntedGuild) 13773097 else if (alliedGuild) 36941 else 14397256

  /** The guild's tracked-status label, used in the activity description. */
  def guildType(huntedGuild: Boolean, alliedGuild: Boolean): String =
    if (huntedGuild) "hunted" else if (alliedGuild) "allied" else "neutral"
}
