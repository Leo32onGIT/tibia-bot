package com.tibiabot.tibiadata.response

/** One race's line in a world's kill statistics.
 *
 *  `killed` counts the race dying, `playersKilled` counts it killing players.
 *  Both come in a "last day" and a "last week" flavour; the day figures reset at
 *  server save and are what the daily snapshot keeps, while the week ones give a
 *  cold start seven days of history it would otherwise have to wait for.
 *
 *  Field names are the endpoint's, since spray-json derives the format from them.
 *
 *  Two entries are not creatures and are handled apart everywhere they matter —
 *  see [[com.tibiabot.statistics.KillStatistics]]:
 *   - `players` is where PvP deaths are counted, and would otherwise win
 *     "creature that killed the most players" on every world every day.
 *   - `(elemental forces)` is environmental damage. */
case class KillStatisticsEntry(
    race: String,
    last_day_players_killed: Int,
    last_day_killed: Int,
    last_week_players_killed: Int,
    last_week_killed: Int
)

/** The same four figures summed over every race on the world. */
case class KillStatisticsTotal(
    last_day_players_killed: Int,
    last_day_killed: Int,
    last_week_players_killed: Int,
    last_week_killed: Int
)

case class KillStatisticsData(
    world: String,
    entries: List[KillStatisticsEntry],
    total: KillStatisticsTotal
)

case class KillStatisticsResponse(killstatistics: KillStatisticsData, information: Information)
