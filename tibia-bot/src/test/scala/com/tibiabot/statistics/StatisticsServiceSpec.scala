package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.domain.{ExperienceDelta, ExperiencePoint, FiledEvent, HighscoreEvent, HighscoreRecord}
import com.tibiabot.persistence.{ExperienceRepository, HighscoreRepository}
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

  private def target(guildId: String, world: String = "Antica", posted: String = "") =
    StatisticsTarget(guildId, s"Guild $guildId", world, s"channel-$guildId", posted)

  private class StubExperience(movers: Map[String, List[ExperienceDelta]] = Map.empty, fail: Boolean = false)
      extends ExperienceRepository {
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
    def removeExpiredReadings(before: Instant): Unit = ()
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

  /** A service wired to stubs, with the posts and marks it made. */
  private class Harness(
      targets: List[StatisticsTarget],
      experience: ExperienceRepository = new StubExperience(Map("Antica" -> List(delta("Bubble", 900)))),
      now: ZonedDateTime = insideWindow,
      announceFails: Boolean = false
  ) {
    val posts = mutable.ListBuffer.empty[(String, DailyReport)]
    val marks = mutable.ListBuffer.empty[(String, LocalDate)]
    val service = new StatisticsService(
      experience = experience,
      highscores = NoopHighscores,
      targets = () => targets,
      announce = (target, report) => {
        if (announceFails) throw new RuntimeException("channel is gone")
        posts += ((target.guildId, report))
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

  test("one guild's broken channel does not stop another's post") {
    val experience = new StubExperience(Map("Antica" -> List(delta("Bubble", 900))))
    val posts = mutable.ListBuffer.empty[String]
    val marks = mutable.ListBuffer.empty[String]
    val service = new StatisticsService(
      experience = experience,
      highscores = NoopHighscores,
      targets = () => List(target("broken"), target("fine")),
      announce = (target, _) =>
        if (target.guildId == "broken") throw new RuntimeException("channel is gone") else posts += target.guildId,
      recordPosted = (target, _) => marks += target.guildId,
      now = () => insideWindow
    )
    service.tick()
    posts shouldBe List("fine")
    marks should contain theSameElementsAs List("broken", "fine")
  }
}
