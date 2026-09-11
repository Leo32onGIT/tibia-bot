package com.tibiabot.statistics

import com.tibiabot.domain.time.DreamScarCycle
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
) {

  /** The day's figures without the day itself.
   *
   *  What the endpoint hands back carries no date, so the only way to tell which
   *  day is in front of us is to compare it against the day we already filed:
   *  until the nightly batch runs, a live read *is* the previous day. See
   *  [[KillStatisticsService]], which is the whole reason this exists.
   *
   *  Every figure rather than just the total, so two days that happened to kill
   *  the same number of creatures are still told apart. */
  def figures: (Option[(String, Int)], Option[(String, Int)], Int, Long, Int) =
    (mostKilled, deadliest, playerDeaths, totalKilled, totalPlayersKilled)
}

/** Reading a kill statistics snapshot: which entries are creatures, which are
 *  bosses, and what the day's headlines were.
 *
 *  Pure. Everything about *when* a snapshot is taken and what day it describes
 *  is [[KillStatisticsService]]'s.
 *
 *  ==Which day a snapshot describes==
 *  Not quite a server-save day. tibia.com rebuilds these figures in a nightly
 *  batch around 03:10 Berlin, so a publication straddles the 10:00 boundary and
 *  overlaps the day it is filed under by seventeen hours of twenty-four — see
 *  [[com.tibiabot.scheduler.KillStatisticsSchedule]], which owns that
 *  arithmetic. The label agrees with [[DailyStatistics.reportedDay]] throughout
 *  the server-save window, which is what lets the two sit in one post. */
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

  /** How many of the day's creatures the post names. */
  val TopKills: Int = 10

  /** The five Dream Courts bosses, whether or not they died.
   *
   *  Kept for a reason nothing reads yet. The bot decides which of the five is
   *  a world's boss of the day from a wiki page whose per-world offsets drift —
   *  a server reset bumps a world's rotation and the page stays wrong until
   *  somebody edits it — and for forty-three worlds the page admits it does not
   *  know at all. The kill figures are the one source that could answer it from
   *  evidence instead, because the boss of the day is the one people can
   *  actually go and kill.
   *
   *  Whether they answer it is not settled. Several of the five are killed on
   *  the same world on the same day, so the signal is which was killed *most*,
   *  and on one day's data that agreed with the wiki only about half the time.
   *  The test that separates a noisy estimator from a drifted page is whether a
   *  world's answer advances by exactly one step per day, and that needs
   *  consecutive days nobody has. Hence this: bank the days, decide later. A day
   *  not banked cannot be recovered.
   *
   *  Zeros included, like the catalogue and unlike the creatures. A day a boss
   *  was not killed is exactly as informative as a day it was — it is evidence
   *  about which boss was available — and a rectangular history is far easier to
   *  reason about than one where absence means two things.
   *
   *  The race names are the endpoint's own, verified against it rather than
   *  assumed: all five are spelled there exactly as
   *  [[com.tibiabot.domain.time.DreamScarCycle]] spells
   *  them, which is not something to take for granted — see [[SpecialKills]] for
   *  the one that is not. */
  def dreamCourtKills(data: KillStatisticsData, saveDay: LocalDate): List[BossKills] = {
    val seen = data.entries.groupBy(_.race.toLowerCase)
    DreamScarCycle.bossCycle.toList.map { boss =>
      val entry = seen.get(boss.toLowerCase).flatMap(_.headOption)
      BossKills(
        world = data.world,
        saveDay = saveDay,
        race = boss,
        killed = entry.map(_.last_day_killed).getOrElse(0),
        playersKilled = entry.map(_.last_day_players_killed).getOrElse(0)
      )
    }
  }

  /** Every race worth keeping for one world's day: the catalogued bosses, the
   *  Dream Courts five, the creatures the world killed most of, and the special
   *  bosses.
   *
   *  All of them go in the one table. It is keyed on `(world, save_day, race)`
   *  and enforces membership of nothing, and the only reader that could be
   *  confused by a stranger — the spawn prediction — looks rows up *by catalogue
   *  name*, so a race it has never heard of is never asked for. A second table
   *  would buy a tidier name and a second write per world per day.
   *
   *  Deduplicated on the race, because the lists can overlap in principle and a
   *  repeated key would be a write conflict rather than a wrong number. The
   *  earlier list wins, and the catalogue is first because its casing is the one
   *  the prediction matches on.
   *
   *  The two named lists keep their zeros; the creatures do not. A zero matters
   *  for a boss — it is what makes "not seen for N days" measurable, and for the
   *  Dream Courts five it is half the evidence — but a creature outside the top
   *  ten is not absent from the world, only from the list, and writing that as a
   *  zero would say something untrue. */
  def dayRaces(data: KillStatisticsData, saveDay: LocalDate): List[BossKills] = {
    val named = (bossKills(data, saveDay) ++ dreamCourtKills(data, saveDay))
      .groupBy(_.race.toLowerCase).values.flatMap(_.headOption).toList
      .sortBy(_.race)
    val known = named.map(_.race.toLowerCase).toSet
    val extra = (topKilled(data.entries) ++ specialKills(data.entries))
      .filterNot(entry => known.contains(entry.race.toLowerCase))
      .groupBy(_.race.toLowerCase)
      .values.flatMap(_.headOption).toList
      .map(entry => BossKills(
        world = data.world,
        saveDay = saveDay,
        race = entry.race,
        killed = entry.last_day_killed,
        playersKilled = entry.last_day_players_killed))
    named ++ extra.sortBy(row => (-row.killed, row.race))
  }

  /** Races the day keeps by name rather than because they were among the
   *  biggest: the catalogue, the Dream Courts five and the specials.
   *
   *  What the post's creature list has to exclude. Reading the day's rows back
   *  ordered by kills used to give the top ten creatures only because a boss
   *  killed three times cannot outrank a rotworm killed a hundred thousand
   *  times — true, but an assumption rather than a rule, and one that got weaker
   *  every time another name was added to the table. This states it instead. */
  def keptByName: Set[String] =
    (BossCatalogue.bosses.map(_.race) ++ DreamScarCycle.bossCycle ++ SpecialKills.races)
      .map(_.toLowerCase).toSet

  /** The creatures the world killed most of, largest first.
   *
   *  `players` and `(elemental forces)` are excluded for the reason
   *  [[NotCreatures]] gives: neither is a creature, and `players` would take the
   *  top of this list on most worlds. */
  def topKilled(entries: List[KillStatisticsEntry], limit: Int = TopKills): List[KillStatisticsEntry] =
    entries.filter(entry => isCreature(entry.race) && entry.last_day_killed > 0)
      .sortBy(entry => (-entry.last_day_killed, entry.race))
      .take(limit)

  /** The special bosses that died that day, in the order [[SpecialKills]] lists
   *  them — which is the order the post shows them in, rather than one that
   *  reshuffles itself depending on how many of each happened to be killed. */
  def specialKills(entries: List[KillStatisticsEntry]): List[KillStatisticsEntry] = {
    val seen = entries.groupBy(_.race.toLowerCase)
    SpecialKills.all.flatMap(kill => seen.get(kill.race.toLowerCase).flatMap(_.headOption))
      .filter(_.last_day_killed > 0)
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
