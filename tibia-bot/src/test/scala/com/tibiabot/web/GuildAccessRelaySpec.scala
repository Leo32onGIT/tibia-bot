package com.tibiabot.web

import com.tibiabot.persistence.RedisCache
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** The relay that lets one bot's dashboard show a guild another bot runs.
 *
 *  Everything here is the wire format and the two halves talking to each other
 *  over a Redis that is a map — no JDA, no network, no scheduler beyond akka's.
 */
class GuildAccessRelaySpec extends AnyWordSpec with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  /** Enough Redis for this: keys, values, and a pattern match over them. TTLs
   *  are ignored, which is what makes the reply-expiry case untestable here and
   *  the timeout case testable at all. */
  private final class FakeRedis extends RedisCache {
    val store = TrieMap.empty[String, String]
    def get(key: String): Future[Option[String]] = Future.successful(store.get(key))
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] =
      Future.successful { store.put(key, value); () }
    def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] =
      Future.successful(store.putIfAbsent(key, value).isEmpty)
    def delete(key: String): Future[Unit] = Future.successful { store.remove(key); () }
    def keysMatching(pattern: String): Future[List[String]] = {
      val re = ("^" + java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q") + "$").r
      Future.successful(store.keys.filter(k => re.findFirstIn(k).isDefined).toList)
    }
    def close(): Unit = ()
  }

  private val access = GuildAccess("g1", "Their Server", AccessTier.Moderator, List("Antica"), Some("icon.png"))

  /** Akka's own reference config and nothing else. The default would load
   *  discord.conf, which substitutes environment variables this has no business
   *  needing — the same reason Config cannot initialise in a test. */
  private lazy val system = akka.actor.ActorSystem(
    "relay-spec", com.typesafe.config.ConfigFactory.defaultReference())
  private def scheduler = system.scheduler

  "the wire format" should {

    "carry a resolved visitor there and back" in {
      val there = AccessAnswer(Some(access)).toJson
      val back = AccessQuery.answerFromJson(there).flatMap(_.access)
      back shouldBe Some(access)
    }

    "distinguish 'they may not' from an answer that could not be read" in {
      // A real no, which the asker should believe and stop asking about.
      AccessQuery.answerFromJson(AccessAnswer(None).toJson) shouldBe Some(AccessAnswer(None))
      // Not an answer at all.
      AccessQuery.answerFromJson("{oh dear") shouldBe None
    }

    "refuse a tier it has never heard of rather than guessing at one" in {
      val forged = """{"access":{"guildId":"g1","guildName":"x","tier":"owner","worlds":[]}}"""
      AccessQuery.answerFromJson(forged).flatMap(_.access) shouldBe None
    }

    "round-trip a query, and refuse a malformed one" in {
      val q = AccessQuery("q1", "g1", "u1")
      AccessQuery.fromJson(q.toJson) shouldBe Some(q)
      AccessQuery.fromJson("""{"id":"","guildId":"g1","userId":"u1"}""") shouldBe None
      AccessQuery.fromJson("not json") shouldBe None
    }

    "put the guild in the key, so a bot can tell what is its own to answer" in {
      val key = AccessQuery.requestKey("g1", "q1")
      AccessQuery.parseRequestKey(key) shouldBe Some("g1" -> "q1")
      // A shared Redis holds other things, and none of them are ours.
      AccessQuery.parseRequestKey("tibia:respawn-cmd:g1:c1") shouldBe None
      AccessQuery.parseRequestKey("something:else") shouldBe None
    }

    "round-trip a roster, dropping entries with no guild id" in {
      val roster = GuildRoster("bot-1", List(RosterGuild("g1", "One", Some("i.png")), RosterGuild("g2", "Two", None)))
      GuildRoster.fromJson(roster.toJson) shouldBe Some(roster)
      GuildRoster.fromJson("""{"botId":"b","guilds":[{"name":"no id"}]}""")
        .map(_.guilds) shouldBe Some(Nil)
    }
  }

  "the two halves together" should {

    "let a bot show a guild only another bot can resolve" in {
      val redis = new FakeRedis
      // The bot that runs g1 publishes it, and answers questions about it.
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None))).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis,
        resolve = (guildId, userId) => if (guildId == "g1" && userId == "u1") Some(access) else None,
        canSee = _ == "g1")

      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false)
      val answer = asking.accessFor("u1", Set("g1"))
      // The consumer sweeps on its own beat in production; here it is stepped.
      Await.result(akka.pattern.after(120.millis, scheduler)(consumer.sweep()), 3.seconds)

      Await.result(answer, 5.seconds) shouldBe List(access)
    }

    "never ask about a guild it is in itself" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Mine Too", None))).toJson, 1.minute), 2.seconds)
      // Everything is local here, so nothing should reach Redis as a question —
      // asking would invite a second, possibly different answer for a guild
      // this bot has already resolved for itself.
      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => true)
      Await.result(asking.accessFor("u1", Set("g1")), 3.seconds) shouldBe Nil
      redis.store.keys.count(_.startsWith("tibia:access-q:")) shouldBe 0
    }

    "ignore a guild nobody has published a roster for" in {
      val redis = new FakeRedis
      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false)
      // No roster names g9, so it is never asked about and costs no timeout.
      val started = System.nanoTime()
      Await.result(asking.accessFor("u1", Set("g9")), 2.seconds) shouldBe Nil
      (System.nanoTime() - started).nanos should be < 1.second
    }

    "show one server short rather than hanging when nobody answers" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Gone Quiet", None))).toJson, 1.minute), 2.seconds)
      // Roster says bot-2 runs g1, but nothing is sweeping to answer.
      val asking = new RemoteGuildAccess(redis, scheduler,
        isLocal = _ => false, timeout = 300.millis, pollEvery = 50.millis)
      Await.result(asking.accessFor("u1", Set("g1")), 3.seconds) shouldBe Nil
    }

    "answer 'they may not' for somebody with no access there" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None))).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis, resolve = (_, _) => None, canSee = _ == "g1")
      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false)
      val answer = asking.accessFor("stranger", Set("g1"))
      Await.result(akka.pattern.after(120.millis, scheduler)(consumer.sweep()), 3.seconds)
      Await.result(answer, 5.seconds) shouldBe Nil
    }

    "answer rather than stay silent when resolving throws" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None))).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis,
        resolve = (_, _) => throw new RuntimeException("JDA fell over"), canSee = _ == "g1")
      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false)
      val answer = asking.accessFor("u1", Set("g1"))
      Await.result(akka.pattern.after(120.millis, scheduler)(consumer.sweep()), 3.seconds)
      // The asker gets a definite no instead of waiting out its timeout.
      Await.result(answer, 2.seconds) shouldBe Nil
      redis.store.keys.exists(_.startsWith("tibia:access-a:")) shouldBe true
    }

    "answer a question once, not on every beat until it expires" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(AccessQuery.requestKey("g1", "q1"),
        AccessQuery("q1", "g1", "u1").toJson, 1.minute), 2.seconds)
      var resolved = 0
      val consumer = new AccessQueryConsumer(redis,
        resolve = (_, _) => { resolved += 1; Some(access) }, canSee = _ == "g1")

      // The beat is shorter than the blocking Discord lookup an answer costs,
      // so a question left in place got resolved again on every sweep for as
      // long as its key lived — several REST calls for one page load.
      Await.result(consumer.sweep(), 3.seconds)
      Await.result(consumer.sweep(), 3.seconds)
      Await.result(consumer.sweep(), 3.seconds)

      resolved shouldBe 1
      redis.store.get(AccessQuery.requestKey("g1", "q1")) shouldBe None
      redis.store.get(AccessQuery.replyKey("q1")) should not be empty
    }

    "keep a server that answered a moment ago when the fleet misses a beat" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None))).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis, resolve = (_, _) => Some(access), canSee = _ == "g1")
      val asking = new RemoteGuildAccess(redis, scheduler,
        isLocal = _ => false, timeout = 300.millis, pollEvery = 50.millis)

      val first = asking.accessFor("u1", Set("g1"))
      Await.result(akka.pattern.after(80.millis, scheduler)(consumer.sweep()), 3.seconds)
      Await.result(first, 3.seconds) shouldBe List(access)

      // Now nothing sweeps — a deploy, a busy beat, a page load that landed
      // between two of them. The visitor's standing there has not changed, so
      // dropping the server from their picker would be the wrong answer.
      Await.result(asking.accessFor("u1", Set("g1")), 3.seconds) shouldBe List(access)
    }

    "believe a 'they may not' over anything it remembers" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None))).toJson, 1.minute), 2.seconds)
      var allowed = true
      val consumer = new AccessQueryConsumer(redis,
        resolve = (_, _) => if (allowed) Some(access) else None, canSee = _ == "g1")
      val asking = new RemoteGuildAccess(redis, scheduler,
        isLocal = _ => false, timeout = 300.millis, pollEvery = 50.millis)

      val first = asking.accessFor("u1", Set("g1"))
      Await.result(akka.pattern.after(80.millis, scheduler)(consumer.sweep()), 3.seconds)
      Await.result(first, 3.seconds) shouldBe List(access)

      allowed = false
      val second = asking.accessFor("u1", Set("g1"))
      Await.result(akka.pattern.after(80.millis, scheduler)(consumer.sweep()), 3.seconds)
      Await.result(second, 3.seconds) shouldBe Nil

      // And the refusal sticks: losing access must not be undone by the next
      // beat the other bot happens to miss.
      Await.result(asking.accessFor("u1", Set("g1")), 3.seconds) shouldBe Nil
    }

    "not stand on an old answer when the question grants a moderator action" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None))).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis, resolve = (_, _) => Some(access), canSee = _ == "g1")
      val asking = new RemoteGuildAccess(redis, scheduler,
        isLocal = _ => false, timeout = 300.millis, pollEvery = 50.millis)

      val first = asking.accessFor("u1", Set("g1"))
      Await.result(akka.pattern.after(80.millis, scheduler)(consumer.sweep()), 3.seconds)
      Await.result(first, 3.seconds) shouldBe List(access)

      // A bot that cannot say now whether somebody is still a moderator is a
      // no, however recently it said yes — this is the check that moves
      // somebody else off a spawn.
      Await.result(asking.accessFor("u1", Set("g1"), remembering = false), 3.seconds) shouldBe Nil
    }

    "leave alone a question about a guild it cannot see" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(AccessQuery.requestKey("g-other", "q1"),
        AccessQuery("q1", "g-other", "u1").toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis, resolve = (_, _) => Some(access), canSee = _ == "g1")
      Await.result(consumer.sweep(), 3.seconds)
      // Not ours to answer — the bot that can see it will.
      redis.store.get(AccessQuery.replyKey("q1")) shouldBe None
    }
  }
}
