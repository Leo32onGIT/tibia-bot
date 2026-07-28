package com.tibiabot.tibiadata

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/** Pins when a failed TibiaData request is worth retrying. The upstream sits
 *  behind Cloudflare + Kong and the character poll is high volume, so a retry
 *  costs real quota — these rules are what stop a brief upstream blip becoming
 *  a self-inflicted one.
 */
class RetryPolicySpec extends AnyFunSuite with Matchers {

  // Deterministic jitter: none, or the maximum, to pin the spread's bounds.
  private def noJitter = new RetryPolicy(jitterMs = _ => 0)
  private def maxJitter = new RetryPolicy(jitterMs = bound => bound)

  test("a transient upstream failure is retried") {
    List(500, 502, 503, 504).foreach { status =>
      noJitter.onResponse(status, None, attempt = 0) shouldBe a[RetryDecision.RetryIn]
    }
  }

  test("429 is never retried, however early in the attempts") {
    noJitter.onResponse(429, None, attempt = 0) shouldBe RetryDecision.GiveUp
  }

  test("429 is not retried even when it asks for a short wait") {
    // Being told to send less is not an invitation to send again sooner.
    noJitter.onResponse(429, Some(1.second), attempt = 0) shouldBe RetryDecision.GiveUp
  }

  test("a real answer is never retried") {
    List(200, 400, 404).foreach { status =>
      noJitter.onResponse(status, None, attempt = 0) shouldBe RetryDecision.GiveUp
    }
  }

  test("retries stop once the attempt budget is spent") {
    noJitter.onResponse(503, None, attempt = 0) shouldBe a[RetryDecision.RetryIn]
    noJitter.onResponse(503, None, attempt = 1) shouldBe a[RetryDecision.RetryIn]
    noJitter.onResponse(503, None, attempt = 2) shouldBe RetryDecision.GiveUp
  }

  test("a short Retry-After is honoured exactly, in preference to our own backoff") {
    noJitter.onResponse(503, Some(1500.millis), attempt = 0) shouldBe RetryDecision.RetryIn(1500.millis)
  }

  test("a Retry-After longer than we will hold a request means give up now") {
    // Sleeping 30s inside the poll's bounded concurrency would stall the stream
    // far worse than missing this character until the next cycle.
    noJitter.onResponse(503, Some(30.seconds), attempt = 0) shouldBe RetryDecision.GiveUp
  }

  test("the boundary Retry-After is still honoured") {
    val policy = new RetryPolicy(maxHonouredRetryAfter = 2.seconds, jitterMs = _ => 0)
    policy.onResponse(503, Some(2.seconds), attempt = 0) shouldBe RetryDecision.RetryIn(2.seconds)
    policy.onResponse(503, Some(2001.millis), attempt = 0) shouldBe RetryDecision.GiveUp
  }

  test("backoff grows with each attempt") {
    noJitter.backoff(0) should be < noJitter.backoff(1)
  }

  test("backoff is jittered around its base, so streams do not retry in lockstep") {
    // base is 250ms for attempt 0: spread runs from half to one-and-a-half times.
    noJitter.backoff(0) shouldBe 125.millis
    maxJitter.backoff(0) shouldBe 375.millis
    noJitter.backoff(1) shouldBe 250.millis
    maxJitter.backoff(1) shouldBe 750.millis
  }

  test("jitter actually varies across calls with the real random source") {
    val policy = new RetryPolicy()
    val samples = (1 to 200).map(_ => policy.backoff(0).toMillis).toSet
    samples.size should be > 1
    all(samples) should (be >= 125L and be <= 375L)
  }

  test("a connection-level failure is retried until the budget is spent") {
    noJitter.onConnectionFailure(attempt = 0) shouldBe a[RetryDecision.RetryIn]
    noJitter.onConnectionFailure(attempt = 1) shouldBe a[RetryDecision.RetryIn]
    noJitter.onConnectionFailure(attempt = 2) shouldBe RetryDecision.GiveUp
  }

  test("only 429 is reported as rate limiting") {
    noJitter.isRateLimited(429) shouldBe true
    List(200, 500, 503).foreach(noJitter.isRateLimited(_) shouldBe false)
  }
}
