package com.tibiabot.highscores

import com.tibiabot.domain.{FiledEvent, HighscoreEvent, HighscoreRecord}
import com.tibiabot.persistence.HighscoreRepository
import com.tibiabot.tibiadata.HighscoreCategory
import com.tibiabot.tibiadata.response.HighscoreEntry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable

/** How each bot in the fleet picks up the advances the primary filed.
 *
 *  The rule this exists to hold: the sweep runs on one bot, but every bot is a
 *  different Discord user in its own guilds, so each has to post for itself. */
class HighscoreFeedSpec extends AnyFunSuite with Matchers {

  private val at = Instant.parse("2026-09-02T05:40:00Z")

  private def ev(world: String, category: String, name: String, score: Long) =
    HighscoreEvent(world, category, name.toLowerCase, name, "Elite Knight", 400, score - 1, score, at)

  /** In-memory events table with a cursor per bot. */
  private class StubRepo(seed: List[FiledEvent] = Nil) extends HighscoreRepository {
    val cursors = mutable.Map.empty[String, Long]
    var rows: List[FiledEvent] = seed
    def load(world: String, category: String): Map[String, HighscoreRecord] = Map.empty
    def upsertAll(world: String, category: String, entries: List[HighscoreEntry], snapshotAt: Instant): Unit = ()
    def recordEvents(events: List[HighscoreEvent]): Unit = ()
    def events(world: String, since: Instant): List[HighscoreEvent] = Nil
    def eventsAfter(afterId: Long, limit: Int): List[FiledEvent] = rows.filter(_.id > afterId).sortBy(_.id).take(limit)
    def maxEventId(): Long = if (rows.isEmpty) 0L else rows.map(_.id).max
    def feedCursor(botId: String): Option[Long] = cursors.get(botId)
    def setFeedCursor(botId: String, eventId: Long): Unit = cursors.update(botId, eventId)
    def removeStale(world: String, before: Instant): Unit = ()
    def removeExpiredEvents(before: Instant): Unit = ()
  }

  private def feed(repo: HighscoreRepository, serves: String => Boolean = _ => true,
                   sink: mutable.ListBuffer[(String, HighscoreCategory, List[HighscoreEvent])] = mutable.ListBuffer.empty,
                   botId: String = "blue", limit: Int = 500) =
    (new HighscoreFeed(repo, botId, serves, (w, c, e) => sink += ((w, c, e)), limit), sink)

  test("a bot with no cursor starts from the end and announces nothing") {
    // Otherwise a first run would empty a month of retained advances into every
    // Levels channel at once.
    val repo = new StubRepo(List(FiledEvent(1, ev("Antica", "swordfighting", "Bubble", 116))))
    val (f, sink) = feed(repo)
    f.tick()

    sink shouldBe empty
    repo.cursors("blue") shouldBe 1L
  }

  test("advances filed after the cursor are announced once, and only once") {
    val repo = new StubRepo()
    val (f, sink) = feed(repo)
    f.tick() // takes the starting mark

    repo.rows = List(FiledEvent(1, ev("Antica", "swordfighting", "Bubble", 116)))
    f.tick()
    sink.map(_._1).toList shouldBe List("Antica")

    // A second tick with nothing new must not repost it.
    f.tick()
    sink should have size 1
  }

  test("each bot keeps its own place, so one being down does not cost the other") {
    val repo = new StubRepo()
    val (blue, blueSink) = feed(repo, botId = "blue")
    val (red, redSink) = feed(repo, botId = "red")
    blue.tick()
    red.tick()

    repo.rows = List(FiledEvent(1, ev("Antica", "swordfighting", "Bubble", 116)))
    blue.tick()
    blueSink should have size 1
    redSink shouldBe empty

    // Red comes back later and still finds the advance waiting for it.
    red.tick()
    redSink should have size 1
  }

  test("a bot only posts for the worlds it serves") {
    // The whole point: the primary files advances for every world in the fleet,
    // and a secondary must take only the ones its own guilds track.
    val repo = new StubRepo()
    val (f, sink) = feed(repo, serves = _ == "Secura")
    f.tick()

    repo.rows = List(
      FiledEvent(1, ev("Antica", "swordfighting", "Bubble", 116)),
      FiledEvent(2, ev("Secura", "swordfighting", "Zmek", 118))
    )
    f.tick()

    sink.map(_._1).toList shouldBe List("Secura")
    // The cursor still clears Antica's row, which belongs to another bot —
    // otherwise it would be re-read on every tick forever.
    repo.cursors("blue") shouldBe 2L
  }

  test("advances are grouped per world and category, so a batch is one message") {
    val repo = new StubRepo()
    val (f, sink) = feed(repo)
    f.tick()

    repo.rows = List(
      FiledEvent(1, ev("Antica", "swordfighting", "Bubble", 116)),
      FiledEvent(2, ev("Antica", "swordfighting", "Zmek", 118)),
      FiledEvent(3, ev("Antica", "magiclevel", "Sedik", 108)),
      FiledEvent(4, ev("Secura", "swordfighting", "Nyge", 115))
    )
    f.tick()

    sink should have size 3
    val antica = sink.find(g => g._1 == "Antica" && g._2 == HighscoreCategory.SwordFighting)
    antica.map(_._3.map(_.displayName)) shouldBe Some(List("Bubble", "Zmek"))
    sink.map(_._2) should contain(HighscoreCategory.MagicLevel)
  }

  test("an experience row is never announced even if one were filed") {
    val repo = new StubRepo()
    val (f, sink) = feed(repo)
    f.tick()

    repo.rows = List(FiledEvent(1, ev("Antica", "experience", "Bubble", 999999L)))
    f.tick()

    sink shouldBe empty
    repo.cursors("blue") shouldBe 1L
  }

  test("a slug this build no longer knows is skipped rather than fatal") {
    val repo = new StubRepo()
    val (f, sink) = feed(repo)
    f.tick()

    repo.rows = List(
      FiledEvent(1, ev("Antica", "fishing", "Bubble", 60)),
      FiledEvent(2, ev("Antica", "swordfighting", "Zmek", 118))
    )
    f.tick()

    sink.map(_._2).toList shouldBe List(HighscoreCategory.SwordFighting)
    repo.cursors("blue") shouldBe 2L
  }

  test("a large backlog is drained a page at a time, in order") {
    val repo = new StubRepo()
    val (f, sink) = feed(repo, limit = 2)
    f.tick()

    repo.rows = (1 to 5).map(i => FiledEvent(i.toLong, ev("Antica", "swordfighting", s"Char$i", 100L + i))).toList
    f.tick()
    repo.cursors("blue") shouldBe 2L
    f.tick()
    repo.cursors("blue") shouldBe 4L
    f.tick()
    repo.cursors("blue") shouldBe 5L

    sink.flatMap(_._3).map(_.displayName).toList shouldBe List("Char1", "Char2", "Char3", "Char4", "Char5")
  }

  test("planning an empty read moves nothing") {
    HighscoreFeed.plan(Nil, _ => true) shouldBe FeedBatch(Nil, 0L)
  }
}
