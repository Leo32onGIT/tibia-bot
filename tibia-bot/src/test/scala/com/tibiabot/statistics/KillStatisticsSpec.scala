package com.tibiabot.statistics

import com.tibiabot.tibiadata.JsonSupport
import com.tibiabot.tibiadata.response.{KillStatisticsData, KillStatisticsEntry, KillStatisticsResponse, KillStatisticsTotal}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.LocalDate
import scala.io.Source

/** Reading a kill statistics snapshot, against a real one.
 *
 *  The fixture is a genuine Antica response trimmed to 47 of its 1,481 entries:
 *  the two rows that are not creatures, every catalogued boss that was present,
 *  and the loudest creatures either way. Trimmed rather than synthesised because
 *  the thing most likely to be wrong here is a name, and only real data has real
 *  names in it. */
class KillStatisticsSpec extends AnyFunSuite with Matchers with JsonSupport {

  private val day = LocalDate.of(2026, 9, 8)

  private val data: KillStatisticsData = {
    val stream = getClass.getResourceAsStream("/tibiadata/killstatistics.json")
    val source = Source.fromInputStream(stream, "UTF-8")
    val raw = try source.mkString finally source.close()
    raw.parseJson.convertTo[KillStatisticsResponse].killstatistics
  }

  private def entry(race: String, killed: Int = 0, playersKilled: Int = 0) =
    KillStatisticsEntry(race, playersKilled, killed, 0, 0)

  // --- the two rows that are not creatures --------------------------------

  test("players and elemental forces are not creatures") {
    KillStatistics.isCreature("players") shouldBe false
    KillStatistics.isCreature("(elemental forces)") shouldBe false
    KillStatistics.isCreature("Players") shouldBe false
    KillStatistics.isCreature("quara looters") shouldBe true
  }

  test("the deadliest creature is never the players row") {
    // 378 PvP deaths on Antica that day against 13 for the worst real creature.
    // Left in, "players" wins this on every world every day.
    KillStatistics.deadliestCreature(data.entries).map(_._1) shouldBe Some("quara looters")
  }

  test("PvP deaths are reported on their own rather than thrown away") {
    KillStatistics.playerDeaths(data.entries) shouldBe 378
  }

  test("environmental damage does not win either") {
    val entries = List(entry("(elemental forces)", playersKilled = 900), entry("dragon", playersKilled = 4))
    KillStatistics.deadliestCreature(entries).map(_._1) shouldBe Some("dragon")
  }

  test("the most killed creature is the real top of the list") {
    KillStatistics.mostKilledCreature(data.entries) shouldBe Some(("flimsy lost souls", 23965))
  }

  test("a day where nothing killed a player has no deadliest creature") {
    KillStatistics.deadliestCreature(List(entry("dragon", killed = 40))) shouldBe None
    KillStatistics.mostKilledCreature(List(entry("dragon", playersKilled = 2))) shouldBe None
  }

  test("ties are broken by name so two reads of one day agree") {
    val entries = List(entry("wyrm", killed = 10), entry("dragon", killed = 10))
    KillStatistics.mostKilledCreature(entries).map(_._1) shouldBe Some("dragon")
  }

  // --- the boss rows ------------------------------------------------------

  test("every catalogued boss gets a row, including the ones not seen") {
    // A zero is what makes "not seen for N days" measurable. Without it, a day
    // we looked and saw nothing is indistinguishable from a day we did not look.
    val rows = KillStatistics.bossKills(data, day)
    rows should have size BossCatalogue.bosses.size
    rows.map(_.race).distinct should have size BossCatalogue.bosses.size
    rows.count(row => row.killed > 0 || row.playersKilled > 0) should be > 0
    rows.count(row => row.killed == 0 && row.playersKilled == 0) should be > 0
  }

  test("a boss present in the snapshot carries its real figures") {
    val rows = KillStatistics.bossKills(data, day).map(row => row.race -> row).toMap
    val fixture = data.entries.map(e => e.race.toLowerCase -> e).toMap
    // Every boss the endpoint listed should have come through with its numbers
    // rather than as a zero.
    val present = BossCatalogue.bosses.filter(boss => fixture.contains(boss.race.toLowerCase))
    present should not be empty
    present.foreach { boss =>
      val expected = fixture(boss.race.toLowerCase)
      rows(boss.race).killed shouldBe expected.last_day_killed
      rows(boss.race).playersKilled shouldBe expected.last_day_players_killed
    }
  }

  test("boss rows carry the world and day they were read for") {
    val rows = KillStatistics.bossKills(data, day)
    rows.map(_.world).distinct shouldBe List("Antica")
    rows.map(_.saveDay).distinct shouldBe List(day)
  }

  test("a boss is matched however the endpoint cases its race") {
    // The endpoint mixes "yetis" with "Rotworm Queen"; nothing should depend on
    // which it chose.
    val boss = BossCatalogue.bosses.head
    val shouty = data.copy(entries = List(entry(boss.race.toUpperCase, killed = 7)))
    KillStatistics.bossKills(shouty, day).find(_.race == boss.race).map(_.killed) shouldBe Some(7)
  }

