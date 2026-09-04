package com.tibiabot.fansiteapi

import com.tibiabot.Config
import com.tibiabot.fansiteapi.response.FansiteCharacterResponse
import com.tibiabot.tibiadata.TibiaApi
import com.tibiabot.tibiadata.response._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

/** How the bot chooses between two upstreams telling it about the same
 *  character: the phase seeding that makes running both worth it, the failover
 *  that comes free with it, and the monotonicity guard that keeps an
 *  alternating pair from showing the stream a character's history in reverse. */
class DualCharacterApiSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll with FansiteJsonSupport {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val system = org.apache.pekko.actor.ActorSystem("dual-character-api-spec")
  override def afterAll(): Unit = { Await.result(system.terminate(), 10.seconds); () }

  private val payload: FansiteCharacterResponse = {
    val is = getClass.getResourceAsStream("/fansiteapi/character_full.json")
    require(is != null, "missing fixture")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString.parseJson.convertTo[FansiteCharacterResponse]
    finally is.close()
  }

  private val t0 = Instant.parse("2026-08-30T06:00:00Z")
  private val name = "Violent Beams"

  private def sheetFrom(at: Instant, level: Int = 1456): CharacterResponse = {
    val base = CharacterMapping.toCharacterResponse(payload, Some(at))
    base.copy(character = base.character.copy(character = base.character.character.copy(level = level.toDouble)))
  }

  private class StubApi(var result: Either[String, CharacterResponse]) extends TibiaApi {
    var calls = 0
    /** Set to make getCharacter hang, standing in for a fetch queued behind a
     *  low concurrency ceiling. */
    var pending: Option[Promise[Either[String, CharacterResponse]]] = None
    def getCharacter(n: String) = { calls += 1; pending.map(_.future).getOrElse(Future.successful(result)) }
    def getWorld(w: String) = Future.successful(Left("x"))
    def getWorlds() = Future.successful(Left("x"))
    def getBoostedBoss() = Future.successful(Left("x"))
    def getBoostedCreature() = Future.successful(Left("x"))
    def getGuild(g: String) = Future.successful(Left("x"))
    def getGuildWithInput(i: (String, String)) = Future.successful((Left("x"), i._1, i._2))
    def getKillerFallback(n: String) = { calls += 1; Future.successful(result) }
    def getCharacterWithInput(i: (String, String, String)) = Future.successful((Left("x"), i._1, i._2, i._3))
  }

  private class Fixture(mode: Config.FansiteApi.Mode, val offsetTicks: Int = 2) {
    val tibiaData = new StubApi(Right(sheetFrom(t0)))
    val fansite = new StubApi(Right(sheetFrom(t0)))
    var clock: Instant = t0
    val api = new DualCharacterApi(
      tibiaData, fansite, mode,
      phaseOffset = (60 * offsetTicks).seconds,
      maxStale = 15.minutes,
      secondaryGrace = 150.milliseconds,
      scheduler = system.scheduler,
      now = () => clock)
    def advance(d: FiniteDuration): Unit = clock = clock.plusSeconds(d.toSeconds)
    def get(): Either[String, CharacterResponse] = await(api.getCharacter(name))

    /** Put the character past its seeding window, so the second source is due.
     *  The hold-back is measured from when a character is first asked for, not
     *  from process start, so it takes a first call to begin. */
    def seed(): Unit = { get(); advance((60 * offsetTicks).seconds) }
    def levelOf(r: Either[String, CharacterResponse]): Int = r.map(_.character.character.level.toInt).getOrElse(-1)
  }

  test("the second source is held back until its window can open out of phase") {
    // The seeding. Asking both immediately would open both windows at the same
    // moment and leave them in lockstep for good, which is the one arrangement
    // that buys nothing at all.
    val f = new Fixture(Config.FansiteApi.Race)
    f.get()
    f.tibiaData.calls shouldBe 1
    f.fansite.calls shouldBe 0

    f.advance(60.seconds)
    f.get()
    f.fansite.calls shouldBe 0

    f.advance(60.seconds)
    f.get()
    f.fansite.calls shouldBe 1
  }

  test("race mode returns whichever source holds the newer copy") {
    val f = new Fixture(Config.FansiteApi.Race)
    f.seed()
    f.tibiaData.result = Right(sheetFrom(t0, level = 100))
    f.fansite.result = Right(sheetFrom(t0.plusSeconds(150), level = 200))
    f.levelOf(f.get()) shouldBe 200

    // ...and the other way round, so this is not just "the fansite always wins".
    f.tibiaData.result = Right(sheetFrom(t0.plusSeconds(400), level = 300))
    f.fansite.result = Right(sheetFrom(t0.plusSeconds(200), level = 200))
    f.levelOf(f.get()) shouldBe 300
  }

  test("a source that fails is covered by the other") {
    // The failover the single-upstream design never had, and it costs nothing
    // extra — both sources are already being asked.
    val f = new Fixture(Config.FansiteApi.Race)
    f.seed()

    f.tibiaData.result = Left("503 from TibiaData")
    f.fansite.result = Right(sheetFrom(t0.plusSeconds(120), level = 200))
    f.levelOf(f.get()) shouldBe 200

    f.tibiaData.result = Right(sheetFrom(t0.plusSeconds(300), level = 300))
    f.fansite.result = Left("fansite is down")
    f.levelOf(f.get()) shouldBe 300
  }

  test("both sources failing is still a failure") {
    val f = new Fixture(Config.FansiteApi.Race)
    f.seed()
    f.tibiaData.result = Left("503")
    f.fansite.result = Left("504")
    f.get().isLeft shouldBe true
  }

