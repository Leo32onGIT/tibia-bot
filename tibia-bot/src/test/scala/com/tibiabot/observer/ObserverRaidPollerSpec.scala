package com.tibiabot.observer

import com.tibiabot.domain.RaidAnnouncement
import com.tibiabot.persistence.ObserverRaidRepository
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** A raid through its three stages, as an account with limited discoveries sees
 *  it: the area and the start an hour ahead, the subarea 15 minutes ahead, and
 *  which raid it is only once it has started. The posts are stand-ins that say
 *  what they are, so these run without Config. */
class ObserverRaidPollerSpec extends AnyFunSuite with Matchers {

  /** Winter Wolves near Krimhorn, Hrodmir: two lines, at the start and 220s in. */
  private val WinterWolves = 289

  private val t0 = Instant.parse("2026-09-24T12:00:00Z")
  private val start = t0.plus(Duration.ofMinutes(60))

  private def entry(category: String, subarea: Option[String] = None, typeId: Int = 0,
                    startDate: Option[Instant] = Some(start)) =
    RaidAnnouncement("r1", "Antica", "Hrodmir", subarea, category, startDate, typeId)

  private class Harness {
    var clock: Instant = t0
    var feed: List[RaidAnnouncement] = Nil
    /** How many times the feed was asked for — each is a request to the API. */
    var fetches = 0
    val posts = mutable.ListBuffer.empty[(String, String)]
    val scheduled = mutable.ListBuffer.empty[(FiniteDuration, () => Unit)]
    private val posted = mutable.Set.empty[(String, String, String)]

    private val repo = new ObserverRaidRepository {
      def setChannel(guildId: String, world: String, channelId: String): Unit = ()
      def clearChannel(guildId: String, world: String): Unit = ()
      def clearGuild(guildId: String): Unit = ()
      def channelFor(guildId: String, world: String): Option[String] = None
      // g2 is another bot's server: it must never be posted to, or marked.
      def channelsForWorld(world: String): List[(String, String)] = List("g1" -> "c1", "g2" -> "c2")
      def markPostedIfNew(guildId: String, raidId: String, category: String): Boolean =
        posted.add((guildId, raidId, category))
      def prunePostedOlderThan(cutoff: Instant): Unit = ()
    }

    private def said(text: String): MessageEmbed = new EmbedBuilder().setDescription(text).build()

    val poller = new ObserverRaidPoller(
      pooledRaids = () => { fetches += 1; feed.groupBy(_.world) },
      raidRepository = repo,
      post = (guildId, _, embed) => posts += (guildId -> embed.getDescription),
      schedule = (delay, task) => scheduled += (delay -> task),
      servesGuild = _ == "g1",
      areaPost = _ => said("area"),
      subareaPost = _ => said("subarea"),
      startedPost = (_, raidType) => said(s"started:${raidType.map(_.name).getOrElse("?")}"),
      linePost = message => said(s"line:$message"),
      now = () => clock)

    def pollAt(minutesAfterT0: Long, entries: RaidAnnouncement*): Unit = {
      clock = t0.plus(Duration.ofMinutes(minutesAfterT0))
      feed = entries.toList
      poller.poll()
    }

    def markedFor(guild: String): Set[String] = posted.collect { case (`guild`, _, key) => key }.toSet
  }

  test("the area stage posts the imminent-raid post once, to this bot's own servers") {
    val h = new Harness
    h.pollAt(0, entry("areaRevealed"))
    h.pollAt(5, entry("areaRevealed"))
    h.posts.toList shouldBe List("g1" -> "area")
    h.markedFor("g2") shouldBe empty
  }

  test("one-off polls are scheduled from two seconds after the subarea reveals, 15 minutes before the start") {
    val h = new Harness
    h.pollAt(0, entry("areaRevealed"))
    h.scheduled.map(_._1.toSeconds).toList shouldBe List(2, 5, 10, 20, 120).map(45 * 60 + _)
  }

