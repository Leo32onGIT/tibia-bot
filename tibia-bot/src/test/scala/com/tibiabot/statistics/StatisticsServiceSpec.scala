package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.domain._
import com.tibiabot.persistence.{ExperienceRepository, FragRepository, HighscoreRepository, KillStatisticsRepository}
import com.tibiabot.tibiadata.response.HighscoreEntry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, LocalDate, ZonedDateTime}
import scala.collection.mutable

/** When the daily post fires, how often, and what it does when it cannot. */
class StatisticsServiceSpec extends AnyFunSuite with Matchers {

  private val yesterday = LocalDate.of(2026, 9, 10)
  private val insideWindow = ZonedDateTime.parse("2026-09-11T10:15:00+02:00").withZoneSameInstant(Clock.Berlin)
  private val outsideWindow = ZonedDateTime.parse("2026-09-11T14:00:00+02:00").withZoneSameInstant(Clock.Berlin)

  private def delta(name: String, gained: Long) =
    ExperienceDelta(name.toLowerCase, name, "Elite Knight", 400, 399, 1000000L, gained)

  private def target(guildId: String, world: String = "Antica", posted: String = "",
                     hunted: Set[String] = Set.empty) =
    StatisticsTarget(guildId, s"Guild $guildId", world, s"channel-$guildId", posted, hunted)

  private class StubExperience(
      movers: Map[String, List[ExperienceDelta]] = Map.empty,
      fail: Boolean = false,
      losses: List[ExperienceDelta] = Nil
  ) extends ExperienceRepository {
    val lossCalls = mutable.ListBuffer.empty[(String, Set[String])]
    val moverCalls = mutable.ListBuffer.empty[(String, LocalDate)]
    def recordReadings(world: String, entries: List[HighscoreEntry], observed: Instant): Unit = ()
    def recordDaily(world: String, entries: List[HighscoreEntry], saveDay: LocalDate): Unit = ()
    def daily(world: String, name: String, from: LocalDate): List[ExperiencePoint] = Nil
    def dailyMovers(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] = {
      moverCalls += ((world, saveDay))
      if (fail) throw new RuntimeException("database is away")
      movers.getOrElse(world, Nil)
    }
    def dailyLoss(world: String, saveDay: LocalDate): Option[ExperienceDelta] = None
    def lossesAmong(world: String, saveDay: LocalDate, names: Set[String], limit: Int): List[ExperienceDelta] = {
      lossCalls += ((world, names))
      losses
    }
    def removeExpiredReadings(before: Instant): Unit = ()
    def removeExpiredDaily(before: LocalDate): Unit = ()
  }

  private class StubKillStatistics(
      days: Map[(String, LocalDate), DayKillSummary] = Map.empty,
      seen: Map[String, List[(LocalDate, Int)]] = Map.empty,
      earliest: Option[LocalDate] = None
  ) extends KillStatisticsRepository {
    def recordBossKills(rows: List[BossKills]): Unit = ()
    def recordSummary(summary: DayKillSummary): Unit = ()
    def hasDay(world: String, saveDay: LocalDate): Boolean = false
    def bossHistory(world: String, race: String, from: LocalDate): List[BossKills] = Nil
    def sightings(world: String, from: LocalDate): Map[String, List[(LocalDate, Int)]] = seen
    def earliestDay(world: String): Option[LocalDate] = earliest
    def summary(world: String, saveDay: LocalDate): Option[DayKillSummary] = days.get((world, saveDay))
    def removeExpired(before: LocalDate): Unit = ()
  }

