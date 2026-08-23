package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Behaviour of the character age cache: a sheet whose upstream copy cannot
 *  have turned over yet is replayed instead of re-fetched, a fetch that failed
 *  never counts as one that succeeded, and the canary keeps a sample of real
 *  fetches flowing so the measurement behind all of it stays honest. */
class AgeCachedTibiaApiSpec extends AnyFunSuite with Matchers with JsonSupport {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val base: CharacterResponse = {
    val is = getClass.getResourceAsStream("/tibiadata/character.json")
    require(is != null, "missing fixture /tibiadata/character.json")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString.parseJson.convertTo[CharacterResponse]
    finally is.close()
  }

  private val t0 = Instant.parse("2026-08-24T12:00:00Z")

  /** The fixture, restamped as though the origin generated it at `at`. */
  private def sheetFrom(at: Instant): CharacterResponse =
    base.copy(information = base.information.copy(timestamp = Some(at.toString)))

  private val poll = 60.seconds
  private val settings = AgeCacheSettings(
    ttl = 300.seconds, pollInterval = poll, maxStale = 15.minutes,
    canaryFraction = 0.0, maxEntries = 20000)

  /** Underlying API whose clock and answer the test drives. `calls` is what
   *  every assertion here is really about: whether a request was made. */
  private class StubApi(var result: Either[String, CharacterResponse]) extends TibiaApi {
    var calls = 0
    var lastName: String = ""
    var otherCalls = 0
    def getCharacter(name: String) = { calls += 1; lastName = name; Future.successful(result) }
    def getWorld(w: String) = { otherCalls += 1; Future.successful(Left("x")) }
    def getWorlds() = { otherCalls += 1; Future.successful(Left("x")) }
    def getBoostedBoss() = { otherCalls += 1; Future.successful(Left("x")) }
    def getBoostedCreature() = { otherCalls += 1; Future.successful(Left("x")) }
    def getGuild(guild: String) = { otherCalls += 1; Future.successful(Left("x")) }
    def getGuildWithInput(i: (String, String)) = { otherCalls += 1; Future.successful((Left("x"), i._1, i._2)) }
    def getKillerFallback(name: String) = { otherCalls += 1; Future.successful(Left("x")) }
    def getCharacterWithInput(i: (String, String, String)) = { otherCalls += 1; Future.successful((Left("x"), i._1, i._2, i._3)) }
  }

  /** A cache with a clock the test moves by hand. */
  private class Fixture(
      result: Either[String, CharacterResponse] = Right(sheetFrom(t0)),
      canaryFraction: Double = 0.0,
      randomValue: Double = 0.99
  ) {
    val stub = new StubApi(result)
    var clock: Instant = t0
    val api = new AgeCachedTibiaApi(
      stub, settings.copy(canaryFraction = canaryFraction), () => clock, () => randomValue)
    def advance(d: FiniteDuration): Unit = clock = clock.plusSeconds(d.toSeconds)
    def get(name: String = "Abu Shusha"): Either[String, CharacterResponse] = await(api.getCharacter(name))
  }

  test("the first fetch goes through and is remembered") {
    val f = new Fixture()
    f.get() shouldBe Right(sheetFrom(t0))
    f.stub.calls shouldBe 1
  }

  test("a sheet whose upstream copy cannot have turned over yet is replayed, not re-fetched") {
    val f = new Fixture()
    f.get()
    f.advance(60.seconds)
    f.get() shouldBe Right(sheetFrom(t0))
    f.advance(60.seconds)
    f.get() shouldBe Right(sheetFrom(t0))
    // four 60s ticks across a 300s upstream TTL, and only the first asked
    f.advance(60.seconds)
    f.get()
    f.stub.calls shouldBe 1
  }

  test("once the upstream copy is due to turn over, it asks again") {
    val f = new Fixture()
    f.get()
    f.advance(269.seconds) // still more than half a poll short of the turnover
    f.get()
    f.stub.calls shouldBe 1
    f.advance(2.seconds) // now within half a poll of it
    f.get()
    f.stub.calls shouldBe 2
  }

  test("the poll that lands on the turnover spends the fetch, not the one after it") {
    // The trap this guards: fetches only happen on the poll tick and the TTL is
    // an exact multiple of it, so a copy created by one poll turns over exactly
    // on a later one. The origin stamp always trails the poll that caused it by
    // the request latency, so asking "has it expired yet" answers no by a
    // fraction of a second — and waiting for the next poll costs a full
    // interval of death-detection latency.
    val requestLatency = 1
    val f = new Fixture(result = Right(sheetFrom(t0.plusSeconds(requestLatency))))
    f.get()
    f.stub.calls shouldBe 1
    List(60, 120, 180, 240).foreach { _ =>
      f.advance(poll)
      f.get()
      f.stub.calls shouldBe 1 // nothing spent on the four polls in between
    }
    f.advance(poll) // the poll on which the copy turns over
    f.get()
    f.stub.calls shouldBe 2
  }

  test("alignment holds despite a slow request, tick drift or a rounded timestamp") {
    // Anything up to half a poll of skew still lands on the same poll, in
    // either direction — far more slack than those three combined need.
    List(-29, -5, 0, 5, 29).foreach { skew =>
      val f = new Fixture(result = Right(sheetFrom(t0.plusSeconds(skew.toLong))))
      f.get()
      (1 to 4).foreach { _ => f.advance(poll); f.get() }
      withClue(s"skew ${skew}s should not have spent a fetch before the turnover poll: ") {
        f.stub.calls shouldBe 1
      }
      f.advance(poll)
      f.get()
      withClue(s"skew ${skew}s should have fetched on the turnover poll: ") {
        f.stub.calls shouldBe 2
      }
    }
  }

