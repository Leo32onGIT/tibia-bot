package com.tibiabot.highscores

import com.tibiabot.domain.{ExperienceDelta, FiledEvent, HighscoreEvent, HighscoreRecord}
import com.tibiabot.persistence.{ExperienceRepository, HighscoreRepository}
import com.tibiabot.tibiadata._
import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, LocalDate}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** When the sweep runs, over what, and — mostly — when it declines to. */
class HighscoreServiceSpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  /** Answers every request with a page whose age puts the snapshot at
   *  `snapshotAt`, and counts what was asked for. */
  private class StubApi(var generatedAt: String = "2026-09-02T06:00:00Z", var age: Int = 20) extends HighscoresApi {
    val calls = new AtomicInteger(0)
    val seen = mutable.Set.empty[(String, String)]
    def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]] = {
      calls.incrementAndGet()
      seen.synchronized { seen += ((world, list.toString)) }
      Future.successful(Right(HighscoresResponse(
        HighscoreData(world, list.category.slug, list.vocation.slug, age, Nil,
          HighscorePage(page, Highscores.MaxPages, Highscores.MaxRecords)),
        Information(Api(4, "4.10.0", "abc"), Some(generatedAt), Status(200))
      )))
    }
  }

  private object NoopRepo extends HighscoreRepository {
    def load(world: String, category: String): Map[String, HighscoreRecord] = Map.empty
    def vocations(world: String): Map[String, String] = Map.empty
    def upsertAll(world: String, category: String, entries: List[HighscoreEntry], snapshotAt: Instant): Unit = ()
    def recordEvents(events: List[HighscoreEvent]): Unit = ()
    def events(world: String, since: Instant): List[HighscoreEvent] = Nil
    def topAdvance(world: String, from: Instant, to: Instant): Option[HighscoreEvent] = None
    def eventsAfter(afterId: Long, limit: Int): List[FiledEvent] = Nil
    def maxEventId(): Long = 0L
    def feedCursor(botId: String): Option[Long] = None
    def setFeedCursor(botId: String, eventId: Long): Unit = ()
    def removeStale(world: String, before: Instant): Unit = ()
    def removeExpiredEvents(before: Instant): Unit = ()
  }

  private object NoopExperience extends ExperienceRepository {
    def recordDaily(world: String, entries: List[HighscoreEntry], saveDay: LocalDate): Unit = ()
    def dailyGains(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] = Nil
    def dailyLosses(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] = Nil
    def lossesAmong(world: String, saveDay: LocalDate, names: Set[String], limit: Int): List[ExperienceDelta] = Nil
    def removeExpiredDaily(before: LocalDate): Unit = ()
  }

  private def service(api: HighscoresApi, worlds: List[String] = List("Antica")) = {
    val pace = new HighscoreGap(1.milli)
    new HighscoreService(
      api = api,
      sweep = new HighscoreSweep(api, NoopRepo, NoopExperience, () => pace.get, _ => Future.unit),
      pace = pace,
      trackedWorlds = () => worlds,
      settings = HighscoreSettings(1.second, workers = 2, minRequestGap = 1.milli, seedLatency = Duration.Zero)
    )
  }

  test("a new snapshot sweeps every list of every world") {
    val api = new StubApi()
    val svc = service(api)
    await(svc.tick())

    svc.snapshotSeen shouldBe Some(Instant.parse("2026-09-02T05:40:00Z"))
    // The probe, plus every page of every list.
    api.calls.get() shouldBe 1 + HighscoreLists.all.size * Highscores.MaxPages
    api.seen.map(_._2) should contain theSameElementsAs HighscoreLists.all.map(_.toString)
    svc.lastSweep.map(_.lists) shouldBe Some(HighscoreLists.all.size)
  }

  test("the same snapshot is probed and then left alone") {
    val api = new StubApi()
    val svc = service(api)
    await(svc.tick())
    val afterFirst = api.calls.get()

    await(svc.tick())
    // One more request — the probe — and nothing behind it.
    api.calls.get() shouldBe afterFirst + 1
  }

  test("a snapshot re-read a minute later is not mistaken for a new one") {
    // The published age is floored to the minute, so the estimate drifts by up
    // to 59 seconds across two reads of the same underlying snapshot.
    val api = new StubApi()
    val svc = service(api)
    await(svc.tick())
    val afterFirst = api.calls.get()

    api.generatedAt = "2026-09-02T06:00:59Z"
    await(svc.tick())
    api.calls.get() shouldBe afterFirst + 1
  }

  test("the next hour's snapshot sweeps again") {
    val api = new StubApi()
    val svc = service(api)
    await(svc.tick())
    val afterFirst = api.calls.get()

    api.generatedAt = "2026-09-02T07:00:00Z"
    await(svc.tick())
    api.calls.get() should be > afterFirst + 1
    svc.snapshotSeen shouldBe Some(Instant.parse("2026-09-02T06:40:00Z"))
  }

  test("every tracked world is swept, in a stable order") {
    val api = new StubApi()
    val svc = service(api, worlds = List("Secura", "Antica", "Antica", "Refugia"))
    // Deduplicated and sorted, so two sweeps' logs line up against each other.
    svc.worlds() shouldBe List("Antica", "Refugia", "Secura")

    await(svc.tick())
    api.seen.map(_._1) shouldBe Set("Antica", "Refugia", "Secura")
  }

  test("no tracked worlds means no requests at all, not a crash") {
    val api = new StubApi()
    await(service(api, worlds = Nil).tick())
    api.calls.get() shouldBe 0
  }

  /** Refuses everything the way our own instance would with restriction mode
   *  back on after a container recreate. */
  private class RefusingApi extends HighscoresApi {
    val calls = new AtomicInteger(0)
    def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]] = {
      calls.incrementAndGet()
      Future.successful(Left("the provided page is not available due to restriction mode"))
    }
  }

  test("a probe that fails leaves the snapshot unchanged and sweeps nothing") {
    val api = new RefusingApi
    val svc = service(api)
    await(svc.tick())

    svc.snapshotSeen shouldBe None
    api.calls.get() shouldBe 1
  }

  test("a probe that throws is caught, so the schedule survives it") {
    val api = new HighscoresApi {
      def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]] =
        Future.failed(new RuntimeException("connection reset"))
    }
    val svc = service(api)
    await(svc.tick())
    svc.snapshotSeen shouldBe None

    // And the lock was released, so the next probe is not skipped forever.
    await(svc.tick())
    svc.snapshotSeen shouldBe None
  }

  test("advances are filed for the feed to post, never announced from the sweep") {
    val previous = Map("bubble" -> HighscoreRecord("bubble", "Bubble", "Elite Knight", 400, 1, Instant.parse("2026-09-02T05:00:00Z")))
    val filed = mutable.ListBuffer.empty[HighscoreEvent]
    val repo = new HighscoreRepository {
      def load(world: String, category: String): Map[String, HighscoreRecord] = previous
      def vocations(world: String): Map[String, String] = Map.empty
      def upsertAll(world: String, category: String, entries: List[HighscoreEntry], snapshotAt: Instant): Unit = ()
      def recordEvents(events: List[HighscoreEvent]): Unit = filed.synchronized { filed ++= events }
      def events(world: String, since: Instant): List[HighscoreEvent] = Nil
    def topAdvance(world: String, from: Instant, to: Instant): Option[HighscoreEvent] = None
      def eventsAfter(afterId: Long, limit: Int): List[FiledEvent] = Nil
      def maxEventId(): Long = 0L
      def feedCursor(botId: String): Option[Long] = None
      def setFeedCursor(botId: String, eventId: Long): Unit = ()
      def removeStale(world: String, before: Instant): Unit = ()
      def removeExpiredEvents(before: Instant): Unit = ()
    }
    val api = new HighscoresApi {
      def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]] =
        Future.successful(Right(HighscoresResponse(
          HighscoreData(world, list.category.slug, list.vocation.slug, 20,
            if (page == 1) List(HighscoreEntry(1, "Bubble", "Elite Knight", world, 400, 116)) else Nil,
            HighscorePage(page, Highscores.MaxPages, Highscores.MaxRecords)),
          Information(Api(4, "4.10.0", "abc"), Some("2026-09-02T06:00:00Z"), Status(200))
        )))
    }
    val pace = new HighscoreGap(1.milli)
    val svc = new HighscoreService(
      api = api,
      sweep = new HighscoreSweep(api, repo, NoopExperience, () => pace.get, _ => Future.unit),
      pace = pace,
      trackedWorlds = () => List("Antica"),
      settings = HighscoreSettings(1.second, workers = 2, minRequestGap = 1.milli, seedLatency = Duration.Zero)
    )
    await(svc.tick())

    // One advance per skill list, never for experience. The sweep posts none of
    // them itself — a bot can only write to its own guilds, so announcing is
    // HighscoreFeed's job on every bot rather than the primary's on everyone's
    // behalf.
    svc.lastSweep.map(_.advances) shouldBe Some(HighscoreLists.skills.size)
    filed.map(_.category).distinct.toSet shouldBe HighscoreLists.skills.map(_.category.slug).toSet
  }

  /** Hands back `start`, then `start + took`, once per sweep. `runSweep` reads
   *  the clock exactly twice — once before the lanes, once after — so this
   *  makes a sweep take a known time without one actually taking it. */
  private class SweepClock(start: Instant, took: java.time.Duration) extends (() => Instant) {
    private val reads = new AtomicInteger(0)
    def apply(): Instant = if (reads.getAndIncrement() % 2 == 0) start else start.plus(took)
  }

  test("what one sweep measured is what the next one is paced by") {
    // The September 2026 drift in miniature. One world is 240 pages over two
    // lanes in a ten-minute window, so the sleeps alone come to 5s a page. Let
    // each page really take 5.4s, and the 400ms the request cost has to come
    // out of the next sweep's sleeping rather than be added on top of it —
    // otherwise every sweep overruns by the same margin, later each time, until
    // it starts missing snapshots altogether.
    val api = new StubApi()
    val pace = new HighscoreGap(1.milli)
    val perLane = HighscoreLists.all.size * Highscores.MaxPages / 2
    val svc = new HighscoreService(
      api = api,
      sweep = new HighscoreSweep(api, NoopRepo, NoopExperience, () => pace.get, _ => Future.unit),
      pace = pace,
      trackedWorlds = () => List("Antica"),
      settings = HighscoreSettings(10.minutes, workers = 2, minRequestGap = 1.milli, seedLatency = Duration.Zero),
      now = new SweepClock(Instant.parse("2026-09-02T06:00:00Z"), java.time.Duration.ofMillis(5400L * perLane))
    )

    await(svc.tick())
    // Nothing measured yet, so the first sweep spends the whole budget sleeping.
    pace.get shouldBe 5.seconds
    svc.lastSweep.map(_.latency) shouldBe Some(400.millis)

    api.generatedAt = "2026-09-02T07:00:00Z"
    await(svc.tick())
    // And the second one hands those 400ms back, so it lands on the budget
    // rather than 8% past it.
    pace.get shouldBe 4600.millis
  }
}
