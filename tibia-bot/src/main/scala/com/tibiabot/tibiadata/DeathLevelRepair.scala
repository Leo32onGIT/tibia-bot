package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response._
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}

/** Puts a level back on the one death TibiaData cannot read one from.
 *
 *  tibia.com writes an assist-only death without the usual "Died at Level N by"
 *  clause — the row is the timestamp followed straight by "Assisted by ...".
 *  TibiaData's parser looks for `at Level ` regardless, and on a miss Go's
 *  `strings.Index` returns -1, so it reads from offset 8 of the row: one
 *  character into the year. The level it reports for such a death is the year's
 *  last two digits — 26 in 2026, 25 in 2025.
 *
 *  Upstream knows. The line after the one that computes it resets the level to
 *  0 when it comes out as exactly 25, a guard written during 2025 that stopped
 *  firing on 1 January 2026 and will need rewriting every January it survives.
 *
 *  '''Which is why this cannot wait for an upstream fix.''' That guard, working,
 *  yields 0 — and 0 is worse here than a wrong number, because it fails the
 *  `deathsMin` filter and the death stops being posted at all rather than being
 *  posted with a wrong level on it. The level is not in the page. Something has
 *  to supply it, and only this side can.
 *
 *  '''What is substituted''' is the next level the character is known to have
 *  had: walking the list newest-first, the level on the nearest newer death, or
 *  for the newest death the level on the sheet itself. Both are exact whenever
 *  the character's level did not move in between — and for the newest death,
 *  which is the only kind the bot ever acts on, "in between" is the seconds
 *  since it happened. The one case that reads a level low is a death that cost
 *  a level with no newer death to correct it.
 *
 *  '''Detected by shape, never by value.''' The upstream branch that empties the
 *  killer list is the same one that loses the level, so killers-empty with
 *  assists-present is exactly the broken parse and nothing else. Matching on the
 *  number instead would need updating every January, and would stop matching
 *  altogether the day upstream repairs its guard.
 *
 *  '''It grades itself.''' This wraps the TibiaData source only, so in shadow
 *  mode [[com.tibiabot.fansiteapi.CharacterDivergence]] goes on comparing the
 *  substituted level against the one CipSoft's own API reports for that death —
 *  which is the real number. A silent comparison means the estimate was exact;
 *  a `settled deaths` warning naming a repaired death is the heuristic missing,
 *  and by how much. */
object DeathLevelRepair extends StrictLogging {

  /** Whether this death's level came from the page rather than from the year. */
  private def readable(death: Deaths): Boolean = death.killers.nonEmpty || death.assists.isEmpty

  private def epochOf(death: Deaths): Long =
    try java.time.Instant.parse(death.time).getEpochSecond
    catch { case _: java.time.format.DateTimeParseException => 0L }

  def apply(sheet: CharacterResponse): CharacterResponse = {
    val deaths = sheet.character.deaths.getOrElse(Nil)
    if (deaths.forall(readable)) sheet
    else {
      // Newest first. TibiaData already returns them that way, but the
      // substitution walks backwards through the character's history and would
      // be quietly wrong rather than loudly broken if that ever changed.
      val byRecency = deaths.zipWithIndex.sortBy { case (death, index) => (-epochOf(death), index) }
      // Carrying the last readable level forward means a run of consecutive
      // assist-only deaths is repaired from the newest death above it that has
      // a real level, rather than from another death's substituted one.
      val substitutions = byRecency.foldLeft((sheet.character.character.level, Map.empty[Int, Double])) {
        case ((lastKnown, repairs), (death, index)) =>
          if (readable(death)) (death.level, repairs) else (lastKnown, repairs.updated(index, lastKnown))
      }._2
      val name = sheet.character.character.name
      val repaired = deaths.zipWithIndex.map { case (death, index) =>
        substitutions.get(index).fold(death) { level =>
          // Debug, not warn: the same death is repaired on every poll for as
          // long as it stays in the 30-day list, and the shadow comparison
          // above already warns if the substitution is wrong.
          logger.debug(
            s"Death level repaired for '$name' at ${death.time}: TibiaData read ${death.level.toInt} off an " +
              s"assist-only row that carries no level, using ${level.toInt}")
          death.copy(level = level)
        }
      }
      sheet.copy(character = sheet.character.copy(deaths = Some(repaired)))
    }
  }
}

/** [[DeathLevelRepair]] as a decorator, so the repair happens once on the way
 *  out of the source rather than at each of the three places the bot reads a
 *  death's level — the `deathsMin`/fullbless filters, the embed header and the
 *  frag record, which would otherwise have to agree with each other forever. */
final class DeathLevelRepairTibiaApi(underlying: TibiaApi)(implicit ec: ExecutionContext) extends TibiaApi {

  private def repair(result: Either[String, CharacterResponse]): Either[String, CharacterResponse] =
    result.map(sheet => DeathLevelRepair(sheet))

  def getCharacter(name: String): Future[Either[String, CharacterResponse]] =
    underlying.getCharacter(name).map(repair)

  override def getCharacterOnDemand(name: String): Future[Either[String, CharacterResponse]] =
    underlying.getCharacterOnDemand(name).map(repair)

  /** Only the killer's own level is read off this one, but a sheet that left
   *  here unrepaired would be the single copy in the process that disagreed
   *  with every other about a death. */
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] =
    underlying.getKillerFallback(name).map(repair)

  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] =
    underlying.getCharacterWithInput(input).map { case (result, name, a, b) => (repair(result), name, a, b) }

  def getWorld(world: String): Future[Either[String, WorldResponse]] = underlying.getWorld(world)
  def getWorlds(): Future[Either[String, WorldsResponse]] = underlying.getWorlds()
  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = underlying.getBoostedBoss()
  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = underlying.getBoostedCreature()
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = underlying.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] =
    underlying.getGuildWithInput(input)
}