  private class StubFrags(tallies: Map[(String, String), FragTally] = Map.empty, fail: Boolean = false)
      extends FragRepository {
    val reads = mutable.ListBuffer.empty[(String, String)]
    def record(guildId: String, events: List[FragEvent]): Unit = ()
    def attachDeathMessage(guildId: String, world: String, victim: String,
                           occurredAt: Instant, messageId: String): Unit = ()
    def tally(guildId: String, world: String, saveDay: LocalDate,
              topFraggers: Int, topRepeats: Int): FragTally = {
      reads += ((guildId, world))
      if (fail) throw new RuntimeException("guild database is away")
      tallies.getOrElse((guildId, world), FragTally.empty)
    }
    def removeExpired(guildId: String, before: LocalDate): Unit = ()
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

  /** A service wired to stubs, with the posts and marks it made. */
  private class Harness(
      targets: List[StatisticsTarget],
      experience: ExperienceRepository = new StubExperience(Map("Antica" -> List(delta("Bubble", 900)))),
      now: ZonedDateTime = insideWindow,
      announceFails: Boolean = false,
      kills: KillStatisticsRepository = new StubKillStatistics(),
      frags: FragRepository = new StubFrags()
  ) {
    val posts = mutable.ListBuffer.empty[(String, DailyReport, FragTally, List[ExperienceDelta])]
    val marks = mutable.ListBuffer.empty[(String, LocalDate)]
    val service = new StatisticsService(
      experience = experience,
      highscores = NoopHighscores,
      killStatistics = kills,
      frags = frags,
      targets = () => targets,
      announce = (target, report, tally, losses) => {
        if (announceFails) throw new RuntimeException("channel is gone")
        posts += ((target.guildId, report, tally, losses))
      },
      recordPosted = (target, day) => marks += ((target.guildId, day)),
      now = () => now
    )
  }

  test("nothing happens outside the server-save window") {
    val harness = new Harness(List(target("a")), now = outsideWindow)
    harness.service.tick()
    harness.posts shouldBe empty
    harness.marks shouldBe empty
  }

  test("a channel that has never posted gets the day that just closed") {
    val harness = new Harness(List(target("a")))
    harness.service.tick()
    harness.posts.map(_._1) shouldBe List("a")
    harness.posts.head._2.saveDay shouldBe yesterday
    harness.posts.head._2.gains.map(_.displayName) shouldBe List("Bubble")
    harness.marks shouldBe List(("a", yesterday))
  }

  test("a channel already marked for that day is left alone") {
    // The window is 45 minutes and the tick visits it about ninety times.
    val harness = new Harness(List(target("a", posted = yesterday.toString)))
    harness.service.tick()
    harness.posts shouldBe empty
    harness.marks shouldBe empty
  }

  test("yesterday's mark does not stop today's post") {
    val harness = new Harness(List(target("a", posted = yesterday.minusDays(1).toString)))
    harness.service.tick()
    harness.posts.map(_._1) shouldBe List("a")
  }

  test("a world is queried once however many discords are waiting on it") {
    // Fifty servers watching Antica is one pair of queries, not fifty.
    val experience = new StubExperience(Map("Antica" -> List(delta("Bubble", 900))))
    val harness = new Harness(List(target("a"), target("b"), target("c")), experience = experience)
    harness.service.tick()
    harness.posts.map(_._1) should contain theSameElementsAs List("a", "b", "c")
    experience.moverCalls should have size 1
  }

  test("two worlds are two queries") {
    val experience = new StubExperience(Map(
      "Antica" -> List(delta("Bubble", 900)),
      "Belobra" -> List(delta("Arieswar", 700))))
    val harness = new Harness(List(target("a", "Antica"), target("a", "Belobra")), experience = experience)
    harness.service.tick()
    experience.moverCalls.map(_._1) should contain theSameElementsAs List("Antica", "Belobra")
    harness.posts.map(_._2.world) should contain theSameElementsAs List("Antica", "Belobra")
  }

  test("a day with nothing in it is marked without posting") {
    // Nothing later in the window can change it — the last snapshot inside the
    // closing day was taken before the window opened — so retrying would be
    // ninety more queries for the same silence.
    val harness = new Harness(List(target("a")), experience = new StubExperience(Map.empty))
    harness.service.tick()
    harness.posts shouldBe empty
    harness.marks shouldBe List(("a", yesterday))
  }

  test("a failed query posts nothing and marks nothing, so the next tick retries") {
    val harness = new Harness(List(target("a")), experience = new StubExperience(fail = true))
    harness.service.tick()
    harness.posts shouldBe empty
    harness.marks shouldBe empty
  }

  test("a send that throws still marks the day") {
    // A channel the bot has lost access to would otherwise be retried for the
    // rest of the window and again every morning, and the day it missed is not
    // recoverable anyway.
    val harness = new Harness(List(target("a")), announceFails = true)
    harness.service.tick()
    harness.posts shouldBe empty
    harness.marks shouldBe List(("a", yesterday))
  }

  // --- the guild-scoped half ----------------------------------------------

  test("the frag tally is read per guild, not shared like the world figures") {
    // Two servers watching the same world read the same deaths as opposite
    // frags, and both are right — so this is the one thing that cannot be
    // computed once and handed to everybody.
    val frags = new StubFrags(Map(
      ("a", "Antica") -> FragTally(3, 1, Nil, Nil, None, None),
      ("b", "Antica") -> FragTally(1, 3, Nil, Nil, None, None)))
    val harness = new Harness(List(target("a"), target("b")), frags = frags)
    harness.service.tick()
    frags.reads should contain theSameElementsAs List(("a", "Antica"), ("b", "Antica"))
    harness.posts.find(_._1 == "a").map(_._3.enemiesKilled) shouldBe Some(3)
    harness.posts.find(_._1 == "b").map(_._3.enemiesKilled) shouldBe Some(1)
  }

  test("frags alone are worth a post") {
    // A world can have a quiet day in the highscores and a war in it.
    val frags = new StubFrags(Map(("a", "Antica") -> FragTally(4, 2, Nil, Nil, None, None)))
    val harness = new Harness(List(target("a")), experience = new StubExperience(Map.empty), frags = frags)
    harness.service.tick()
    harness.posts.map(_._1) shouldBe List("a")
    harness.posts.head._2.isEmpty shouldBe true
    harness.posts.head._3.enemiesKilled shouldBe 4
  }

  test("a frag query that fails does not cost the rest of the post") {
    val harness = new Harness(List(target("a")), frags = new StubFrags(fail = true))
    harness.service.tick()
    harness.posts.map(_._1) shouldBe List("a")
    harness.posts.head._3 shouldBe FragTally.empty
    harness.posts.head._2.gains should not be empty
  }

  test("enemy experience losses are read for the guild's own hunted list") {
    val experience = new StubExperience(
      Map("Antica" -> List(delta("Bubble", 900))),
      losses = List(delta("Vestrik", -24180400)))
    val harness = new Harness(List(target("a", hunted = Set("vestrik", "grimjaw"))), experience = experience)
    harness.service.tick()
    experience.lossCalls shouldBe List(("Antica", Set("vestrik", "grimjaw")))
    harness.posts.head._4.map(_.displayName) shouldBe List("Vestrik")
  }

  test("a guild hunting nobody is not asked for enemy losses at all") {
    val experience = new StubExperience(Map("Antica" -> List(delta("Bubble", 900))))
    val harness = new Harness(List(target("a")), experience = experience)
    harness.service.tick()
    experience.lossCalls shouldBe empty
    harness.posts.head._4 shouldBe empty
  }

  // --- the kill statistics half -------------------------------------------

  test("the day's kill statistics ride along when the snapshot was taken") {
    val summary = DayKillSummary("Antica", yesterday, Some(("dragon", 40)), Some(("wyrm", 3)), 12, 900L, 20)
    val harness = new Harness(List(target("a")), kills = new StubKillStatistics(Map(("Antica", yesterday) -> summary)))
    harness.service.tick()
    harness.posts.head._2.kills shouldBe Some(summary)
  }

  test("a day whose snapshot was never taken still posts everything else") {
    val harness = new Harness(List(target("a")))
    harness.service.tick()
    harness.posts.head._2.kills shouldBe None
    harness.posts.head._2.gains should not be empty
  }

  test("kill statistics alone are worth a post") {
    val summary = DayKillSummary("Antica", yesterday, Some(("dragon", 40)), None, 0, 900L, 0)
    val harness = new Harness(
      List(target("a")),
      experience = new StubExperience(Map.empty),
      kills = new StubKillStatistics(Map(("Antica", yesterday) -> summary)))
    harness.service.tick()
    harness.posts.map(_._1) shouldBe List("a")
  }

  // --- boss predictions ----------------------------------------------------

  test("a boss seen recently enough is predicted into the report") {
    val kills = new StubKillStatistics(
      seen = Map("furyosa" -> List((yesterday.minusDays(20), 1))),
      earliest = Some(yesterday.minusDays(120)))
    val harness = new Harness(List(target("a")), kills = kills)
    harness.service.tick()
    val report = harness.posts.head._2
    report.predictions.map(_.boss.name) shouldBe List("Furyosa")
    report.dueBosses.map(_.boss.name) shouldBe List("Furyosa")
  }

  test("a world with no history predicts nothing and counts every boss as waiting") {
    val harness = new Harness(List(target("a")))
    harness.service.tick()
    harness.posts.head._2.predictions shouldBe empty
    harness.posts.head._2.awaitingSighting shouldBe BossCatalogue.bosses.count(_.predict)
  }

  test("a due boss alone is worth a post") {
    val kills = new StubKillStatistics(
      seen = Map("furyosa" -> List((yesterday.minusDays(20), 1))),
      earliest = Some(yesterday.minusDays(120)))
    val harness = new Harness(List(target("a")), experience = new StubExperience(Map.empty), kills = kills)
    harness.service.tick()
    harness.posts.map(_._1) shouldBe List("a")
  }

  test("a world whose bosses are all quiet is not posted for on that account alone") {
    // Predictions with nothing due are not news; the report reads as empty and
    // the other halves decide.
    val kills = new StubKillStatistics(
      seen = Map("furyosa" -> List((yesterday.minusDays(1), 1))),
      earliest = Some(yesterday.minusDays(120)))
    val harness = new Harness(List(target("a")), experience = new StubExperience(Map.empty), kills = kills)
    harness.service.tick()
    harness.posts shouldBe empty
    harness.marks shouldBe List(("a", yesterday))
  }

  test("one guild's broken channel does not stop another's post") {
    val experience = new StubExperience(Map("Antica" -> List(delta("Bubble", 900))))
    val posts = mutable.ListBuffer.empty[String]
    val marks = mutable.ListBuffer.empty[String]
    val service = new StatisticsService(
      experience = experience,
      highscores = NoopHighscores,
      killStatistics = new StubKillStatistics(),
      frags = new StubFrags(),
      targets = () => List(target("broken"), target("fine")),
      announce = (target, _, _, _) =>
        if (target.guildId == "broken") throw new RuntimeException("channel is gone") else posts += target.guildId,
      recordPosted = (target, _) => marks += target.guildId,
      now = () => insideWindow
    )
    service.tick()
    posts shouldBe List("fine")
    marks should contain theSameElementsAs List("broken", "fine")
  }
}
