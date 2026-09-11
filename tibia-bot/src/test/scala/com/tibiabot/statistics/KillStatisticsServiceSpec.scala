package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.persistence.KillStatisticsRepository
import com.tibiabot.tibiadata.KillStatisticsApi
import com.tibiabot.tibiadata.response._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import java.time.{Duration, LocalDate, ZonedDateTime}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

/** How the roll is recognised, when a day is filed, and what happens when the
 *  read is bad. */
class KillStatisticsServiceSpec extends AnyFunSuite with Matchers with ScalaFutures {

  // The default 150ms is not enough for the first test that touches the boss
  // catalogue: it is a lazy val read off the classpath and parsed on first use,
  // so whichever test gets there first pays for it. Nothing here is slow on
  // purpose — every delay is stubbed out.
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(15, Millis))

  private def berlin(text: String) = ZonedDateTime.parse(text).withZoneSameInstant(Clock.Berlin)

  private val wellAfterSave = berlin("2026-09-11T14:00:00+02:00")
  private val justAfterSave = berlin("2026-09-11T10:05:00+02:00")
  private val closedDay = LocalDate.of(2026, 9, 10)
  private val dayBefore = closedDay.minusDays(1)

  private def response(world: String, totalKilled: Int = 2500000) = KillStatisticsResponse(
    KillStatisticsData(
      world = world,
      entries = List(
        KillStatisticsEntry("players", 378, 378, 2185, 2185),
        KillStatisticsEntry("dragon", 4, 900, 20, 6000),
        KillStatisticsEntry("Ferumbras", 0, 1, 0, 1)),
      total = KillStatisticsTotal(818, totalKilled, 5520, 21487813)),
    Information(Api(4, "4.10.0", "abc"), Some("2026-09-11T08:15:00Z"), Status(200)))

  /** The figures [[response]] describes, filed under `day`.
   *
   *  Derived from the same fixture so that "the endpoint is still showing the
   *  day we filed" is literally the same numbers, which is the thing the service
   *  is looking at. */
  private def filedAs(world: String, day: LocalDate, totalKilled: Int = 2500000) =
    KillStatistics.summary(response(world, totalKilled).killstatistics, day)

  private class StubApi(var results: Map[String, Either[String, KillStatisticsResponse]]) extends KillStatisticsApi {
    val calls = mutable.ListBuffer.empty[String]
    def getKillStatistics(world: String): Future[Either[String, KillStatisticsResponse]] = {
      calls += world
      results.get(world) match {
        case Some(result) => Future.successful(result)
        case None => Future.failed(new RuntimeException("boom"))
      }
    }
  }

  /** @param previous what was filed for the day before the closing one. Empty is
   *                  the cold-start case — a database with nothing to recognise
   *                  the roll against — which is why most of the tests below
   *                  that are not about the roll leave it empty. */
  private class StubRepo(
      alreadyFiled: Set[(String, LocalDate)] = Set.empty,
      previous: Map[String, DayKillSummary] = Map.empty
  ) extends KillStatisticsRepository {
    val bossWrites = mutable.ListBuffer.empty[List[BossKills]]
    val summaries = mutable.ListBuffer.empty[DayKillSummary]
    val order = mutable.ListBuffer.empty[String]
    def recordBossKills(rows: List[BossKills]): Unit = { bossWrites += rows; order += "bosses" }
    def recordSummary(summary: DayKillSummary): Unit = { summaries += summary; order += "summary" }
    def hasDay(world: String, saveDay: LocalDate): Boolean = alreadyFiled.contains((world, saveDay))
    def bossHistory(world: String, race: String, from: LocalDate): List[BossKills] = Nil
    def sightings(world: String, from: LocalDate): Map[String, List[(LocalDate, Int)]] = Map.empty
    def earliestDay(world: String): Option[LocalDate] = None
    def killsOn(world: String, saveDay: LocalDate): List[BossKills] = Nil
    def summary(world: String, saveDay: LocalDate): Option[DayKillSummary] =
      if (saveDay == dayBefore) previous.get(world) else None
    def removeExpired(before: LocalDate): Unit = ()
  }

  private def service(
      api: KillStatisticsApi,
      repo: KillStatisticsRepository,
      worlds: List[String] = List("Antica"),
      now: ZonedDateTime = wellAfterSave
  ) = new KillStatisticsService(
    api = api,
    repository = repo,
    trackedWorlds = () => worlds,
    gap = () => Duration.ZERO.toMillis.millis,
    delay = _ => Future.unit,
    now = () => now)

  // --- which day the endpoint is reporting ---------------------------------

  test("the day read is the one that just closed") {
    val svc = service(new StubApi(Map.empty), new StubRepo())
    svc.dayToFetch(wellAfterSave) shouldBe closedDay
  }

  test("there is no deadline, so a bot down all morning still catches the day") {
    val svc = service(new StubApi(Map.empty), new StubRepo())
    svc.dayToFetch(berlin("2026-09-11T23:50:00+02:00")) shouldBe closedDay
  }

  test("before server save, the day read is the one before that") {
    // 09:00 on the 11th is inside the save day keyed the 10th, which has not
    // closed; what the endpoint is showing is the 9th.
    val svc = service(new StubApi(Map.empty), new StubRepo())
    svc.dayToFetch(berlin("2026-09-11T09:00:00+02:00")) shouldBe LocalDate.of(2026, 9, 9)
  }

  // --- recognising the roll ------------------------------------------------

  test("a world still showing the day we filed yesterday has not rolled, and is not filed") {
    // The figures carry no date. What says the roll has not happened is that
    // they are the same figures we already have under yesterday.
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    val repo = new StubRepo(previous = Map("Antica" -> filedAs("Antica", dayBefore)))
    service(api, repo, now = justAfterSave).tick().futureValue
    api.calls shouldBe List("Antica")
    repo.summaries shouldBe empty
  }

  test("figures that have moved on mean the roll has happened") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    val repo = new StubRepo(previous = Map("Antica" -> filedAs("Antica", dayBefore, totalKilled = 2400000)))
    service(api, repo, now = justAfterSave).tick().futureValue
    repo.summaries.map(_.saveDay) shouldBe List(closedDay)
  }

  test("one probe settles it and the rest of the worlds follow") {
    // The roll is one event across tibia.com, so discovering it costs one
    // request rather than one per world.
    val api = new StubApi(Map(
      "Antica" -> Right(response("Antica")),
      "Belobra" -> Right(response("Belobra"))))
    val repo = new StubRepo(previous = Map("Antica" -> filedAs("Antica", dayBefore, totalKilled = 2400000)))
    service(api, repo, worlds = List("Antica", "Belobra"), now = justAfterSave).tick().futureValue
    api.calls shouldBe List("Antica", "Belobra")
    repo.summaries.map(_.world) should contain theSameElementsAs List("Antica", "Belobra")
  }

  test("a world tracked for the first time is filed on the probe's word") {
    // Belobra has no previous day of its own, so there is nothing to compare it
    // against — it inherits the answer rather than sitting the day out.
    val api = new StubApi(Map(
      "Antica" -> Right(response("Antica")),
      "Belobra" -> Right(response("Belobra"))))
    val repo = new StubRepo(previous = Map("Antica" -> filedAs("Antica", dayBefore, totalKilled = 2400000)))
    service(api, repo, worlds = List("Antica", "Belobra"), now = justAfterSave).tick().futureValue
    repo.summaries.map(_.world) should contain("Belobra")
  }

  test("a world lagging behind the roll is refused rather than filed a day out") {
    // The inheritance above assumes worlds roll together. Every world that has a
    // previous day is checked against it anyway, so the assumption fails loudly.
    val api = new StubApi(Map(
      "Antica" -> Right(response("Antica")),
      "Belobra" -> Right(response("Belobra"))))
    val repo = new StubRepo(previous = Map(
      "Antica" -> filedAs("Antica", dayBefore, totalKilled = 2400000),
      "Belobra" -> filedAs("Belobra", dayBefore)))
    service(api, repo, worlds = List("Antica", "Belobra"), now = justAfterSave).tick().futureValue
    repo.summaries.map(_.world) shouldBe List("Antica")
  }

  test("a probe that cannot be reached asks the next world") {
    // One candidate is a coin flip when a large share of requests 503, and a
    // probe that reaches nobody costs a whole tick.
    val api = new StubApi(Map(
      "Antica" -> Left("503 from upstream"),
      "Belobra" -> Right(response("Belobra"))))
    val repo = new StubRepo(previous = Map(
      "Antica" -> filedAs("Antica", dayBefore),
      "Belobra" -> filedAs("Belobra", dayBefore, totalKilled = 2400000)))
    service(api, repo, worlds = List("Antica", "Belobra"), now = justAfterSave).tick().futureValue
    // Belobra answers the question, and Antica is then swept like any other
    // world the 503 left outstanding.
    api.calls shouldBe List("Antica", "Belobra", "Antica")
    repo.summaries.map(_.world) shouldBe List("Belobra")
  }

  test("a tick where no probe answers files nothing and waits") {
    val api = new StubApi(Map("Antica" -> Left("503 from upstream")))
    val repo = new StubRepo(previous = Map("Antica" -> filedAs("Antica", dayBefore)))
    service(api, repo, now = justAfterSave).tick().futureValue
    repo.summaries shouldBe empty
  }

  test("once the roll is known, a world a 503 cost is retried without another probe") {
    val api = new StubApi(Map(
      "Antica" -> Right(response("Antica")),
      "Belobra" -> Left("503 from upstream")))
    val repo = new StubRepo(previous = Map("Antica" -> filedAs("Antica", dayBefore, totalKilled = 2400000)))
    val svc = service(api, repo, worlds = List("Antica", "Belobra"), now = justAfterSave)
    svc.tick().futureValue
    api.results = api.results.updated("Belobra", Right(response("Belobra")))
    svc.tick().futureValue
    api.calls shouldBe List("Antica", "Belobra", "Belobra")
    repo.summaries.map(_.world) should contain theSameElementsAs List("Antica", "Belobra")
  }

  // --- the cold start ------------------------------------------------------

  test("a database with no previous day anywhere waits out the settle delay") {
    // The first morning after this shipped, or a wiped cache. There is nothing
    // to recognise the roll against, so this one case falls back to the clock.
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    val repo = new StubRepo()
    service(api, repo, now = justAfterSave).tick().futureValue
    api.calls shouldBe empty
    repo.summaries shouldBe empty
  }

  test("and files once the settle delay has passed") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    val repo = new StubRepo()
    service(api, repo, now = wellAfterSave).tick().futureValue
    api.calls shouldBe List("Antica")
    repo.summaries.map(_.saveDay) shouldBe List(closedDay)
  }

  // --- filing a day -------------------------------------------------------

  test("a world's day is fetched and filed") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    val repo = new StubRepo()
    service(api, repo).tick().futureValue
    api.calls shouldBe List("Antica")
    repo.summaries.map(_.saveDay) shouldBe List(closedDay)
    // Every catalogued boss, plus the day's creatures — the fixture has one the
    // catalogue does not know — so the write is the whole day, not just bosses.
    val written = repo.bossWrites.head
    written.size should be > BossCatalogue.bosses.size
    BossCatalogue.bosses.foreach(boss => written.map(_.race) should contain(boss.race))
    written.map(_.race) should contain("dragon")
  }

  test("the boss rows are written before the summary") {
    // hasDay reads the summary, and so does the daily post's gate, so a failure
    // between the two must leave the day looking unfiled. The other order would
    // mark it done with its rows missing and release a post reading a
    // half-written history.
    val repo = new StubRepo()
    service(new StubApi(Map("Antica" -> Right(response("Antica")))), repo).tick().futureValue
    repo.order shouldBe List("bosses", "summary")
  }

  test("a day already in the database is not fetched again") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    service(api, new StubRepo(alreadyFiled = Set(("Antica", closedDay)))).tick().futureValue
    api.calls shouldBe empty
  }

  test("a second tick does not re-read a world this process already filed") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    val svc = service(api, new StubRepo())
    svc.tick().futureValue
    svc.tick().futureValue
    api.calls shouldBe List("Antica")
  }

  test("every tracked world is read") {
    val api = new StubApi(Map(
      "Antica" -> Right(response("Antica")),
      "Belobra" -> Right(response("Belobra"))))
    val repo = new StubRepo()
    service(api, repo, worlds = List("Belobra", "Antica")).tick().futureValue
    api.calls should contain theSameElementsAs List("Antica", "Belobra")
    repo.summaries.map(_.world) should contain theSameElementsAs List("Antica", "Belobra")
  }

  // --- bad reads ----------------------------------------------------------

  test("a world that reports no kills at all is not filed") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica", totalKilled = 0))))
    val repo = new StubRepo()
    service(api, repo).tick().futureValue
    repo.summaries shouldBe empty
    repo.bossWrites shouldBe empty
  }

  test("a page of zeroes is not mistaken for a rolled one") {
    // What tibia.com serves part-way through its own maintenance. It differs
    // from yesterday in every figure, so without the plausibility guard it would
    // read as the clearest roll we ever saw.
    val api = new StubApi(Map("Antica" -> Right(response("Antica", totalKilled = 0))))
    val repo = new StubRepo(previous = Map("Antica" -> filedAs("Antica", dayBefore)))
    service(api, repo, now = justAfterSave).tick().futureValue
    repo.summaries shouldBe empty
  }

  test("an implausible read is tried again on the next tick") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica", totalKilled = 0))))
    val svc = service(api, new StubRepo())
    svc.tick().futureValue
    svc.tick().futureValue
    api.calls shouldBe List("Antica", "Antica")
  }

  test("a failed fetch files nothing and is retried") {
    val api = new StubApi(Map("Antica" -> Left("503 from upstream")))
    val repo = new StubRepo()
    val svc = service(api, repo)
    svc.tick().futureValue
    repo.summaries shouldBe empty
    svc.tick().futureValue
    api.calls shouldBe List("Antica", "Antica")
  }

  test("one world throwing does not stop the others") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))  // Belobra throws
    val repo = new StubRepo()
    service(api, repo, worlds = List("Antica", "Belobra")).tick().futureValue
    repo.summaries.map(_.world) shouldBe List("Antica")
  }
}
