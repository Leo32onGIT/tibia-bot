package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Repairing the level on an assist-only death, which TibiaData reports as the
 *  current year's last two digits because its parser reads past a clause
 *  tibia.com never wrote. See [[DeathLevelRepair]].
 *
 *  The tests that matter are the ones about what is *not* touched: this runs
 *  over every character sheet the bot fetches, and a heuristic that reached
 *  beyond the one broken shape would be rewriting real deaths. */
class DeathLevelRepairSpec extends AnyFunSuite with Matchers with JsonSupport {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val base: CharacterResponse = {
    val is = getClass.getResourceAsStream("/tibiadata/character.json")
    require(is != null, "missing fixture /tibiadata/character.json")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString.parseJson.convertTo[CharacterResponse]
    finally is.close()
  }

  private def player(name: String): Killers = Killers(name = name, player = true, traded = false, summon = "")

  /** A death as TibiaData renders one it could read: killers, a real level. */
  private def killed(time: String, level: Int): Deaths =
    Deaths(time = time, level = level.toDouble, killers = List(player("Someone")), assists = Nil, reason = "")

  /** A death as TibiaData renders an assist-only one: no killers, and the level
   *  read off the year rather than the page. */
  private def assistOnly(time: String, level: Int = 26): Deaths =
    Deaths(time = time, level = level.toDouble, killers = Nil,
      assists = List(player("Helper One"), player("Helper Two")), reason = "")

  private def withDeaths(deaths: List[Deaths], sheetLevel: Int = 131): CharacterResponse =
    base.copy(character = base.character.copy(
      character = base.character.character.copy(level = sheetLevel.toDouble),
      deaths = Some(deaths)))

  private def levelsOf(sheet: CharacterResponse): List[Int] =
    sheet.character.deaths.getOrElse(Nil).map(_.level.toInt)

  test("a sheet whose deaths all carry a real level is returned untouched") {
    // Reference equality: the common case must not rebuild the sheet.
    val untouched = DeathLevelRepair(base)
    untouched should be theSameInstanceAs base
  }

  test("a character with no deaths at all is returned untouched") {
    val empty = base.copy(character = base.character.copy(deaths = None))
    DeathLevelRepair(empty) should be theSameInstanceAs empty
  }

  test("an assist-only death takes the level of the nearest newer death") {
    // The shape this exists for, and Syxti's real numbers: TibiaData read 26
    // off "2026" for the death at 23:03:28, between a 414 above and a 415
    // below. 414 is what CipSoft's own API reports for it.
    val repaired = DeathLevelRepair(withDeaths(List(
      killed("2026-09-03T22:11:37Z", 414),
      assistOnly("2026-09-01T23:03:28Z"),
      killed("2026-09-01T23:01:20Z", 415)
    )))
    levelsOf(repaired) shouldBe List(414, 414, 415)
  }

  test("the newest death has no newer death to take from, so it takes the sheet's level") {
    // The only case the bot ever acts on: a death seconds old, where the sheet
    // was read moments after it.
    val repaired = DeathLevelRepair(withDeaths(List(
      assistOnly("2026-09-10T01:39:52Z"),
      killed("2026-09-08T22:11:22Z", 414)
    ), sheetLevel = 413))
    levelsOf(repaired) shouldBe List(413, 414)
  }

  test("a run of assist-only deaths is repaired from the last real level, not from each other") {
    // Repairing from the entry above would work here by accident; it stops
    // working the moment two of them sit above a level that moved.
    val repaired = DeathLevelRepair(withDeaths(List(
      killed("2026-09-05T10:00:00Z", 410),
      assistOnly("2026-09-04T10:00:00Z"),
      assistOnly("2026-09-03T10:00:00Z"),
      killed("2026-09-02T10:00:00Z", 415)
    )))
    levelsOf(repaired) shouldBe List(410, 410, 410, 415)
  }

