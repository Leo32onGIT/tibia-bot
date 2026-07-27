package com.tibiabot.tibiadata

import com.tibiabot.Config
import com.tibiabot.persistence.RedisCache
import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Behaviour of the SharedWorldTibiaApi decorator: a Primary publishes a
 *  successful getWorld/getCharacter fetch to Redis (never a Left) and returns
 *  it unchanged; a Secondary reads that published value on a hit without
 *  touching the underlying API, and falls back to it on a miss or a corrupt
 *  value; Disabled and every other method (including getCharacterV2, which
 *  must stay a pure pass-through — see the class doc) are unaffected. */
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
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = { sets += 1; store.put(key, value); Future.unit }
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
    var characterV2Calls = 0
    def getWorld(w: String): Future[Either[String, WorldResponse]] = { worldCalls += 1; Future.successful(worldResult) }
    def getWorlds() = Future.successful(Left("x"))
    def getBoostedBoss() = Future.successful(Left("x"))
    def getBoostedCreature() = Future.successful(Left("x"))
    def getHighscores(w: String, page: Int) = Future.successful(Left("x"))
    def getGuild(guild: String) = Future.successful(Left("x"))
    def getGuildWithInput(input: (String, String)) = Future.successful((Left("x"), input._1, input._2))
    def getCharacter(name: String) = { characterCalls += 1; Future.successful(characterResult) }
    def getKillerFallback(name: String) = Future.successful(Left("x"))
    def getCharacterV2(input: (String, Int)) = { characterV2Calls += 1; Future.successful(Left("x")) }
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

  test("primary: does not publish a character Left result, including a local 'Hit cache' signal") {
    val stub = new StubApi(characterResult = Left("Hit cache")); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getCharacter("Abu Shusha")) shouldBe Left("Hit cache")
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

  test("getCharacterV2 stays a pure pass-through even as primary — sharing would defeat its Noctera cache-bypass purpose") {
    val stub = new StubApi(); val cache = new FakeCache()
    val api = new SharedWorldTibiaApi(stub, cache, Config.BotRole.Primary)
    await(api.getCharacterV2(("Abu Shusha", 1000))) shouldBe Left("x")
    stub.characterV2Calls shouldBe 1
    cache.gets shouldBe 0
    cache.sets shouldBe 0
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
