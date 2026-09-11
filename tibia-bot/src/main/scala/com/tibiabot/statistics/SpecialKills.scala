package com.tibiabot.statistics

/** A boss the daily post calls out by name when it died.
 *
 *  @param name  what the post shows
 *  @param race  what kill statistics calls it, which is not always the same
 *               thing — the endpoint counts these the way it counts creatures,
 *               in lowercase and in the plural
 *  @param emoji the key its configured Discord emoji is held under; resolved by
 *               the caller, so this stays Config-free like everything else the
 *               post's presentation touches
 */
final case class SpecialKill(name: String, race: String, emoji: String)

/** The quest bosses worth a line of their own on the day they are killed.
 *
 *  Deliberately not part of [[BossCatalogue]], which is a catalogue of *spawn
 *  cycles*: [[BossPredictor.predictAll]] walks that list and predicts everything
 *  on it, and none of these has a window to predict. Keeping them apart means
 *  they are reported without ever being guessed at.
 *
 *  They still ride in the same table. `kill_statistics_boss` is keyed on
 *  `(world, save_day, race)` and enforces no membership of anything, and the
 *  only reader that cares looks rows up *by catalogue name* — so a race in there
 *  that the catalogue has never heard of is simply never asked for. */
object SpecialKills {

  /** The races, and what to call them.
   *
   *  Every string here was read off the live endpoint rather than typed from
   *  memory, because a race that does not match exactly does not produce a wrong
   *  row — it produces no row at all, on a feature that is meant to be quiet
   *  most days anyway, which is about as hard to notice as a bug gets.
   *  `plunder patriarches` is the one that catches people out. */
  val all: List[SpecialKill] = List(
    SpecialKill("Plunder Patriarch", "plunder patriarches", "plunder"),
    SpecialKill("Phosphorus", "Phosphorus", "phosphorus"),
    SpecialKill("Goshnar's Megalomania", "Goshnar's Megalomania", "soulwar"),
    SpecialKill("Bakragore", "Bakragore", "bakragore")
  )

  private val byRace: Map[String, SpecialKill] = all.map(kill => kill.race.toLowerCase -> kill).toMap

  def forRace(race: String): Option[SpecialKill] = byRace.get(race.toLowerCase)

  def races: List[String] = all.map(_.race)
}
