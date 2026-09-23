package com.tibiabot.observer

import com.tibiabot.persistence.RedisCache
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** A secondary handing link and clear-rules requests to the primary over Redis. */
class ObserverRelaySpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  /** Redis's two properties that matter here: `PUBLISH` answers how many
   *  subscribers it reached, before they have handled anything. */
  private final class FakeRedis extends RedisCache {
    val store = TrieMap.empty[String, String]
    def get(key: String): Future[Option[String]] = Future.successful(store.get(key))
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] =
      Future.successful { store.put(key, value); () }
    def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] =
      Future.successful(store.putIfAbsent(key, value).isEmpty)
    def delete(key: String): Future[Unit] = Future.successful { store.remove(key); () }
    def keysMatching(pattern: String): Future[List[String]] = Future.successful(Nil)
    val listeners = TrieMap.empty[String, List[String => Unit]]
    override def publish(channel: String, message: String): Future[Long] = {
      val reached = listeners.getOrElse(channel, Nil)
      Future(reached.foreach(_(message)))
      Future.successful(reached.size.toLong)
    }
    override def subscribe(channel: String)(onMessage: String => Unit): Future[Unit] =
      Future.successful { listeners.put(channel, onMessage :: listeners.getOrElse(channel, Nil)); () }
    def close(): Unit = ()
  }

  private def relay(redis: FakeRedis, timeout: FiniteDuration = 2.seconds) =
    new ObserverRelay(redis, linkTimeout = timeout, clearTimeout = timeout, pollEvery = 10.millis)

  private def serving(redis: FakeRedis)(handle: ObserverRelay.Request => ObserverRelay.Reply): Unit =
    Await.result(relay(redis).serve(handle), 1.second)

  test("a link reaches the primary, token and all, and its answer comes back") {
    val redis = new FakeRedis
    val seen = ListBuffer.empty[ObserverRelay.Request]
    serving(redis) { request => seen.synchronized(seen += request); ObserverRelay.Linked }
    relay(redis).link("g1", "u1", "ABCDE") shouldBe ObserverRelay.Linked
    seen.map(r => (r.op, r.guildId, r.userId, r.token)) shouldBe
      List((ObserverRelay.OpLink, "g1", "u1", Some("ABCDE")))
  }

  test("a rejected token is reported as rejected, not as a failure") {
    val redis = new FakeRedis
    serving(redis)(_ => ObserverRelay.InvalidToken)
    relay(redis).link("g1", "u1", "WRONG") shouldBe ObserverRelay.InvalidToken
  }

  test("clearing rules carries no token") {
    val redis = new FakeRedis
    val seen = ListBuffer.empty[ObserverRelay.Request]
    serving(redis) { request => seen.synchronized(seen += request); ObserverRelay.Done }
    relay(redis).clearRules("g1", "u1") shouldBe ObserverRelay.Done
    seen.map(r => (r.op, r.token)) shouldBe List((ObserverRelay.OpClearRules, None))
  }

  test("nobody listening fails at once rather than waiting out the timeout") {
    val started = System.nanoTime()
    relay(new FakeRedis, timeout = 10.seconds).link("g1", "u1", "ABCDE") shouldBe a[ObserverRelay.Failed]
    (System.nanoTime() - started).nanos should be < 2.seconds
  }

  test("a primary that never answers times out") {
    val redis = new FakeRedis
    Await.result(redis.subscribe(ObserverRelay.Channel)(_ => ()), 1.second)
    relay(redis, timeout = 200.millis).link("g1", "u1", "ABCDE") shouldBe a[ObserverRelay.Failed]
  }

  test("a request that throws on the primary still gets an answer, with the reason") {
    val redis = new FakeRedis
    serving(redis)(_ => throw new RuntimeException("sidecar down"))
    relay(redis).link("g1", "u1", "ABCDE") shouldBe ObserverRelay.Failed("sidecar down")
  }

  test("an answer is collected once and then removed") {
    val redis = new FakeRedis
    serving(redis)(_ => ObserverRelay.Linked)
    relay(redis).link("g1", "u1", "ABCDE")
    redis.store.keys.filter(_.startsWith("tibia:observer:reply:")) shouldBe empty
  }

  test("the wire format round-trips") {
    val request = ObserverRelay.Request("id-1", ObserverRelay.OpLink, "g1", "u1", Some("ABCDE"))
    ObserverRelay.decodeRequest(ObserverRelay.encode(request)) shouldBe Some(request)
    ObserverRelay.decodeRequest(ObserverRelay.encode(request.copy(token = None))) shouldBe Some(request.copy(token = None))
    List(ObserverRelay.Linked, ObserverRelay.InvalidToken, ObserverRelay.Done, ObserverRelay.Failed("why"))
      .foreach(reply => ObserverRelay.decodeReply(ObserverRelay.encode(reply)) shouldBe Some(reply))
    ObserverRelay.decodeRequest("not json") shouldBe None
  }
}
