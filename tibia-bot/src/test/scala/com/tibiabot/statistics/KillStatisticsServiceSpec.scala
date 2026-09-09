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

/** When a day is read, how often, and what happens when the read is bad. */
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

  private def response(world: String, totalKilled: Int = 2500000) = KillStatisticsResponse(
    KillStatisticsData(
      world = world,
      entries = List(
        KillStatisticsEntry("players", 378, 378, 2185, 2185),
        KillStatisticsEntry("dragon", 4, 900, 20, 6000),
        KillStatisticsEntry("Ferumbras", 0, 1, 0, 1)),
      total = KillStatisticsTotal(818, totalKilled, 5520, 21487813)),
    Information(Api(4, "4.10.0", "abc"), Some("2026-09-11T08:15:00Z"), Status(200)))

  private class StubApi(results: Map[String, Either[String, KillStatisticsResponse]]) extends KillStatisticsApi {
    val calls = mutable.ListBuffer.empty[String]
    def getKillStatistics(world: String): Future[Either[String, KillStatisticsResponse]] = {
      calls += world
      results.get(world) match {
        case Some(result) => Future.successful(result)
        case None => Future.failed(new RuntimeException("boom"))
      }
    }
  }

  private class StubRepo(alreadyFiled: Set[(String, LocalDate)] = Set.empty) extends KillStatisticsRepository {
    val bossWrites = mutable.ListBuffer.empty[List[BossKills]]
    val summaries = mutable.ListBuffer.empty[DayKillSummary]
    val order = mutable.ListBuffer.empty[String]
    def recordBossKills(rows: List[BossKills]): Unit = { bossWrites += rows; order += "bosses" }
    def recordSummary(summary: DayKillSummary): Unit = { summaries += summary; order += "summary" }
    def hasDay(world: String, saveDay: LocalDate): Boolean = alreadyFiled.contains((world, saveDay))
    def bossHistory(world: String, race: String, from: LocalDate): List[BossKills] = Nil
    def summary(world: String, saveDay: LocalDate): Option[DayKillSummary] = None
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

  // --- when a day may be read ---------------------------------------------

  test("the day read is the one that just closed") {
    val svc = service(new StubApi(Map.empty), new StubRepo())
    svc.dayToFetch(wellAfterSave) shouldBe Some(closedDay)
  }

  test("nothing is read too soon after server save") {
    // tibia.com's roll is not instant and TibiaData caches over it, so an early
    // read can still show the previous day — filed under today's date that is an
    // off-by-one nothing later can detect.
    val svc = service(new StubApi(Map.empty), new StubRepo())
    svc.dayToFetch(justAfterSave) shouldBe None
  }

  test("there is no deadline, so a bot down all morning still catches the day") {
    val svc = service(new StubApi(Map.empty), new StubRepo())
    svc.dayToFetch(berlin("2026-09-11T23:50:00+02:00")) shouldBe Some(closedDay)
  }

  test("before server save, the day read is the one before that") {
    // 09:00 on the 11th is inside the save day keyed the 10th, which has not
    // closed; what the endpoint is showing is the 9th.
    val svc = service(new StubApi(Map.empty), new StubRepo())
    svc.dayToFetch(berlin("2026-09-11T09:00:00+02:00")) shouldBe Some(LocalDate.of(2026, 9, 9))
  }

  test("a tick inside the settle window reads nothing at all") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    val repo = new StubRepo()
    service(api, repo, now = justAfterSave).tick().futureValue
    api.calls shouldBe empty
    repo.summaries shouldBe empty
  }

  // --- filing a day -------------------------------------------------------

  test("a world's day is fetched and filed") {
    val api = new StubApi(Map("Antica" -> Right(response("Antica"))))
    val repo = new StubRepo()
    service(api, repo).tick().futureValue
    api.calls shouldBe List("Antica")
    repo.summaries.map(_.saveDay) shouldBe List(closedDay)
    repo.bossWrites.head should have size BossCatalogue.bosses.size
  }

  test("the boss rows are written before the summary") {
    // hasDay reads the summary, so a failure between the two must leave the day
    // looking unfiled. The other order would mark it done with its rows missing.
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
