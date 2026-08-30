package com.tibiabot.tibiadata

import com.tibiabot.Config
import com.tibiabot.persistence.RedisCache
import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Behaviour of the SharedWorldTibiaApi decorator: a Primary publishes a
 *  successful getWorld/getCharacter fetch to Redis (never a Left) and returns
 *  it unchanged; a Secondary reads that published value on a hit without
 *  touching the underlying API, and falls back to it on a miss or a corrupt
 *  value; Disabled and every other method are unaffected. */
class SharedWorldTibiaApiSpec extends AnyFunSuite with Matchers with JsonSupport {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private def fixture(name: String): String = {
    val is = getClass.getResourceAsStream(s"/tibiadata/$name")
    require(is != null, s"missing fixture /tibiadata/$name")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString finally is.close()
  }
  private val world: WorldResponse = fixture("world_antica.json").parseJson.convertTo[WorldResponse]
  private val character: CharacterResponse = fixture("character.json").parseJson.convertTo[CharacterResponse]
  private val sharedWorldKey = "tibia:world-shared:antica"
  private val sharedCharacterKey = "tibia:character-shared:abu shusha"

  private class FakeCache(preset: Map[String, String] = Map.empty) extends RedisCache {
    val store = TrieMap.empty[String, String] ++ preset
    var gets = 0
    var sets = 0
    def get(key: String): Future[Option[String]] = { gets += 1; Future.successful(store.get(key)) }
    var lastTtl: Map[String, FiniteDuration] = Map.empty
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = {
      sets += 1; store.put(key, value); lastTtl += (key -> ttl); Future.unit
    }
    /** Real enough to be useful: wins only when nothing holds the key, which
     *  is the property anything relying on this actually depends on. */
    def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] =
      synchronized {
        if (store.contains(key)) Future.successful(false)
        else { store(key) = value; Future.successful(true) }
      }
    def delete(key: String): Future[Unit] = Future.successful { store.remove(key); () }
    def keysMatching(pattern: String): Future[List[String]] = {
      val regex = ("^" + java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q") + "$").r
      Future.successful(store.keys.filter(k => regex.pattern.matcher(k).matches()).toList)
    }
    def close(): Unit = ()
  }

  private class StubApi(
      worldResult: Either[String, WorldResponse] = Right(world),
      characterResult: Either[String, CharacterResponse] = Right(character)
  ) extends TibiaApi {
    var worldCalls = 0
    var characterCalls = 0
    def getWorld(w: String): Future[Either[String, WorldResponse]] = { worldCalls += 1; Future.successful(worldResult) }
    def getWorlds() = Future.successful(Left("x"))
    def getBoostedBoss() = Future.successful(Left("x"))
    def getBoostedCreature() = Future.successful(Left("x"))
    def getGuild(guild: String) = Future.successful(Left("x"))
    def getGuildWithInput(input: (String, String)) = Future.successful((Left("x"), input._1, input._2))
    def getCharacter(name: String) = { characterCalls += 1; Future.successful(characterResult) }
    def getKillerFallback(name: String) = Future.successful(Left("x"))
    def getCharacterWithInput(input: (String, String, String)) = Future.successful((Left("x"), input._1, input._2, input._3))
  }

