package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response.CharacterResponse
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Pins which character fetch the command path gets.
 *
 *  `getCharacter` is the poll's and skips the inline retry on purpose — it asks
 *  again a minute later. A command has no next attempt, and roughly 45% of this
 *  API's responses are 503s, so a command sent down that method reports real
 *  characters as "does not exist" about as often as not. That is what this
 *  guards, and it fails silently in production if a decorator stops forwarding.
 */
class CharacterOnDemandSpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  /** Records which of the two fetches was asked for. */
  private class Recorder extends TibiaApi {
    var polled = 0
    var onDemand = 0
    def getWorld(w: String) = Future.successful(Left("x"))
    def getWorlds() = Future.successful(Left("x"))
    def getBoostedBoss() = Future.successful(Left("x"))
    def getBoostedCreature() = Future.successful(Left("x"))
    def getGuild(guild: String) = Future.successful(Left("x"))
    def getGuildWithInput(input: (String, String)) = Future.successful((Left("x"), input._1, input._2))
    def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
      polled += 1; Future.successful(Left("polled"))
    }
    override def getCharacterOnDemand(name: String): Future[Either[String, CharacterResponse]] = {
      onDemand += 1; Future.successful(Left("on-demand"))
    }
    def getKillerFallback(name: String) = Future.successful(Left("x"))
    def getCharacterWithInput(input: (String, String, String)) =
      Future.successful((Left("x"), input._1, input._2, input._3))
  }

  /** An implementor that has not overridden it — every decorator not in a command
   *  path, and every test stub. */
  private class DefaultsOnly extends TibiaApi {
    var polled = 0
    def getWorld(w: String) = Future.successful(Left("x"))
    def getWorlds() = Future.successful(Left("x"))
    def getBoostedBoss() = Future.successful(Left("x"))
    def getBoostedCreature() = Future.successful(Left("x"))
    def getGuild(guild: String) = Future.successful(Left("x"))
    def getGuildWithInput(input: (String, String)) = Future.successful((Left("x"), input._1, input._2))
    def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
      polled += 1; Future.successful(Left("polled"))
    }
    def getKillerFallback(name: String) = Future.successful(Left("x"))
    def getCharacterWithInput(input: (String, String, String)) =
      Future.successful((Left("x"), input._1, input._2, input._3))
  }

  test("the two fetches are distinct — asking for one never runs the other") {
    val api = new Recorder
    await(api.getCharacterOnDemand("Bubble")) shouldBe Left("on-demand")
    api.onDemand shouldBe 1
    api.polled shouldBe 0
  }

  test("an implementor that does not override it falls back to the poll's fetch") {
    val api = new DefaultsOnly
    await(api.getCharacterOnDemand("Bubble")) shouldBe Left("polled")
    api.polled shouldBe 1
  }

  /** CachingTibiaApi sits between the command path and the real client, so if it
   *  inherited the default instead of forwarding, every `/hunted` lookup would
   *  quietly drop back to the non-retrying fetch. */
  test("the caching layer forwards on-demand rather than inheriting the default") {
    val underlying = new Recorder
    val caching = new CachingTibiaApi(underlying, null)
    await(caching.getCharacterOnDemand("Bubble")) shouldBe Left("on-demand")
    underlying.onDemand shouldBe 1
    underlying.polled shouldBe 0
  }

  test("the caching layer still sends the poll's fetch down the poll's path") {
    val underlying = new Recorder
    val caching = new CachingTibiaApi(underlying, null)
    await(caching.getCharacter("Bubble")) shouldBe Left("polled")
    underlying.polled shouldBe 1
    underlying.onDemand shouldBe 0
  }

  /** The whole point: a 503 is transient and must be retried for a caller that
   *  will never ask again. */
  test("503 is retryable, and a fetch-once caller gets the retry") {
    val policy = new RetryPolicy(jitterMs = _ => 0)
    policy.retryableStatusCodes should contain(503)
    policy.onResponse(503, None, attempt = 0, callerRetriesSoon = false) shouldBe
      a[RetryDecision.RetryIn]
    policy.onResponse(503, None, attempt = 0, callerRetriesSoon = true) shouldBe
      RetryDecision.GiveUp
  }
}