  test("the substitution follows death time, not list order") {
    // TibiaData returns deaths newest first and the repair reads them that
    // way. If that ever changes it should keep being right rather than start
    // reading history backwards.
    val repaired = DeathLevelRepair(withDeaths(List(
      killed("2026-09-01T23:01:20Z", 415),
      assistOnly("2026-09-01T23:03:28Z"),
      killed("2026-09-03T22:11:37Z", 414)
    )))
    levelsOf(repaired) shouldBe List(415, 414, 414)
  }

  test("a death with killers is left alone however implausible its level looks") {
    // Detection is by shape. A real level that happens to be 26 belongs to a
    // level 26 character, and there is nothing to repair.
    val repaired = DeathLevelRepair(withDeaths(List(
      assistOnly("2026-09-04T10:00:00Z"),
      killed("2026-09-03T10:00:00Z", 26)
    ), sheetLevel = 27))
    levelsOf(repaired) shouldBe List(27, 26)
  }

  test("a death with neither killers nor assists is left alone") {
    // Not the broken branch — upstream only drops the killer list where it
    // also found an "Assisted by", so this shape says nothing about the level.
    val orphan = Deaths(time = "2026-09-04T10:00:00Z", level = 26d, killers = Nil, assists = Nil, reason = "")
    val sheet = withDeaths(List(orphan))
    DeathLevelRepair(sheet) should be theSameInstanceAs sheet
  }

  test("an unparseable death time does not fail the fetch") {
    // A sheet must still come back; the ordering of a death nobody can date is
    // the only thing at stake.
    val repaired = DeathLevelRepair(withDeaths(List(
      Deaths(time = "not a time", level = 26d, killers = Nil,
        assists = List(player("Helper")), reason = ""),
      killed("2026-09-03T10:00:00Z", 414)
    ), sheetLevel = 413))
    levelsOf(repaired) shouldBe List(414, 414)
  }

  test("the decorator repairs every character path and passes the rest through") {
    val stub = new StubApi(Right(withDeaths(List(
      assistOnly("2026-09-10T01:39:52Z"),
      killed("2026-09-08T22:11:22Z", 414)
    ), sheetLevel = 413)))
    val api = new DeathLevelRepairTibiaApi(stub)

    levelsOf(await(api.getCharacter("Syxti")).toOption.get) shouldBe List(413, 414)
    levelsOf(await(api.getCharacterOnDemand("Syxti")).toOption.get) shouldBe List(413, 414)
    levelsOf(await(api.getKillerFallback("Syxti")).toOption.get) shouldBe List(413, 414)
    levelsOf(await(api.getCharacterWithInput(("Syxti", "a", "b")))._1.toOption.get) shouldBe List(413, 414)

    await(api.getWorld("Wintera")) shouldBe Left("x")
    stub.otherCalls shouldBe 1
  }

  test("an error is passed through rather than turned into an empty sheet") {
    val api = new DeathLevelRepairTibiaApi(new StubApi(Left("503")))
    await(api.getCharacter("Syxti")) shouldBe Left("503")
  }

  private class StubApi(result: Either[String, CharacterResponse]) extends TibiaApi {
    var otherCalls = 0
    def getCharacter(name: String) = Future.successful(result)
    override def getCharacterOnDemand(name: String) = Future.successful(result)
    def getKillerFallback(name: String) = Future.successful(result)
    def getCharacterWithInput(i: (String, String, String)) = Future.successful((result, i._1, i._2, i._3))
    def getWorld(w: String) = { otherCalls += 1; Future.successful(Left("x")) }
    def getWorlds() = { otherCalls += 1; Future.successful(Left("x")) }
    def getBoostedBoss() = { otherCalls += 1; Future.successful(Left("x")) }
    def getBoostedCreature() = { otherCalls += 1; Future.successful(Left("x")) }
    def getGuild(guild: String) = { otherCalls += 1; Future.successful(Left("x")) }
    def getGuildWithInput(i: (String, String)) = { otherCalls += 1; Future.successful((Left("x"), i._1, i._2)) }
  }
}
