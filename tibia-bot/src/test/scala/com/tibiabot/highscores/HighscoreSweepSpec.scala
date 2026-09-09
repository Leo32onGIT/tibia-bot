package com.tibiabot.highscores

import com.tibiabot.domain.{ExperiencePoint, FiledEvent, HighscoreEvent, HighscoreRecord}
import com.tibiabot.persistence.{ExperienceRepository, HighscoreRepository}
import com.tibiabot.tibiadata._
import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, LocalDate}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** One list's pass over one world: what it reads, what it writes, and how it
 *  behaves when the endpoint only half answers. */
class HighscoreSweepSpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val world = "Antica"
  private val snapshot = Instant.parse("2026-09-02T05:40:00Z")
  private val sword = HighscoreList(HighscoreCategory.SwordFighting, HighscoreVocation.All)

  private def entry(name: String, value: Long, level: Int = 400) =
    HighscoreEntry(1, name, "Elite Knight", world, level, value)

  private def response(entries: List[HighscoreEntry], page: Int,
                       totalPages: Int = Highscores.MaxPages, totalRecords: Int = Highscores.MaxRecords) =
    HighscoresResponse(
      HighscoreData(world, "swordfighting", "all", 20, entries, HighscorePage(page, totalPages, totalRecords)),
      Information(Api(4, "4.10.0", "abc"), Some("2026-09-02T06:00:00Z"), Status(200))
    )

  /** Serves page N from `pages`, and a Left for anything not in it. */
  private class StubApi(pages: Map[Int, List[HighscoreEntry]]) extends HighscoresApi {
    val requested = mutable.ListBuffer.empty[(String, String, Int)]
    def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]] = {
      requested += ((world, list.toString, page))
      Future.successful(pages.get(page).map(entries => response(entries, page)).toRight("boom"))
    }
  }

  /** A list that ends before page 20, the way a vocation-filtered list does on a
   *  young world: the pages up to `length` answer, and every page past it is
   *  refused exactly as the endpoint refuses page 21. */
  private class ShortListApi(length: Int) extends HighscoresApi {
    val requested = mutable.ListBuffer.empty[Int]
    def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]] = {
      requested += page
      Future.successful(
        if (page > length) Left("the provided page is larger than max amount of pages")
        else Right(response(List(entry(s"Monk $page", 40)), page, totalPages = length, totalRecords = length * 50)))
    }
  }

  private class StubRepo(seed: Map[String, HighscoreRecord] = Map.empty) extends HighscoreRepository {
    val upserts = mutable.ListBuffer.empty[(String, String, List[HighscoreEntry], Instant)]
    val filed = mutable.ListBuffer.empty[HighscoreEvent]
    def load(world: String, category: String): Map[String, HighscoreRecord] = seed
    def upsertAll(world: String, category: String, entries: List[HighscoreEntry], snapshotAt: Instant): Unit =
      upserts += ((world, category, entries, snapshotAt))
    def recordEvents(events: List[HighscoreEvent]): Unit = filed ++= events
    def events(world: String, since: Instant): List[HighscoreEvent] = filed.toList
    def eventsAfter(afterId: Long, limit: Int): List[FiledEvent] = Nil
    def maxEventId(): Long = 0L
    def feedCursor(botId: String): Option[Long] = None
    def setFeedCursor(botId: String, eventId: Long): Unit = ()
    def removeStale(world: String, before: Instant): Unit = ()
    def removeExpiredEvents(before: Instant): Unit = ()
  }

  private class StubExperience extends ExperienceRepository {
    val readings = mutable.ListBuffer.empty[(String, Int, Instant)]
    val dailies = mutable.ListBuffer.empty[(String, Int, LocalDate)]
    def recordReadings(world: String, entries: List[HighscoreEntry], observed: Instant): Unit =
      readings += ((world, entries.size, observed))
    def recordDaily(world: String, entries: List[HighscoreEntry], saveDay: LocalDate): Unit =
      dailies += ((world, entries.size, saveDay))
    def daily(world: String, name: String, from: LocalDate): List[ExperiencePoint] = Nil
    def removeExpiredReadings(before: Instant): Unit = ()
    def removeExpiredDaily(before: LocalDate): Unit = ()
  }

  private def sweeper(api: HighscoresApi, repo: HighscoreRepository, exp: ExperienceRepository = new StubExperience) =
    new HighscoreSweep(api, repo, exp, () => 1.milli, _ => Future.unit)

  private def fullList(entries: List[HighscoreEntry]): Map[Int, List[HighscoreEntry]] =
    Highscores.pages.map(page => page -> (if (page == 1) entries else Nil)).toMap

  test("every page is read, in order, one request each") {
    val api = new StubApi(fullList(List(entry("Bubble", 116))))
    val result = await(sweeper(api, new StubRepo).sweepList(world, sword, snapshot))

    api.requested should have size Highscores.MaxPages
    api.requested.map(_._3).toList shouldBe Highscores.pages
    result.pagesRead shouldBe Highscores.MaxPages
    result.pagesFailed shouldBe 0
  }

  test("a list shorter than 20 pages is read to its end and no further") {
    // Penumbra's monk magic level is 11 pages. Asking for page 12 is refused
    // with the same 400 that page 21 gets, so walking to 20 regardless would
    // report nine failed pages and warn about a list that is complete.
    val api = new ShortListApi(length = 11)
    val result = await(sweeper(api, new StubRepo).sweepList(world, sword, snapshot))

    api.requested.toList shouldBe (1 to 11).toList
    result.pagesRead shouldBe 11
    result.pagesFailed shouldBe 0
    result.characters shouldBe 11
  }

  test("what came back is written against the snapshot, not the time of writing") {
    val api = new StubApi(fullList(List(entry("Bubble", 116), entry("Xerxess", 114))))
    val repo = new StubRepo
    await(sweeper(api, repo).sweepList(world, sword, snapshot))

    repo.upserts should have size 1
    val (upsertWorld, category, entries, at) = repo.upserts.head
    upsertWorld shouldBe world
    category shouldBe "swordfighting"
    entries.map(_.name) shouldBe List("Bubble", "Xerxess")
    at shouldBe snapshot
  }

  test("advances are returned and filed") {
    val previous = Map("bubble" -> HighscoreRecord("bubble", "Bubble", "Elite Knight", 400, 115, snapshot.minusSeconds(3600)))
    val api = new StubApi(fullList(List(entry("Bubble", 116))))
    val repo = new StubRepo(previous)
    val result = await(sweeper(api, repo).sweepList(world, sword, snapshot))

    result.advances.map(_.score) shouldBe List(116)
    repo.filed.map(_.displayName).toList shouldBe List("Bubble")
  }

  test("a missing page is survivable: what did arrive is still written") {
    // Nothing is ever deleted here, so the characters on the missing page keep
    // the score and last_seen they had, and their advance is found next
    // snapshot against the same baseline — late, not lost.
    val pages = fullList(List(entry("Bubble", 116))) - 7
    val api = new StubApi(pages)
    val repo = new StubRepo
    val result = await(sweeper(api, repo).sweepList(world, sword, snapshot))

    result.pagesFailed shouldBe 1
    result.pagesRead shouldBe Highscores.MaxPages - 1
    repo.upserts should have size 1
  }

  test("a list that answered nothing writes nothing at all") {
    // Stamping last_seen on an empty read would prove nothing and would cost the
    // baseline of every character in the list on the next pass.
    val api = new StubApi(Map.empty)
    val repo = new StubRepo
    val result = await(sweeper(api, repo).sweepList(world, sword, snapshot))

    result.pagesFailed shouldBe Highscores.MaxPages
    result.characters shouldBe 0
    repo.upserts shouldBe empty
    repo.filed shouldBe empty
  }

  test("a request that throws is counted as a failed page, not a failed sweep") {
    val api = new HighscoresApi {
      def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]] =
        if (page == 3) Future.failed(new RuntimeException("connection reset"))
        else Future.successful(Right(response(if (page == 1) List(entry("Bubble", 116)) else Nil, page)))
    }
    val result = await(sweeper(api, new StubRepo).sweepList(world, sword, snapshot))
    result.pagesFailed shouldBe 1
    result.characters shouldBe 1
  }

  test("the experience list feeds the history tables and announces nothing") {
    val experience = HighscoreLists.experience
    val previous = Map("bubble" -> HighscoreRecord("bubble", "Bubble", "Elite Knight", 400, 1000L, snapshot.minusSeconds(3600)))
    val api = new StubApi(fullList(List(entry("Bubble", 2000L))))
    val repo = new StubRepo(previous)
    val history = new StubExperience
    val result = await(sweeper(api, repo, history).sweepList(world, experience, snapshot))

    result.advances shouldBe empty
    repo.filed shouldBe empty
    history.readings.map(_._3).toList shouldBe List(snapshot)
    history.dailies should have size 1
    // 05:40 UTC is 07:40 Berlin, before the 10:00 save, so it belongs to the
    // previous save day rather than the calendar one.
    history.dailies.head._3 shouldBe LocalDate.parse("2026-09-01")
  }

  test("a skill list never touches the history tables") {
    val history = new StubExperience
    await(sweeper(new StubApi(fullList(List(entry("Bubble", 116)))), new StubRepo, history).sweepList(world, sword, snapshot))
    history.readings shouldBe empty
    history.dailies shouldBe empty
  }
}