  test("a fetch that lands early gets the same copy back and leaves the entry due") {
    // Rounding to the nearest poll can ask slightly early. That returns the
    // copy already held, so the entry keeps its origin, stays due, and is asked
    // for again — one wasted request, never a stuck entry.
    val f = new Fixture()
    f.get()
    f.advance(271.seconds) // within half a poll of turnover, so due
    f.get()
    f.stub.calls shouldBe 2
    f.advance(1.second)
    f.get()
    f.stub.calls shouldBe 3 // still due, because the copy never turned over
  }

  test("age is counted from when the origin built the copy, not from when we stored it") {
    // A copy already 250s old when first seen has 50s of life left, not 300.
    val f = new Fixture(result = Right(sheetFrom(t0.minusSeconds(250))))
    f.get()
    f.advance(15.seconds)
    f.get()
    f.stub.calls shouldBe 1
    f.advance(10.seconds)
    f.get()
    f.stub.calls shouldBe 2
  }

  test("a failed fetch does not count as a fresh one — the character stays due and is retried next tick") {
    val f = new Fixture(result = Left("503"))
    f.get() shouldBe Left("503")
    f.advance(60.seconds)
    f.get() shouldBe Left("503")
    f.advance(60.seconds)
    f.get() shouldBe Left("503")
    // every tick retried, exactly as it would without this cache in the way
    f.stub.calls shouldBe 3
  }

  test("a failure after a good fetch is answered from the stored sheet rather than as a hole") {
    val f = new Fixture()
    f.get() shouldBe Right(sheetFrom(t0))
    f.advance(280.seconds) // due again
    f.stub.result = Left("503")
    f.get() shouldBe Right(sheetFrom(t0)) // the stored sheet, not the error
    f.stub.calls shouldBe 2
  }

  test("a failure keeps the character due, so it is retried every tick through an outage") {
    val f = new Fixture()
    f.get()
    f.advance(280.seconds)
    f.stub.result = Left("503")
    f.get(); f.advance(60.seconds)
    f.get(); f.advance(60.seconds)
    f.get()
    f.stub.calls shouldBe 4 // one good, then one attempt per tick
  }

  test("past max-stale the stored sheet stops covering failures and the error is passed through") {
    val f = new Fixture()
    f.get()
    f.stub.result = Left("503")
    f.advance(14.minutes)
    f.get() shouldBe Right(sheetFrom(t0)) // still inside 15m
    f.advance(2.minutes)
    f.get() shouldBe Left("503") // past it
  }

  test("a response with no origin timestamp is never cached, so nothing is served on a guess") {
    val noStamp = base.copy(information = base.information.copy(timestamp = None))
    val f = new Fixture(result = Right(noStamp))
    f.get(); f.advance(10.seconds); f.get(); f.advance(10.seconds); f.get()
    f.stub.calls shouldBe 3
  }

  test("an unparseable origin timestamp is treated the same as a missing one") {
    val bad = base.copy(information = base.information.copy(timestamp = Some("not a timestamp")))
    val f = new Fixture(result = Right(bad))
    f.get(); f.advance(10.seconds); f.get()
    f.stub.calls shouldBe 2
  }

  test("a timestamp from a skewed clock cannot postpone the next fetch beyond one TTL") {
    val f = new Fixture(result = Right(sheetFrom(t0.plusSeconds(86400))))
    f.get()
    f.advance(200.seconds)
    f.get()
    f.stub.calls shouldBe 1 // still inside the clamped window
    f.advance(200.seconds)
    f.get()
    f.stub.calls shouldBe 2 // clamped to one TTL from first sight, not a day
  }

  test("the canary fetches anyway, so the age histogram keeps an unbiased sample") {
    // randomValue below the fraction is what makes a call a canary
    val f = new Fixture(canaryFraction = 1.0, randomValue = 0.0)
    f.get(); f.advance(10.seconds); f.get(); f.advance(10.seconds); f.get()
    f.stub.calls shouldBe 3
  }

  test("with no canary configured, a reusable sheet is never re-fetched") {
    val f = new Fixture(canaryFraction = 0.0, randomValue = 0.0)
    f.get(); f.advance(10.seconds); f.get()
    f.stub.calls shouldBe 1
  }

  test("names are matched however they are capitalised, since the API is not case sensitive") {
    val f = new Fixture()
    f.get("Abu Shusha")
    f.advance(10.seconds)
    f.get("abu shusha")
    f.get("ABU SHUSHA")
    f.stub.calls shouldBe 1
  }

  test("the name is passed through to the underlying API exactly as given") {
    val f = new Fixture()
    f.get("Abu Shusha")
    f.stub.lastName shouldBe "Abu Shusha"
  }

  test("every other endpoint passes straight through") {
    val f = new Fixture()
    await(f.api.getWorld("Antica")) shouldBe Left("x")
    await(f.api.getWorlds()) shouldBe Left("x")
    await(f.api.getBoostedBoss()) shouldBe Left("x")
    await(f.api.getBoostedCreature()) shouldBe Left("x")
    await(f.api.getGuild("g")) shouldBe Left("x")
    await(f.api.getKillerFallback("k")) shouldBe Left("x")
    f.stub.otherCalls shouldBe 6
    f.stub.calls shouldBe 0
  }

  test("killer lookups are not answered from the character cache — they deliberately want a fresh read") {
    val f = new Fixture()
    f.get()
    await(f.api.getKillerFallback("Abu Shusha")) shouldBe Left("x")
    f.stub.otherCalls shouldBe 1
  }
}
