package com.tibiabot.tibiadata

import com.tibiabot.persistence.{NoopRedisCache, RedisCache}
import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.{ZoneId, ZonedDateTime}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Behaviour of the CachingTibiaApi decorator: cached endpoints serve from Redis
 *  on a hit, fall back to the underlying API on miss/decode-error/cache-error,
 *  never cache a Left, leave the death-critical character endpoints uncached,
 *  and key the boosted endpoints by server-save day so a pre-save value is never
 *  served after the save. */
class CachingTibiaApiSpec extends AnyFunSuite with Matchers with JsonSupport {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private def fixture(name: String): String = {
    val is = getClass.getResourceAsStream(s"/tibiadata/$name")
    require(is != null, s"missing fixture /tibiadata/$name")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString finally is.close()
  }
  private val boosted: BoostedResponse = fixture("boostablebosses.json").parseJson.convertTo[BoostedResponse]
  private val creature: CreatureResponse = fixture("creatures.json").parseJson.convertTo[CreatureResponse]
  private val highscores: HighscoresResponse = fixture("highscores_antica.json").parseJson.convertTo[HighscoresResponse]

  // Fixed Berlin clock so the save-day-keyed boosted keys are deterministic.
  // 2026-05-31 12:00 Berlin - 10h = 02:00 same day => save day 2026-05-31.
  private def berlin(y: Int, mo: Int, d: Int, h: Int, mi: Int): ZonedDateTime =
    ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of("Europe/Berlin"))
  private val fixedNow: () => ZonedDateTime = () => berlin(2026, 5, 31, 12, 0)
  private val bossKey = "tibia:boostedboss:2026-05-31"
  private val creatureKey = "tibia:boostedcreature:2026-05-31"

  private def mkApi(stub: TibiaApi, cache: RedisCache,
                    boostedTtl: FiniteDuration = 30.minutes,
                    highscoresTtl: FiniteDuration = 30.minutes,
                    now: () => ZonedDateTime = fixedNow): CachingTibiaApi =
    new CachingTibiaApi(stub, cache, boostedTtl, highscoresTtl, now)

  /** In-memory RedisCache with call counters; preset seeds values, lastTtl records
   *  the TTL handed to setEx per key, failGet simulates a Redis error. */
  private class FakeCache(preset: Map[String, String] = Map.empty, failGet: Boolean = false) extends RedisCache {
    val store = TrieMap.empty[String, String] ++ preset
    var gets = 0
    var sets = 0
    var lastTtl = Map.empty[String, FiniteDuration]
    def get(key: String): Future[Option[String]] = {
      gets += 1
      if (failGet) Future.failed(new RuntimeException("redis down")) else Future.successful(store.get(key))
    }
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = {
      sets += 1; lastTtl += (key -> ttl); store.put(key, value); Future.unit
    }
    def close(): Unit = ()
  }

  /** Stub TibiaApi counting calls; highscores can vary its result per (world,page). */
  private class StubApi(
      boss: Either[String, BoostedResponse] = Right(boosted),
      creatureResp: Either[String, CreatureResponse] = Right(creature),
      hs: (String, Int) => Either[String, HighscoresResponse] = (_, _) => Right(highscores)
  ) extends TibiaApi {
    var bossCalls = 0
    var creatureCalls = 0
    var charCalls = 0
    var hsCalls = 0
    def getBoostedBoss(): Future[Either[String, BoostedResponse]] = { bossCalls += 1; Future.successful(boss) }
    def getBoostedCreature(): Future[Either[String, CreatureResponse]] = { creatureCalls += 1; Future.successful(creatureResp) }
    def getHighscores(world: String, page: Int): Future[Either[String, HighscoresResponse]] = { hsCalls += 1; Future.successful(hs(world, page)) }
    def getCharacter(name: String): Future[Either[String, CharacterResponse]] = { charCalls += 1; Future.successful(Left("char")) }
    // unused in these tests
    def getWorld(world: String) = Future.successful(Left("x"))
    def getWorlds() = Future.successful(Left("x"))
    def getGuild(guild: String) = Future.successful(Left("x"))
    def getGuildWithInput(input: (String, String)) = Future.successful((Left("x"), input._1, input._2))
    def getKillerFallback(name: String) = Future.successful(Left("x"))
    def getCharacterV2(input: (String, Int)) = Future.successful(Left("x"))
    def getCharacterWithInput(input: (String, String, String)) = Future.successful((Left("x"), input._1, input._2, input._3))
  }

  // --- core hit/miss/fallback behaviour ---

  test("miss: calls underlying once and stores the result under the save-day key") {
    val stub = new StubApi(); val cache = new FakeCache()
    await(mkApi(stub, cache).getBoostedBoss()) shouldBe Right(boosted)
    stub.bossCalls shouldBe 1
    cache.sets shouldBe 1
    cache.store should contain key bossKey
  }

  test("hit: second call is served from cache without touching underlying") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = mkApi(stub, cache)
    await(api.getBoostedBoss())
    await(api.getBoostedBoss()) shouldBe Right(boosted)
    stub.bossCalls shouldBe 1 // not called the second time
  }

  test("corrupt cache value: falls back to underlying and re-stores") {
    val stub = new StubApi(); val cache = new FakeCache(preset = Map(bossKey -> "}{not json"))
    await(mkApi(stub, cache).getBoostedBoss()) shouldBe Right(boosted)
    stub.bossCalls shouldBe 1
    cache.store(bossKey) should not be "}{not json" // overwritten with valid json
  }

  test("cache get failure: degrades to underlying instead of failing") {
    val stub = new StubApi(); val cache = new FakeCache(failGet = true)
    await(mkApi(stub, cache).getBoostedBoss()) shouldBe Right(boosted)
    stub.bossCalls shouldBe 1
  }

  test("Left results are not cached") {
    val stub = new StubApi(boss = Left("api error")); val cache = new FakeCache()
    await(mkApi(stub, cache).getBoostedBoss()) shouldBe Left("api error")
    cache.sets shouldBe 0
    cache.store should not contain key(bossKey)
  }

  test("character endpoint is never cached (death-critical passthrough)") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = mkApi(stub, cache)
    await(api.getCharacter("Violent Beams"))
    await(api.getCharacter("Violent Beams"))
    stub.charCalls shouldBe 2 // every call hits underlying
    cache.gets shouldBe 0     // and the cache is never consulted
  }

  // --- server-save day keying (stale-after-save protection) ---

  test("server-save day roll: a pre-save cached boosted value is not served after the save") {
    val cache = new FakeCache()
    val preSaveStub = new StubApi()
    val postSaveStub = new StubApi()
    // 09:00 Berlin -10h => save day 2026-05-30 (before the 10:00 save)
    val preSave = mkApi(preSaveStub, cache, now = () => berlin(2026, 5, 31, 9, 0))
    // 10:01 Berlin -10h => save day 2026-05-31 (after the save)
    val postSave = mkApi(postSaveStub, cache, now = () => berlin(2026, 5, 31, 10, 1))

    await(preSave.getBoostedBoss())  // writes tibia:boostedboss:2026-05-30
    await(postSave.getBoostedBoss()) // different key => miss => underlying called again

    postSaveStub.bossCalls shouldBe 1
    cache.store.keySet should contain ("tibia:boostedboss:2026-05-30")
    cache.store.keySet should contain ("tibia:boostedboss:2026-05-31")
  }

  // --- round-trip serialization for the other cached types ---

  test("CreatureResponse round-trips losslessly through the cache serialization") {
    creature.toJson.compactPrint.parseJson.convertTo[CreatureResponse] shouldBe creature
  }

  test("HighscoresResponse round-trips losslessly through the cache serialization") {
    highscores.toJson.compactPrint.parseJson.convertTo[HighscoresResponse] shouldBe highscores
  }

  test("HighscoresResponse with highscore_list = None survives the round-trip") {
    val none = highscores.copy(highscores = highscores.highscores.copy(highscore_list = None))
    none.toJson.compactPrint.parseJson.convertTo[HighscoresResponse] shouldBe none
  }

  test("boosted creature is served from cache on the second call") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = mkApi(stub, cache)
    await(api.getBoostedCreature()) shouldBe Right(creature)
    await(api.getBoostedCreature()) shouldBe Right(creature)
    stub.creatureCalls shouldBe 1
    cache.store should contain key creatureKey
  }

  // --- highscores composite key: distinctness + case-insensitive collapse ---

  test("highscores keys distinct (world,page) tuples") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = mkApi(stub, cache)
    await(api.getHighscores("Antica", 1))
    await(api.getHighscores("Antica", 2))
    stub.hsCalls shouldBe 2
    cache.store.keySet should contain ("tibia:highscores:antica:1")
    cache.store.keySet should contain ("tibia:highscores:antica:2")
  }

  test("highscores key collapses case: 'Antica' and 'antica' share one entry") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = mkApi(stub, cache)
    await(api.getHighscores("Antica", 1))
    await(api.getHighscores("antica", 1)) // cache hit
    stub.hsCalls shouldBe 1
    cache.store.keySet should contain ("tibia:highscores:antica:1")
    cache.store.keySet should not contain "tibia:highscores:Antica:1"
  }

  test("highscores Left is not cached") {
    val stub = new StubApi(hs = (_, _) => Left("api error")); val cache = new FakeCache()
    await(mkApi(stub, cache).getHighscores("Antica", 1)) shouldBe Left("api error")
    cache.store.keySet.exists(_.startsWith("tibia:highscores:")) shouldBe false
  }

  // --- per-endpoint TTLs are threaded correctly (not crossed/hardcoded) ---

  test("each endpoint stores under its own configured TTL") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = mkApi(stub, cache, boostedTtl = 7.minutes, highscoresTtl = 11.minutes)
    await(api.getBoostedBoss())
    await(api.getBoostedCreature())
    await(api.getHighscores("Antica", 1))
    cache.lastTtl(bossKey) shouldBe 7.minutes
    cache.lastTtl(creatureKey) shouldBe 7.minutes
    cache.lastTtl("tibia:highscores:antica:1") shouldBe 11.minutes
  }

  // --- disabled Redis (NoopRedisCache) is a pure pass-through ---

  test("disabled Redis (Noop): every call falls through, nothing is memoized") {
    val stub = new StubApi()
    val api = mkApi(stub, NoopRedisCache)
    await(api.getBoostedBoss()) shouldBe Right(boosted)
    await(api.getBoostedBoss()) shouldBe Right(boosted)
    stub.bossCalls shouldBe 2 // Noop get always misses, so underlying is hit every time
  }
}