  test("once the stage has been seen, the later one-off polls don't ask the feed again") {
    val h = new Harness
    h.pollAt(0, entry("areaRevealed"))
    val wakes = h.scheduled.map(_._2).toList
    h.clock = t0.plus(Duration.ofMinutes(45)).plusSeconds(2)
    h.feed = List(entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")))
    val before = h.fetches
    wakes.head()                       // the first wake finds the subarea
    wakes.tail.foreach(_())            // the rest see it was found and skip
    h.fetches shouldBe before + 1
    h.posts.toList shouldBe List("g1" -> "area", "g1" -> "subarea")
  }

  test("a start wake keeps looking until the feed says which raid it is") {
    val h = new Harness
    h.pollAt(0, entry("areaRevealed"))
    h.pollAt(45, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")))
    val startWakes = h.scheduled.filter(_._1.toSeconds < 20 * 60).map(_._2).toList
    h.clock = start.plusSeconds(2)
    // Started, but not yet identified: the next wake must still look.
    h.feed = List(entry("subareaRevealed", Some("Krimhorn")), entry("raidStarted", Some("Krimhorn")))
    val before = h.fetches
    startWakes(0)()
    h.feed = List(entry("subareaRevealed", Some("Krimhorn")), entry("raidStarted", Some("Krimhorn"), WinterWolves))
    startWakes(1)()
    startWakes.drop(2).foreach(_())
    h.fetches shouldBe before + 2
  }

  test("the subarea stage is a new post, and the area post never follows it") {
    val h = new Harness
    h.pollAt(0, entry("areaRevealed"))
    h.pollAt(30, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")))
    h.pollAt(31, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")))
    h.posts.toList shouldBe List("g1" -> "area", "g1" -> "subarea")
  }

  test("the lines are timed from the start once the raid is revealed there, though the start was known an hour before") {
    val h = new Harness
    h.pollAt(0, entry("areaRevealed"))
    h.pollAt(30, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")))
    h.scheduled.clear()
    // Revealed at the start: the lines are scheduled now, the first due at once.
    h.pollAt(60, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")),
      entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.scheduled.map(_._1.toMillis).toList shouldBe List(0L, 220000L)
    h.scheduled.foreach(_._2())
    h.posts.map(_._2).filter(_.startsWith("line:")) should have size 2
    // A later poll neither reschedules nor reposts them.
    h.scheduled.clear()
    h.pollAt(62, entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.scheduled shouldBe empty
  }

  test("a raid first seen already started gets only the start post, then its lines at once and on time") {
    val h = new Harness
    h.pollAt(61, entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.posts.toList shouldBe List("g1" -> "started:Winter Wolves near Krimhorn")
    h.scheduled.map(_._1.toMillis).toList shouldBe List(0L, 220000L - 60000L)
  }

  test("every raid gets its three posts in order, the start post ahead of its first line") {
    val h = new Harness
    h.pollAt(0, entry("areaRevealed"))
    h.pollAt(30, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")))
    h.scheduled.clear()
    h.pollAt(60, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")),
      entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.scheduled.foreach(_._2())
    // A later poll posts nothing again.
    h.pollAt(62, entry("raidStarted", Some("Krimhorn"), WinterWolves))
    val said = h.posts.map(_._2).toList
    said.take(3) shouldBe List("area", "subarea", "started:Winter Wolves near Krimhorn")
    said.drop(3) should have size 2
    all(said.drop(3)) should startWith("line:")
    h.markedFor("g2") shouldBe empty
  }

  test("a raids channel created before a raid starts gets its start post along with its lines") {
    val h = new Harness
    h.feed = List(entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")))
    h.poller.seedPosted("g1", "Antica")
    h.pollAt(60, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")),
      entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.scheduled.foreach(_._2())
    val said = h.posts.map(_._2).toList
    said.headOption shouldBe Some("started:Winter Wolves near Krimhorn")
    said.drop(1) should have size 2
    all(said.drop(1)) should startWith("line:")
  }

  test("a raids channel created once a raid has started posts nothing more of it") {
    val h = new Harness
    h.feed = List(entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.poller.seedPosted("g1", "Antica")
    h.pollAt(60, entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.scheduled.foreach(_._2())
    h.posts shouldBe empty
  }

  test("a raid still running when the bot starts catches up everything it missed, however late") {
    val h = new Harness
    // Both lines are past, but the raid is not over: nothing is left out.
    h.pollAt(70, entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.posts.toList shouldBe List("g1" -> "started:Winter Wolves near Krimhorn")
    h.scheduled.map(_._1.toMillis).toList shouldBe List(0L, 0L)
  }

  test("a raid already over when the bot starts is not posted at all") {
    val h = new Harness
    // Three hours after the start: long past its last line, but still in the feed.
    h.pollAt(240, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")),
      entry("raidStarted", Some("Krimhorn"), WinterWolves))
    h.posts shouldBe empty
    h.scheduled shouldBe empty
  }

  test("a raid first seen at its subarea stage gets only the subarea post") {
    val h = new Harness
    h.pollAt(50, entry("areaRevealed"), entry("subareaRevealed", Some("Krimhorn")))
    h.posts.toList shouldBe List("g1" -> "subarea")
    h.markedFor("g1") shouldBe Set("imminent", "subarea")
  }

  test("merge takes the furthest stage, and the start, subarea and raid from whichever entry has them") {
    val merged = ObserverRaidPoller.merge(List(
      entry("areaRevealed", startDate = None),
      entry("subareaRevealed", Some("Krimhorn")),
      entry("raidStarted", startDate = None, typeId = WinterWolves)))
    merged.category shouldBe "raidStarted"
    merged.subarea shouldBe Some("Krimhorn")
    merged.startDate shouldBe Some(start)
    merged.raidTypeId shouldBe WinterWolves
  }
}