  test("primary: publishes a successful world fetch to Redis and returns it unchanged") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getWorld("Antica")) shouldBe Right(world)
    stub.worldCalls shouldBe 1
    cache.sets shouldBe 1
    cache.store(sharedWorldKey).parseJson.convertTo[WorldResponse] shouldBe world
  }

  test("primary: does not publish a world Left result") {
    val stub = new StubApi(worldResult = Left("api error")); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getWorld("Antica")) shouldBe Left("api error")
    cache.sets shouldBe 0
  }

  test("secondary: a world cache hit is served without calling the underlying API") {
    val stub = new StubApi()
    val cache = new FakeCache(preset = Map(sharedWorldKey -> world.toJson.compactPrint))
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Secondary)
    await(api.getWorld("Antica")) shouldBe Right(world)
    stub.worldCalls shouldBe 0
  }

  test("secondary: a world cache miss falls back to the underlying API") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Secondary)
    await(api.getWorld("Antica")) shouldBe Right(world)
    stub.worldCalls shouldBe 1
  }

  test("secondary: a corrupt world cached value falls back to the underlying API") {
    val stub = new StubApi()
    val cache = new FakeCache(preset = Map(sharedWorldKey -> "}{not json"))
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Secondary)
    await(api.getWorld("Antica")) shouldBe Right(world)
    stub.worldCalls shouldBe 1
  }

  test("disabled: getWorld is a pure pass-through, cache never consulted") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Disabled)
    await(api.getWorld("Antica")) shouldBe Right(world)
    stub.worldCalls shouldBe 1
    cache.gets shouldBe 0
    cache.sets shouldBe 0
  }

  test("primary: publishes a successful character fetch to Redis and returns it unchanged") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getCharacter("Abu Shusha")) shouldBe Right(character)
    stub.characterCalls shouldBe 1
    cache.sets shouldBe 1
    cache.store(sharedCharacterKey).parseJson.convertTo[CharacterResponse] shouldBe character
  }

  test("primary: does not publish a character Left result — an error is not data") {
    val stub = new StubApi(characterResult = Left("503 Service Unavailable")); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getCharacter("Abu Shusha")) shouldBe Left("503 Service Unavailable")
    cache.sets shouldBe 0
  }

  test("secondary: a character cache hit is served without calling the underlying API") {
    val stub = new StubApi()
    val cache = new FakeCache(preset = Map(sharedCharacterKey -> character.toJson.compactPrint))
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Secondary)
    await(api.getCharacter("Abu Shusha")) shouldBe Right(character)
    stub.characterCalls shouldBe 0
  }

  test("secondary: a character cache miss falls back to the underlying API") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Secondary)
    await(api.getCharacter("Abu Shusha")) shouldBe Right(character)
    stub.characterCalls shouldBe 1
  }

  test("secondary: a corrupt character cached value falls back to the underlying API") {
    val stub = new StubApi()
    val cache = new FakeCache(preset = Map(sharedCharacterKey -> "}{not json"))
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Secondary)
    await(api.getCharacter("Abu Shusha")) shouldBe Right(character)
    stub.characterCalls shouldBe 1
  }

  test("disabled: getCharacter is a pure pass-through, cache never consulted") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Disabled)
    await(api.getCharacter("Abu Shusha")) shouldBe Right(character)
    stub.characterCalls shouldBe 1
    cache.gets shouldBe 0
    cache.sets shouldBe 0
  }

  test("primary: a published sheet is kept exactly as long as the copy it came from stays current") {
    // Fixture origin is 2026-05-30T14:57:58Z; 200s into a 300s copy leaves 100s.
    val at = Instant.parse("2026-05-30T15:01:18Z")
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary,
      characterTtl = 300.seconds, now = () => at)
    await(api.getCharacter("Abu Shusha")) shouldBe Right(character)
    cache.lastTtl(sharedCharacterKey) shouldBe 100.seconds
  }

  test("primary: a sheet fetched at its turnover is shared for a full copy lifetime") {
    val at = Instant.parse("2026-05-30T14:57:58Z")
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary,
      characterTtl = 300.seconds, now = () => at)
    await(api.getCharacter("Abu Shusha"))
    cache.lastTtl(sharedCharacterKey) shouldBe 300.seconds
  }

  test("primary: a copy already past its turnover is published only for the floor, never a negative life") {
    val at = Instant.parse("2026-05-30T15:30:00Z") // long past the copy's 300s
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary,
      characterTtl = 300.seconds, now = () => at)
    await(api.getCharacter("Abu Shusha"))
    cache.lastTtl(sharedCharacterKey) shouldBe SharedWorldTibiaApi.MinCharacterPublishTtl
  }

  test("primary: a sheet with no readable origin has unknown freshness, so it gets the floor rather than a guess") {
    val noStamp = character.copy(information = character.information.copy(timestamp = None))
    val stub = new StubApi(characterResult = Right(noStamp)); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getCharacter("Abu Shusha"))
    cache.lastTtl(sharedCharacterKey) shouldBe SharedWorldTibiaApi.MinCharacterPublishTtl
  }

  test("primary: the world share keeps its flat TTL, since its copy and the poll are both 60s") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary, worldTtl = 90.seconds)
    await(api.getWorld("Antica"))
    cache.lastTtl(sharedWorldKey) shouldBe 90.seconds
  }

  test("each character source publishes under its own prefix, so a secondary can race them") {
    // With two upstreams running, one shared key would hold whichever source
    // wrote last — which is not the same as the freshest. Namespacing lets a
    // secondary read both and reach the primary's own answer without calling
    // either API.
    val stub = new StubApi(); val cache = new FakeCache()
    val fansite = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary,
      characterKeyPrefix = SharedWorldTibiaApi.FansiteCharacterKeyPrefix)
    await(fansite.getCharacter("Abu Shusha"))

    cache.store.keys.toList should contain("fansite:character-shared:abu shusha")
    cache.store.keys.toList should not contain sharedCharacterKey
  }

  test("a secondary reads the prefix its own source publishes to") {
    val published = character.toJson.compactPrint
    val cache = new FakeCache(Map("fansite:character-shared:abu shusha" -> published))
    val stub = new StubApi()
    val fansite = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Secondary,
      characterKeyPrefix = SharedWorldTibiaApi.FansiteCharacterKeyPrefix)

    await(fansite.getCharacter("Abu Shusha")) shouldBe Right(character)
    stub.characterCalls shouldBe 0
  }

  test("the default prefix is unchanged, so an existing shared cycle keeps working") {
    // A deploy that upgrades one bot before the other must not have the two
    // silently reading different keys.
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getCharacter("Abu Shusha"))
    cache.store.keys.toList should contain(sharedCharacterKey)
  }

  test("every other method passes straight through to the underlying API regardless of role") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getBoostedBoss()) shouldBe Left("x")
    await(api.getKillerFallback("Violent Beams")) shouldBe Left("x")
    cache.gets shouldBe 0
    cache.sets shouldBe 0
  }
}