  test("a sheet older than the one already served is refused") {
    // Protects the rename and guild-change logic, which reads a sheet going
    // backwards as a real event and announces it. See the class doc.
    val f = new Fixture(Config.FansiteApi.Race)
    f.seed()

    f.tibiaData.result = Right(sheetFrom(t0.plusSeconds(300), level = 300))
    f.fansite.result = Left("no answer")
    f.levelOf(f.get()) shouldBe 300

    // Now both sources regress — a drifted phase, or a stale replay after a
    // failed fetch. The last good sheet is served instead of the older one.
    f.tibiaData.result = Right(sheetFrom(t0.plusSeconds(100), level = 100))
    f.fansite.result = Right(sheetFrom(t0.plusSeconds(120), level = 120))
    f.levelOf(f.get()) shouldBe 300
  }

  test("a newer sheet is still accepted after a refused one") {
    // The guard must not latch — it refuses individual regressions, it does not
    // freeze the character at its high-water mark.
    val f = new Fixture(Config.FansiteApi.Race)
    f.seed()
    f.tibiaData.result = Right(sheetFrom(t0.plusSeconds(300), level = 300))
    f.fansite.result = Left("no answer")
    f.get()

    f.tibiaData.result = Right(sheetFrom(t0.plusSeconds(100), level = 100))
    f.levelOf(f.get()) shouldBe 300

    f.tibiaData.result = Right(sheetFrom(t0.plusSeconds(600), level = 600))
    f.levelOf(f.get()) shouldBe 600
  }

  test("shadow mode never changes what the bot is told") {
    // The whole point of the mode: the fansite source is fetched and compared,
    // and TibiaData's answer is what comes out regardless of which is fresher.
    val f = new Fixture(Config.FansiteApi.Shadow)
    f.seed()
    f.tibiaData.result = Right(sheetFrom(t0, level = 100))
    f.fansite.result = Right(sheetFrom(t0.plusSeconds(300), level = 999))

    f.levelOf(f.get()) shouldBe 100
    f.fansite.calls shouldBe 1 // fetched, just not believed
  }

  test("shadow mode passes a TibiaData failure through untouched") {
    val f = new Fixture(Config.FansiteApi.Shadow)
    f.seed()
    f.tibiaData.result = Left("503")
    f.fansite.result = Right(sheetFrom(t0.plusSeconds(300)))
    f.get().isLeft shouldBe true
  }

  test("the killer-level lookup never reaches the fansite API") {
    // Nothing rations this path — no roster, no age cache, one request per
    // unknown killer per death batch — and it is the one character path that
    // skips the shared-cycle role check, so it would call out from secondaries
    // too. It stays on TibiaData in every mode.
    val f = new Fixture(Config.FansiteApi.Race)
    f.fansite.result = Right(sheetFrom(t0, level = 700))
    f.tibiaData.result = Right(sheetFrom(t0, level = 800))
    f.levelOf(await(f.api.getKillerFallback("Someone"))) shouldBe 800
    f.fansite.calls shouldBe 0
    f.tibiaData.calls shouldBe 1
  }

  test("a slow secondary never holds up the answer") {
    // The fix for the trap the concurrency cap creates: once the fansite source
    // is throttled hard enough not to get the IP blocked, a due fetch can sit
    // queued — and a death must not wait behind it.
    val f = new Fixture(Config.FansiteApi.Race)
    f.seed()
    f.tibiaData.result = Right(sheetFrom(t0.plusSeconds(300), level = 300))
    f.fansite.pending = Some(Promise[Either[String, CharacterResponse]]())

    val started = System.nanoTime()
    val got = f.get()
    val tookMs = (System.nanoTime() - started) / 1000000L

    f.levelOf(got) shouldBe 300
    tookMs should be < 3000L // bounded by the grace, not by the hung fetch
    f.fansite.calls shouldBe 1 // it was still asked; only the waiting was abandoned
  }

  test("a secondary that lands inside the grace is still used") {
    // The bound must not be so eager that it throws away the whole point.
    val f = new Fixture(Config.FansiteApi.Race)
    f.seed()
    f.tibiaData.result = Right(sheetFrom(t0, level = 100))
    f.fansite.result = Right(sheetFrom(t0.plusSeconds(300), level = 200))
    f.levelOf(f.get()) shouldBe 200
  }

  test("shadow mode does not wait on the secondary either") {
    // Shadow is meant to be observation with no cost to what gets posted, so a
    // hung comparison fetch must not delay the answer it is comparing against.
    val f = new Fixture(Config.FansiteApi.Shadow)
    f.seed()
    f.tibiaData.result = Right(sheetFrom(t0, level = 100))
    f.fansite.pending = Some(Promise[Either[String, CharacterResponse]]())

    val started = System.nanoTime()
    f.levelOf(f.get()) shouldBe 100
    ((System.nanoTime() - started) / 1000000L) should be < 3000L
  }

  test("endpoints this API does not have never reach the fansite source") {
    val f = new Fixture(Config.FansiteApi.Race)
    await(f.api.getWorld("Antica"))
    await(f.api.getWorlds())
    await(f.api.getGuild("Ruckus"))
    await(f.api.getBoostedBoss())
    await(f.api.getBoostedCreature())
    f.fansite.calls shouldBe 0
  }

  test("characters stop being tracked once nothing has asked about them") {
    val f = new Fixture(Config.FansiteApi.Race)
    f.get()
    f.api.trackedCharacters shouldBe 1
    // Past maxStale with no further interest, the phase/monotonicity state for
    // that character is dropped rather than held for the life of the process.
    f.advance(31.minutes)
    await(f.api.getCharacter("Somebody Else"))
    f.api.trackedCharacters shouldBe 1
  }
}
