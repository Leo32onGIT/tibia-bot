package com.tibiabot.fansiteapi

import com.tibiabot.fansiteapi.response.FansiteCharacterResponse
import com.tibiabot.tibiadata.response._
import com.tibiabot.tibiadata.{AgeCacheSettings, AgeCachedTibiaApi, TibiaApi}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Threads a mapped fansite sheet through the production age cache.
 *
 *  The whole adapter design rests on one claim: that `Last-Modified` carries
 *  the same meaning as TibiaData's `information.timestamp`, so
 *  [[AgeCachedTibiaApi]] can schedule this source with no changes. The mapping
 *  suite proves the header reaches the field; this proves the field actually
 *  drives the scheduler, which is the part that would silently cost death
 *  latency if it were wrong.
 *
 *  The timings here are the real ones — a 300s upstream window against a 60s
 *  poll, both measured against the live API. */
class FansiteAgeCacheSpec extends AnyFunSuite with Matchers with FansiteJsonSupport {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val payload: FansiteCharacterResponse = {
    val is = getClass.getResourceAsStream("/fansiteapi/character_full.json")
    require(is != null, "missing fixture /fansiteapi/character_full.json")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString.parseJson.convertTo[FansiteCharacterResponse]
    finally is.close()
  }

  private val t0 = Instant.parse("2026-08-30T06:00:00Z")

  /** The fixture as it would arrive with a `Last-Modified` of `at`. */
  private def sheetFrom(at: Instant): CharacterResponse =
    CharacterMapping.toCharacterResponse(payload, Some(at))

  private val settings = AgeCacheSettings(
    ttl = 300.seconds, pollInterval = 60.seconds, maxStale = 15.minutes,
    canaryFraction = 0.0, maxEntries = 20000)

  private class StubApi(var result: Either[String, CharacterResponse]) extends TibiaApi {
    var calls = 0
    def getCharacter(name: String) = { calls += 1; Future.successful(result) }
    def getWorld(w: String) = Future.successful(Left("x"))
    def getWorlds() = Future.successful(Left("x"))
    def getBoostedBoss() = Future.successful(Left("x"))
    def getBoostedCreature() = Future.successful(Left("x"))
    def getGuild(guild: String) = Future.successful(Left("x"))
    def getGuildWithInput(i: (String, String)) = Future.successful((Left("x"), i._1, i._2))
    def getKillerFallback(name: String) = Future.successful(Left("x"))
    def getCharacterWithInput(i: (String, String, String)) = Future.successful((Left("x"), i._1, i._2, i._3))
  }

  private class Fixture {
    val stub = new StubApi(Right(sheetFrom(t0)))
    var clock: Instant = t0
    val api = new AgeCachedTibiaApi(stub, settings, () => clock, () => 0.99)
    def advance(d: FiniteDuration): Unit = clock = clock.plusSeconds(d.toSeconds)
    def get(): Either[String, CharacterResponse] = await(api.getCharacter("Violent Beams"))
  }

  test("a mapped sheet is cached on the Last-Modified it carried") {
    val f = new Fixture()
    f.get()
    f.stub.calls shouldBe 1

    // Four polls inside the upstream window cannot learn anything new, so none
    // of them should reach the API — the same 4-in-5 saving the TibiaData path
    // already gets.
    (1 to 4).foreach { _ => f.advance(60.seconds); f.get() }
    f.stub.calls shouldBe 1
  }

  test("the fetch lands on the poll nearest the upstream turnover") {
    val f = new Fixture()
    f.get()
    f.advance(300.seconds)
    f.get()
    f.stub.calls shouldBe 2
  }

  test("a skipped poll replays the sheet rather than answering with an error") {
    // Load-bearing: the death scan drops a Left for that character on that
    // tick, so a skip that answered with one would silently stop level-ups,
    // guild activity and transfers four polls in five.
    val f = new Fixture()
    val first = f.get()
    f.advance(60.seconds)
    f.get() shouldBe first
    f.get().isRight shouldBe true
  }

  test("a sheet with no Last-Modified is never cached, so the character stays due") {
    val f = new Fixture()
    f.stub.result = Right(CharacterMapping.toCharacterResponse(payload, None))
    f.get()
    f.advance(60.seconds)
    f.get()
    // Unknown freshness must degrade to fetching every poll, not to reusing a
    // sheet whose age nothing can vouch for.
    f.stub.calls shouldBe 2
  }

  /** A source whose window opens at the moment it is asked, which is how both
   *  upstreams actually behave: the cached copy is (re)built lazily by the
   *  first request after expiry, not on a fixed server-side schedule. That is
   *  precisely why the phase is ours to choose rather than ours to discover. */
  private class LazyStub(clock: () => Instant) extends TibiaApi {
    var calls = 0
    def getCharacter(name: String) = { calls += 1; Future.successful(Right(sheetFrom(clock()))) }
    def getWorld(w: String) = Future.successful(Left("x"))
    def getWorlds() = Future.successful(Left("x"))
    def getBoostedBoss() = Future.successful(Left("x"))
    def getBoostedCreature() = Future.successful(Left("x"))
    def getGuild(guild: String) = Future.successful(Left("x"))
    def getGuildWithInput(i: (String, String)) = Future.successful((Left("x"), i._1, i._2))
    def getKillerFallback(name: String) = Future.successful(Left("x"))
    def getCharacterWithInput(i: (String, String, String)) = Future.successful((Left("x"), i._1, i._2, i._3))
  }

  test("two sources seeded two ticks apart stay out of phase, halving the wait for a new copy") {
    // The claim the dual-fetch design is bought with. Both sources share one
    // real clock — offsetting their *phase*, not their notion of time — and the
    // fansite window is opened two polls after TibiaData's. From then on each
    // self-schedules on its own 300s rhythm and the offset persists.
    var clock = t0
    val tibiaDataStub = new LazyStub(() => clock)
    val fansiteStub = new LazyStub(() => clock)
    val tibiaData = new AgeCachedTibiaApi(tibiaDataStub, settings, () => clock, () => 0.99)
    val fansite = new AgeCachedTibiaApi(fansiteStub, settings, () => clock, () => 0.99)
    def poll(api: AgeCachedTibiaApi): Unit = await(api.getCharacter("Violent Beams")): Unit

    poll(tibiaData)                      // TibiaData's window opens at t0
    clock = clock.plusSeconds(120)
    poll(fansite)                        // the fansite window opens two ticks later

    // Walk one shared clock forward a poll at a time, recording the moment each
    // source actually spends a request — i.e. when a new copy becomes visible.
    val refreshedAt = List.newBuilder[Instant]
    (1 to 12).foreach { _ =>
      clock = clock.plusSeconds(60)
      val beforeTibiaData = tibiaDataStub.calls
      val beforeFansite = fansiteStub.calls
      poll(tibiaData); poll(fansite)
      if (tibiaDataStub.calls > beforeTibiaData || fansiteStub.calls > beforeFansite) refreshedAt += clock
    }

    val moments = refreshedAt.result()
    val gaps = moments.zip(moments.tail).map { case (a, b) => b.getEpochSecond - a.getEpochSecond }
    gaps should not be empty

    // With one source a new copy appears every 300s. Holding the two apart
    // means never waiting more than 180s for one, which is where the roughly
    // halved detection lag comes from.
    all(gaps) should be <= 180L
    // And both sources are genuinely carrying the load, rather than one having
    // quietly collapsed onto the other's schedule.
    tibiaDataStub.calls should be > 1
    fansiteStub.calls should be > 1
  }
}
