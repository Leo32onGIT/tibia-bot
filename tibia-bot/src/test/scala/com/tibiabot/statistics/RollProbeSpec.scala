package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.tibiadata.KillStatisticsApi
import com.tibiabot.tibiadata.response._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import java.time.{LocalTime, ZonedDateTime}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The measurement of when tibia.com's nightly batch runs.
 *
 *  Worth testing despite being temporary: it asks the live endpoint once a
 *  minute in the small hours, and the ways it could go wrong are all quiet ones
 *  — polling outside its window, polling on after it has its answer, or calling
 *  the first read of the morning a change. */
class RollProbeSpec extends AnyFunSuite with Matchers with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(15, Millis))

  private def berlin(text: String) = ZonedDateTime.parse(text).withZoneSameInstant(Clock.Berlin)

  private def response(total: Int) = KillStatisticsResponse(
    KillStatisticsData(
      world = "Antica",
      entries = List(KillStatisticsEntry("dragon", 4, 900, 20, 6000)),
      total = KillStatisticsTotal(818, total, 5520, 21487813)),
    Information(Api(4, "4.10.0", "abc"), Some("2026-09-11T02:00:00Z"), Status(200)))

  private class StubApi(var total: Int) extends KillStatisticsApi {
    val calls = mutable.ListBuffer.empty[String]
    var fail = false
    def getKillStatistics(world: String): Future[Either[String, KillStatisticsResponse]] = {
      calls += world
      if (fail) Future.successful(Left("503 from upstream"))
      else Future.successful(Right(response(total)))
    }
  }

  private class Harness(world: Option[String] = Some("Antica")) {
    val api = new StubApi(2400000)
    @volatile var clock: ZonedDateTime = berlin("2026-09-11T02:45:00+02:00")
    val probe = new RollProbe(
      api = api,
      world = () => world,
      from = LocalTime.of(2, 40),
      to = LocalTime.of(5, 0),
      now = () => clock)

    def at(time: String): Unit = clock = berlin(s"2026-09-11T$time:00+02:00")
    def tick(): Unit = probe.tick().futureValue
  }

  test("it only looks inside its window") {
    val harness = new Harness()
    harness.at("02:39")
    harness.tick()
    harness.at("05:00")
    harness.tick()
    harness.at("22:00")
    harness.tick()
    harness.api.calls shouldBe empty
  }

  test("the first look of the morning is a baseline, not a reading") {
    // With nothing to compare against, a change cannot be seen — and calling the
    // first look a change would report the batch every single morning at
    // whatever minute the probe happened to start.
    val harness = new Harness()
    harness.at("02:45")
    harness.tick()
    harness.api.calls should have size 1
    // Still watching, because nothing has been reported yet.
    harness.at("02:46")
    harness.tick()
    harness.api.calls should have size 2
  }

  test("it keeps looking while the figures hold still") {
    val harness = new Harness()
    List("02:45", "02:50", "03:00", "03:05").foreach { time =>
      harness.at(time)
      harness.tick()
    }
    harness.api.calls should have size 4
  }

  test("it stops for the day once the figures change") {
    val harness = new Harness()
    harness.at("02:45")
    harness.tick()
    harness.api.total = 2500000
    harness.at("03:12")
    harness.tick()
    val seen = harness.api.calls.size
    // The answer is in the log; asking again would be one request a minute for
    // the rest of the window to learn nothing.
    List("03:13", "03:30", "04:30").foreach { time =>
      harness.at(time)
      harness.tick()
    }
    harness.api.calls should have size seen
  }

  test("a failed read costs a look, not the morning") {
    val harness = new Harness()
    harness.at("02:45")
    harness.tick()
    harness.api.fail = true
    harness.at("03:00")
    harness.tick()
    harness.api.fail = false
    harness.api.total = 2500000
    harness.at("03:12")
    harness.tick()
    // Three looks, and the change was still caught after the failure.
    harness.api.calls should have size 3
    harness.at("03:13")
    harness.tick()
    harness.api.calls should have size 3
  }

  test("a bot tracking no worlds yet asks nothing") {
    val harness = new Harness(world = None)
    harness.at("03:00")
    harness.tick()
    harness.api.calls shouldBe empty
  }

  test("the window it watches covers the batch it is looking for") {
    RollProbe.From.isBefore(com.tibiabot.scheduler.KillStatisticsSchedule.publishedFrom) shouldBe true
    RollProbe.To.isAfter(com.tibiabot.scheduler.KillStatisticsSchedule.publishedBy) shouldBe true
  }
}