  // --- the summary --------------------------------------------------------

  test("the summary carries the day's headlines and the world totals") {
    val summary = KillStatistics.summary(data, day)
    summary.world shouldBe "Antica"
    summary.saveDay shouldBe day
    summary.mostKilled shouldBe Some(("flimsy lost souls", 23965))
    summary.deadliest shouldBe Some(("quara looters", 13))
    summary.playerDeaths shouldBe 378
    summary.totalKilled shouldBe 2514276L
    summary.totalPlayersKilled shouldBe 818
  }

  // --- the creatures the post names ---------------------------------------

  test("the top killed are creatures, largest first, and stop at the limit") {
    val entries = List(
      entry("players", killed = 999999),
      entry("(elemental forces)", killed = 888888),
      entry("rotworm", killed = 900),
      entry("dragon", killed = 700),
      entry("wyrm", killed = 500))
    KillStatistics.topKilled(entries, limit = 2).map(_.race) shouldBe List("rotworm", "dragon")
  }

  test("a race nothing killed is not in the list at all") {
    KillStatistics.topKilled(List(entry("rotworm", killed = 0), entry("dragon", killed = 5)))
      .map(_.race) shouldBe List("dragon")
  }

  test("the real snapshot's top ten are ten creatures") {
    val top = KillStatistics.topKilled(data.entries)
    top should have size KillStatistics.TopKills
    top.map(_.race).foreach(race => KillStatistics.isCreature(race) shouldBe true)
    top.map(_.last_day_killed) shouldBe top.map(_.last_day_killed).sorted.reverse
  }

  // --- the special bosses --------------------------------------------------

  test("a special boss is found under the race the endpoint counts it by") {
    // "plunder patriarches", not "Plunder Patriarch" — read off the live
    // endpoint, because a race that does not match exactly produces no row at
    // all rather than a wrong one.
    val plunder = SpecialKills.all.head
    plunder.name shouldBe "Plunder Patriarch"
    plunder.race shouldBe "plunder patriarches"
    SpecialKills.forRace("PLUNDER PATRIARCHES") shouldBe Some(plunder)
  }

  test("only the special bosses that died are reported") {
    val entries = List(
      entry("plunder patriarches", killed = 3),
      entry("Bakragore", killed = 0),
      entry("Phosphorus", killed = 1))
    KillStatistics.specialKills(entries).map(_.race) shouldBe
      List("plunder patriarches", "Phosphorus")
  }

  test("they are reported in catalogue order, not by how many died") {
    val entries = SpecialKills.all.reverse.map(kill => entry(kill.race, killed = 1))
    KillStatistics.specialKills(entries).map(_.race) shouldBe SpecialKills.races
  }

  // --- what a day keeps ----------------------------------------------------

  test("a day keeps the catalogued bosses, the top creatures and the specials") {
    val rows = KillStatistics.dayRaces(data, day)
    val races = rows.map(_.race.toLowerCase).toSet
    BossCatalogue.bosses.foreach(boss => races should contain(boss.race.toLowerCase))
    KillStatistics.topKilled(data.entries).foreach(top => races should contain(top.race.toLowerCase))
    rows.size shouldBe races.size
  }

  test("a race is never kept twice, however many lists claim it") {
    val entries = List(entry("Ferumbras", killed = 90000), entry("rotworm", killed = 5))
    val rows = KillStatistics.dayRaces(data.copy(entries = entries), day)
    rows.count(_.race.equalsIgnoreCase("Ferumbras")) shouldBe 1
  }

  test("a catalogued boss keeps its zero, and a creature outside the list is simply absent") {
    // A zero is what makes "not seen for N days" measurable for a boss. For a
    // creature it would say something untrue — it is missing from the list, not
    // from the world.
    val entries = List(entry("rotworm", killed = 900))
    val rows = KillStatistics.dayRaces(data.copy(entries = entries), day)
    rows.find(_.race == BossCatalogue.bosses.head.race).map(_.killed) shouldBe Some(0)
    rows.exists(_.race == "dragon") shouldBe false
  }

  test("the extra races carry the world and day they were read for") {
    val rows = KillStatistics.dayRaces(data, day).filterNot(row =>
      BossCatalogue.bosses.exists(_.race.equalsIgnoreCase(row.race)))
    rows should not be empty
    rows.foreach { row =>
      row.world shouldBe data.world
      row.saveDay shouldBe day
    }
  }

  // --- believing a snapshot at all ----------------------------------------

  test("a world that killed nothing all day is not believed") {
    // Not a quiet day — a bad read. Seventy-four zeroes filed as fact would
    // later read as "no boss spawned", which is what the history is for.
    val empty = data.copy(total = KillStatisticsTotal(0, 0, 0, 0))
    KillStatistics.isPlausible(empty) shouldBe false
    KillStatistics.isPlausible(data) shouldBe true
  }
}
