package com.tibiabot.statistics

import com.tibiabot.domain._
import com.tibiabot.domain.time.Clock
import com.tibiabot.persistence._
import com.tibiabot.tibiadata.KillStatisticsApi
import com.tibiabot.tibiadata.response._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import java.time.{Instant, LocalDate, ZonedDateTime}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

/** The snapshot and the post, on one clock.
 *
 *  Both services are covered on their own elsewhere, and both passed while the
 *  post could never carry a creature figure: each was right about its own half
 *  and nothing looked at the two schedules together. The snapshot was taken an
 *  hour after server save and the post went out inside the 45 minutes before
 *  that, so the row the post wanted was written a quarter of an hour after it
 *  had already gone out and marked the day done — every day, not sometimes.
 *
 *  These drive the real pair against one instant to hold that shut. */
class DailyPostPipelineSpec extends AnyFunSuite with Matchers with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(15, Millis))

  private def berlin(text: String) = ZonedDateTime.parse(text).withZoneSameInstant(Clock.Berlin)

  /** A minute past server save: the post's first tick of the day, and long
   *  before the old hour-long settle would have allowed a read. */
  private val justAfterSave = berlin("2026-09-11T10:01:00+02:00")
  private val closedDay = LocalDate.of(2026, 9, 10)
  private val dayBefore = closedDay.minusDays(1)

  private def response(world: String, totalKilled: Int) = KillStatisticsResponse(
    KillStatisticsData(
      world = world,
      entries = List(
        KillStatisticsEntry("players", 378, 378, 2185, 2185),
        KillStatisticsEntry("dragon", 4, 900, 20, 6000),
        KillStatisticsEntry("Ferumbras", 0, 1, 0, 1)),
      total = KillStatisticsTotal(818, totalKilled, 5520, 21487813)),
    Information(Api(4, "4.10.0", "abc"), Some("2026-09-11T08:15:00Z"), Status(200)))

  /** Whatever the endpoint is showing this moment. Starts on the day before the
   *  closing one — which is what "tibia.com has not rolled yet" looks like —
   *  and `roll()` moves it on. */
  private class FakeTibia(world: String) extends KillStatisticsApi {
    private var totalKilled = 2400000
    private var extra = List.empty[KillStatisticsEntry]
    def roll(): Unit = totalKilled = 2500000
    def alsoKilled(race: String, killed: Int): Unit =
      extra = extra :+ KillStatisticsEntry(race, 0, killed, 0, killed)
    def getKillStatistics(asked: String): Future[Either[String, KillStatisticsResponse]] =
      Future.successful(
        if (asked != world) Left("unknown world")
        else {
          val body = response(asked, totalKilled)
          val data = body.killstatistics
          Right(body.copy(killstatistics = data.copy(entries = data.entries ++ extra)))
        })
  }

  /** The shared cache, as far as these two care about it. */
  private class Cache extends KillStatisticsRepository {
    private val summaries = mutable.Map.empty[(String, LocalDate), DayKillSummary]
    private val bosses = mutable.Map.empty[(String, LocalDate), List[BossKills]]
    def recordBossKills(rows: List[BossKills]): Unit =
      rows.groupBy(row => (row.world, row.saveDay)).foreach { case (key, value) => bosses.put(key, value) }
    def recordSummary(summary: DayKillSummary): Unit =
      summaries.put((summary.world, summary.saveDay), summary)
    def hasDay(world: String, saveDay: LocalDate): Boolean = summaries.contains((world, saveDay))
    def bossHistory(world: String, race: String, from: LocalDate): List[BossKills] = Nil
    def sightings(world: String, from: LocalDate): Map[String, List[(LocalDate, Int)]] =
      bosses.toList.collect { case ((w, day), rows) if w == world && !day.isBefore(from) => (day, rows) }
        .flatMap { case (day, rows) => rows.filter(_.killed > 0).map(row => (row.race.toLowerCase, (day, row.killed))) }
        .groupBy(_._1).map { case (race, entries) => race -> entries.map(_._2).sortBy(_._1).reverse }
    def earliestDay(world: String): Option[LocalDate] =
      summaries.keys.filter(_._1 == world).map(_._2).toList.sortWith(_.isBefore(_)).headOption
    def killsOn(world: String, saveDay: LocalDate): List[BossKills] =
      bosses.getOrElse((world, saveDay), Nil).filter(_.killed > 0).sortBy(row => (-row.killed, row.race))
    def summary(world: String, saveDay: LocalDate): Option[DayKillSummary] = summaries.get((world, saveDay))
    def removeExpired(before: LocalDate): Unit = ()
  }

  private class StubExperience extends ExperienceRepository {
    def recordDaily(world: String, entries: List[HighscoreEntry], saveDay: LocalDate): Unit = ()
    def dailyMovers(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] =
      List(ExperienceDelta("bubble", "Bubble", "Elite Knight", 400, 399, 1000000L, 900L))
    def dailyLosses(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] = Nil
    def lossesAmong(world: String, saveDay: LocalDate, names: Set[String], limit: Int): List[ExperienceDelta] = Nil
    def removeExpiredDaily(before: LocalDate): Unit = ()
  }

  private object NoopHighscores extends HighscoreRepository {
    def load(world: String, category: String): Map[String, HighscoreRecord] = Map.empty
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

  private object NoFrags extends FragRepository {
    def record(guildId: String, events: List[FragEvent]): Unit = ()
    def attachDeathMessage(guildId: String, world: String, victim: String,
                           occurredAt: Instant, messageId: String): Unit = ()
    def tally(guildId: String, world: String, saveDay: LocalDate,
              topFraggers: Int, topRepeats: Int): FragTally = FragTally.empty
    def removeExpired(guildId: String, before: LocalDate): Unit = ()
  }

  /** One world, one discord, the real services, one clock. */
  private class Pipeline(world: String = "Antica") {
    val cache = new Cache
    val tibia = new FakeTibia(world)
    val boards = mutable.ListBuffer.empty[DailyReport]
    val creatures = mutable.ListBuffer.empty[DailyReport]
    private var posted = ""
    private var killsPosted = ""

    val snapshot = new KillStatisticsService(
      api = tibia,
      repository = cache,
      trackedWorlds = () => List(world),
      gap = () => 0.millis,
      delay = _ => Future.unit,
      now = () => justAfterSave)

    val post = new StatisticsService(
      experience = new StubExperience,
      highscores = NoopHighscores,
      killStatistics = cache,
      frags = NoFrags,
      worldOnline = new WorldOnlineRepository {
        def recordSample(world: String, saveDay: LocalDate, online: Int, levelTotal: Long): Unit = ()
        def averages(world: String, saveDay: LocalDate): Option[WorldOnlineAverage] = None
        def removeExpired(before: LocalDate): Unit = ()
      },
      targets = () => List(StatisticsTarget(
        "guild", "Guild", world, "channel", posted, Set.empty, killsPosted)),
      announce = (_, report, _, _) => boards += report,
      announceKills = (_, report) => creatures += report,
      recordPosted = (_, day) => posted = day.toString,
      recordKillsPosted = (_, day) => killsPosted = day.toString,
      now = () => justAfterSave)

    /** Yesterday's snapshot, which is what the roll is recognised against. */
    def seedPreviousDay(): Unit =
      cache.recordSummary(KillStatistics.summary(response(world, 2400000).killstatistics, dayBefore))
  }

  test("the day the post reports is the day the snapshot files") {
    // The bug this pair exists for: two services agreeing on the clock but not
    // on the day, which no test of either alone could see.
    val pipeline = new Pipeline()
    pipeline.snapshot.dayToFetch(justAfterSave) shouldBe DailyStatistics.reportedDay(justAfterSave)
    pipeline.snapshot.dayToFetch(justAfterSave) shouldBe closedDay
  }

  test("a minute past server save, the snapshot is filed and the post carries it") {
    val pipeline = new Pipeline()
    pipeline.seedPreviousDay()
    pipeline.tibia.roll()

    pipeline.snapshot.tick().futureValue
    pipeline.post.tick()

    pipeline.boards.map(_.saveDay) shouldBe List(closedDay)
    pipeline.boards.head.kills.map(_.mostKilled) shouldBe Some(Some(("dragon", 900)))
    // One message, because the figures were there when the board went out.
    pipeline.creatures shouldBe empty
  }

  test("a board sent before the roll is followed by the creature figures") {
    val pipeline = new Pipeline()
    pipeline.seedPreviousDay()

    // tibia.com has not rolled: the snapshot files nothing and the board goes
    // out on its own rather than waiting.
    pipeline.snapshot.tick().futureValue
    pipeline.post.tick()
    pipeline.boards.map(_.kills) shouldBe List(None)
    pipeline.creatures shouldBe empty

    // It rolls a minute later, and the other half follows.
    pipeline.tibia.roll()
    pipeline.snapshot.tick().futureValue
    pipeline.post.tick()
    pipeline.boards should have size 1
    pipeline.creatures.map(_.saveDay) shouldBe List(closedDay)
    pipeline.creatures.head.kills.map(_.mostKilled) shouldBe Some(Some(("dragon", 900)))
  }

  test("neither half is posted twice however often the tick runs") {
    val pipeline = new Pipeline()
    pipeline.seedPreviousDay()
    pipeline.tibia.roll()

    (1 to 5).foreach { _ =>
      pipeline.snapshot.tick().futureValue
      pipeline.post.tick()
    }
    pipeline.boards should have size 1
    pipeline.creatures shouldBe empty
  }

  test("a special boss killed that day reaches the post under its own name") {
    // The whole path: the endpoint's race string, into the day's rows, back out
    // by name. A mismatch anywhere produces no row rather than a wrong one,
    // which is why this is worth an end-to-end test rather than two unit ones.
    val plunder = SpecialKills.all.head
    val pipeline = new Pipeline()
    pipeline.seedPreviousDay()
    pipeline.tibia.roll()
    pipeline.tibia.alsoKilled(plunder.race, 3)

    pipeline.snapshot.tick().futureValue
    pipeline.post.tick()

    pipeline.boards.head.specialKills shouldBe List(plunder -> 3)
    pipeline.boards.head.topKills.map(_.race) should not contain plunder.race
  }

  test("the boss predictions can see the day being reported") {
    // The snapshot writes the boss rows before the summary, and the post waits
    // on the summary — so a boss killed on the closing day is in the history the
    // prediction reads rather than a day out of reach.
    val pipeline = new Pipeline()
    pipeline.seedPreviousDay()
    pipeline.tibia.roll()

    pipeline.snapshot.tick().futureValue
    pipeline.post.tick()

    pipeline.cache.sightings("Antica", dayBefore).get("ferumbras")
      .map(_.map(_._1)) shouldBe Some(List(closedDay))
  }
}
