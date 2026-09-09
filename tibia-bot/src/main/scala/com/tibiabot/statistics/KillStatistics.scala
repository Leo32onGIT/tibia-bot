package com.tibiabot.statistics

import com.tibiabot.tibiadata.response.{KillStatisticsData, KillStatisticsEntry}

import java.time.LocalDate

/** One boss's kills on one world on one server-save day. */
final case class BossKills(world: String, saveDay: LocalDate, race: String, killed: Int, playersKilled: Int)

/** A world's day in one row: the headlines, kept so the eventual embed does not
 *  have to store or re-read fifteen hundred races to say three things.
 *
 *  `mostKilled` is the creature players killed most of. `deadliest` is the
 *  creature that killed the most players — never `players` or
 *  `(elemental forces)`, which are not creatures; see
 *  [[KillStatistics.deadliestCreature]]. `playerDeaths` is the PvP figure those
 *  first two exclude, reported on its own because it is genuinely interesting
 *  and merely miscategorised by the endpoint.
 *
 *  The two headline races are Options because a world can have a day where
 *  nothing killed a player at all, and a small world can have a day where
 *  nothing was killed. */
final case class DayKillSummary(
    world: String,
    saveDay: LocalDate,
    mostKilled: Option[(String, Int)],
    deadliest: Option[(String, Int)],
    playerDeaths: Int,
    totalKilled: Long,
    totalPlayersKilled: Int
)

/** Reading a kill statistics snapshot: which entries are creatures, which are
 *  bosses, and what the day's headlines were.
 *
 *  Pure. Everything about *when* a snapshot is taken and what day it describes
 *  is [[KillStatisticsService]]'s.
 *
 *  ==Which day a snapshot describes==
 *  `last_day` is the server-save day that has just closed, not the one running:
 *  tibia.com rolls the figures at server save. So a snapshot read after 10:00
 *  Berlin describes the day keyed by [[DailyStatistics.reportedDay]] — the same
 *  day the experience post covers, which is what lets the two sit in one embed
 *  later. */
object KillStatistics {

  /** Entries that are counted like a race and are not one.
   *
   *  `players` is where PvP deaths land — 378 of them on Antica in a day, which
   *  is more than any real creature manages, so leaving it in would make
   *  "creature that killed the most players" read "players" on every world every
   *  day. `(elemental forces)` is environmental damage: fire fields, drowning,
   *  and the rest.
   *
   *  Lowercased for the same reason [[BossCatalogue.byRace]] is. */
  val NotCreatures: Set[String] = Set("players", "(elemental forces)")

  def isCreature(race: String): Boolean = !NotCreatures.contains(race.toLowerCase)

  /** The day's boss rows, for the bosses the catalogue knows.
   *
   *  Every catalogued boss, including the ones that were not seen: a zero is the
   *  fact that makes "not seen for N days" measurable later, and storing only
   *  the non-zero rows would make a gap in the history indistinguishable from a
   *  day the boss did not spawn. Seventy-four rows per world per day, which is
   *  what keeps this affordable against the fifteen hundred races the endpoint
   *  actually returns. */
  def bossKills(data: KillStatisticsData, saveDay: LocalDate): List[BossKills] = {
    val seen = data.entries.groupBy(_.race.toLowerCase)
    BossCatalogue.bosses.map { boss =>
      val entry = seen.get(boss.race.toLowerCase).flatMap(_.headOption)
      BossKills(
        world = data.world,
        saveDay = saveDay,
        race = boss.race,
        killed = entry.map(_.last_day_killed).getOrElse(0),
        playersKilled = entry.map(_.last_day_players_killed).getOrElse(0)
      )
    }
  }

  /** The creature players killed most of. None on a day with no kills at all. */
  def mostKilledCreature(entries: List[KillStatisticsEntry]): Option[(String, Int)] =
    entries.filter(entry => isCreature(entry.race) && entry.last_day_killed > 0)
      .sortBy(entry => (-entry.last_day_killed, entry.race))
      .headOption
      .map(entry => (entry.race, entry.last_day_killed))

  /** The creature that killed the most players, PvP and the environment
   *  excluded. None on a day nothing killed anybody. */
  def deadliestCreature(entries: List[KillStatisticsEntry]): Option[(String, Int)] =
    entries.filter(entry => isCreature(entry.race) && entry.last_day_players_killed > 0)
      .sortBy(entry => (-entry.last_day_players_killed, entry.race))
      .headOption
      .map(entry => (entry.race, entry.last_day_players_killed))

  /** Players killed by other players — the `players` row, which the two above
   *  leave out. 0 when the endpoint did not report the row at all. */
  def playerDeaths(entries: List[KillStatisticsEntry]): Int =
    entries.find(_.race.equalsIgnoreCase("players")).map(_.last_day_players_killed).getOrElse(0)

  def summary(data: KillStatisticsData, saveDay: LocalDate): DayKillSummary =
    DayKillSummary(
      world = data.world,
      saveDay = saveDay,
      mostKilled = mostKilledCreature(data.entries),
      deadliest = deadliestCreature(data.entries),
      playerDeaths = playerDeaths(data.entries),
      totalKilled = data.total.last_day_killed.toLong,
      totalPlayersKilled = data.total.last_day_players_killed
    )

  /** Whether a snapshot is worth filing at all.
   *
   *  A world that reports nothing killed in a whole day did not have a quiet
   *  day — something upstream went wrong, and writing seventy-four zeroes would
   *  put a false "no boss spawned" into the history that the prediction later
   *  reads as fact. Cheap to check and it costs only a retry. */
  def isPlausible(data: KillStatisticsData): Boolean = data.total.last_day_killed > 0
}
