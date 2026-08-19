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

    /** Channels, with Redis's own two properties that matter here: `PUBLISH`
     *  answers how many subscribers it reached, and it answers *before* they
     *  have handled anything. */
    val listeners = TrieMap.empty[String, List[String => Unit]]
    override def publish(channel: String, message: String): Future[Long] = {
      val reached = listeners.getOrElse(channel, Nil)
      Future(reached.foreach(_(message)))
      Future.successful(reached.size.toLong)
    }
    override def subscribe(channel: String)(onMessage: String => Unit): Future[Unit] =
      Future.successful {
        listeners.put(channel, onMessage :: listeners.getOrElse(channel, Nil))
        ()
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

    "carry a return address on a query, and an id on the answer" in {
      // Both are what make one channel per bot workable instead of one key per
      // question: the question says where to reply, the reply says what it is
      // replying to.
      val q = AccessQuery("q1", "g1", "u1", replyTo = "bot-1")
      AccessQuery.fromJson(q.toJson) shouldBe Some(q)
      AccessQuery.answerFromJson(AccessAnswer(Some(access), "q1").toJson)
        .map(_.id) shouldBe Some("q1")
      AccessQuery.answerFromJson(AccessAnswer(None, "q1").toJson) shouldBe Some(AccessAnswer(None, "q1"))
    }

    // A bot that predates the channel sends neither, and must still be
    // understood — that is the whole of the rolling-deploy story.
    "read a query and an answer from a bot that knows nothing of channels" in {
      AccessQuery.fromJson("""{"id":"q1","guildId":"g1","userId":"u1"}""") shouldBe
        Some(AccessQuery("q1", "g1", "u1", replyTo = ""))
      AccessQuery.answerFromJson("""{"access":null}""") shouldBe Some(AccessAnswer(None, ""))
    }

    "say in the roster whether this bot is listening on its channel" in {
      val listening = GuildRoster("bot-1", List(RosterGuild("g1", "One", None)), pubSub = true)
      GuildRoster.fromJson(listening.toJson) shouldBe Some(listening)
      // An old bot's roster says nothing, which has to read as "not listening"
      // or its questions would be published into silence.
      GuildRoster.fromJson("""{"botId":"b","guilds":[]}""").map(_.pubSub) shouldBe Some(false)
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

      Await.result(answer, 5.seconds).granted shouldBe List(access)
    }

    // The transport this replaced needed the answering bot to sweep the whole
    // Redis keyspace four times a second to notice a question at all. Here
    // nothing sweeps: the question is addressed to the bot that runs the guild
    // and arrives the moment it is sent.
    "answer over a channel, with nothing sweeping at all" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None)),
                    pubSub = true).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis,
        resolve = (guildId, userId) => if (guildId == "g1" && userId == "u1") Some(access) else None,
        canSee = _ == "g1", selfBotId = "bot-2")
      Await.result(consumer.listen(), 2.seconds) shouldBe true

      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false, selfBotId = "bot-1")
      Await.result(asking.listen(), 2.seconds) shouldBe true

      Await.result(asking.accessFor("u1", Set("g1")), 5.seconds).granted shouldBe List(access)
      // Not one question was left lying in the keyspace for anybody to find.
      redis.store.keys.count(_.startsWith("tibia:access-q:")) shouldBe 0
    }

    // `PUBLISH` says how many listeners it reached, so a bot that is gone is
    // known in one round trip. A roster outlives the bot that published it by
    // a couple of minutes, so this is exactly the window every deploy opens —
    // and it used to cost the visitor the entire timeout.
    "give up at once when the bot that runs a guild is not listening" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Gone Away", None)),
                    pubSub = true).toJson, 1.minute), 2.seconds)
      // Nobody subscribed on bot-2's behalf: it has died since publishing.
      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false,
        selfBotId = "bot-1", timeout = 30.seconds)
      Await.result(asking.listen(), 2.seconds) shouldBe true

      val started = System.nanoTime()
      val report = Await.result(asking.accessFor("u1", Set("g1")), 5.seconds)
      report.granted shouldBe Nil
      report.unreachable.map(_.guildName) shouldBe List("Gone Away")
      // Well inside a timeout set deliberately far longer than this can take.
      (System.nanoTime() - started).nanos should be < 2.seconds
    }

    // Mid-deploy: one bot has the channel, another has not been restarted yet.
    // The roster is what tells them apart, so each is asked the way it can hear.
    "fall back to leaving a key for a bot that is not on a channel" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Older Bot", None)),
                    pubSub = false).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis,
        resolve = (_, _) => Some(access), canSee = _ == "g1")

      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false, selfBotId = "bot-1")
      Await.result(asking.listen(), 2.seconds) shouldBe true
      val answer = asking.accessFor("u1", Set("g1"))
      Await.result(akka.pattern.after(120.millis, scheduler)(consumer.sweep()), 3.seconds)

      Await.result(answer, 5.seconds).granted shouldBe List(access)
    }

    // One channel carries every answer a bot is waiting for, so two questions
    // in the air at once must not be able to take each other's answer.
    "match each answer to the question that asked it" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "First", None)), pubSub = true).toJson,
        1.minute), 2.seconds)
      Await.result(redis.setEx(GuildRoster.key("bot-3"),
        GuildRoster("bot-3", List(RosterGuild("g2", "Second", None)), pubSub = true).toJson,
        1.minute), 2.seconds)

      val inG1 = GuildAccess("g1", "First", AccessTier.Member, List("Antica"), None)
      val inG2 = GuildAccess("g2", "Second", AccessTier.Admin, List("Belobra"), None)
      List(("bot-2", "g1", inG1), ("bot-3", "g2", inG2)).foreach { case (bot, guild, granted) =>
        val consumer = new AccessQueryConsumer(redis,
          resolve = (g, _) => if (g == guild) Some(granted) else None,
          canSee = _ == guild, selfBotId = bot)
        Await.result(consumer.listen(), 2.seconds) shouldBe true
      }

      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false, selfBotId = "bot-1")
      Await.result(asking.listen(), 2.seconds) shouldBe true

      val granted = Await.result(asking.accessFor("u1", Set("g1", "g2")), 5.seconds).granted
      granted.sortBy(_.guildId) shouldBe List(inG1, inG2)
    }

    // The sweep exists only for bots too old to be listening on a channel, and
    // the rosters say whether any such bot is still running — so it can stand
    // itself down on the deploy that finishes the fleet, rather than waiting
    // for somebody to remember to delete it.
    "know when nobody in the fleet is leaving questions as keys any more" in {
      val redis = new FakeRedis
      // One reader per state of the fleet: rosters are deliberately re-read
      // only every RosterMaxAge, so a single instance would answer the second
      // question from the first one's memory.
      def asked() = Await.result(
        new RemoteGuildAccess(redis, scheduler, isLocal = _ => false, selfBotId = "bot-1")
          .fleetAllOnChannels, 3.seconds)

      // Nothing published yet: not knowing has to read as "somebody might be
      // old", or the sweep would stand down on a fleet it has not seen.
      asked() shouldBe false

      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "One", None)), pubSub = true).toJson,
        1.minute), 2.seconds)
      asked() shouldBe true

      // A bot that runs nothing still publishes a roster, which is what makes
      // this see the whole fleet rather than only the parts of it that own a
      // guild — an old bot with no guilds still asks questions. Its roster has
      // no `pubSub` at all, which is what marks it as old.
      Await.result(redis.setEx(GuildRoster.key("bot-3"),
        """{"botId":"bot-3","guilds":[]}""", 1.minute), 2.seconds)
      asked() shouldBe false
    }

    "never ask about a guild it is in itself" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Mine Too", None))).toJson, 1.minute), 2.seconds)
      // Everything is local here, so nothing should reach Redis as a question —
      // asking would invite a second, possibly different answer for a guild
      // this bot has already resolved for itself.
      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => true)
      Await.result(asking.accessFor("u1", Set("g1")), 3.seconds).granted shouldBe Nil
      redis.store.keys.count(_.startsWith("tibia:access-q:")) shouldBe 0
    }

    "ignore a guild nobody has published a roster for" in {
      val redis = new FakeRedis
      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false)
      // No roster names g9, so it is never asked about and costs no timeout.
      val started = System.nanoTime()
      Await.result(asking.accessFor("u1", Set("g9")), 2.seconds) shouldBe AccessReport.Empty
      (System.nanoTime() - started).nanos should be < 1.second
    }

    "show one server short rather than hanging when nobody answers" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Gone Quiet", None))).toJson, 1.minute), 2.seconds)
      // Roster says bot-2 runs g1, but nothing is sweeping to answer.
      val asking = new RemoteGuildAccess(redis, scheduler,
        isLocal = _ => false, timeout = 300.millis, pollEvery = 50.millis)
      val report = Await.result(asking.accessFor("u1", Set("g1")), 3.seconds)
      report.granted shouldBe Nil
      // Short, and saying so. Silently short is what made a two-server visitor
      // land on a one-server dashboard with no sign anything was missing — the
      // roster's copy of the name is what lets the page name it.
      report.unreachable shouldBe List(UnreachableGuild("g1", "Gone Quiet"))
      report.complete shouldBe false
    }

    // The standing memory covers a missed beat, so a guild that answered a
    // moment ago is not reported as missing — that is the difference between
    // "the fleet is busy" and "this server has gone".
    "not report a server as missing while it still has a remembered answer" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None))).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis, resolve = (_, _) => Some(access), canSee = _ == "g1")
      val asking = new RemoteGuildAccess(redis, scheduler,
        isLocal = _ => false, timeout = 300.millis, pollEvery = 50.millis)

      val first = asking.accessFor("u1", Set("g1"))
      Await.result(akka.pattern.after(80.millis, scheduler)(consumer.sweep()), 3.seconds)
      Await.result(first, 3.seconds).granted shouldBe List(access)

      val second = Await.result(asking.accessFor("u1", Set("g1")), 3.seconds)
      second.granted shouldBe List(access)
      second.unreachable shouldBe empty
    }

    "answer 'they may not' for somebody with no access there" in {
      val redis = new FakeRedis
      Await.result(redis.setEx(GuildRoster.key("bot-2"),
        GuildRoster("bot-2", List(RosterGuild("g1", "Their Server", None))).toJson, 1.minute), 2.seconds)
      val consumer = new AccessQueryConsumer(redis, resolve = (_, _) => None, canSee = _ == "g1")
      val asking = new RemoteGuildAccess(redis, scheduler, isLocal = _ => false)
      val answer = asking.accessFor("stranger", Set("g1"))
      Await.result(akka.pattern.after(120.millis, scheduler)(consumer.sweep()), 3.seconds)
      // Empty *and* complete: a refusal is an answer, so the guild is absent
      // rather than missing, and the page says nothing about it. Only silence
      // is worth reporting.
      Await.result(answer, 5.seconds) shouldBe AccessReport.Empty
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
      Await.result(answer, 2.seconds).granted shouldBe Nil
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
      Await.result(first, 3.seconds).granted shouldBe List(access)

      // Now nothing sweeps — a deploy, a busy beat, a page load that landed
      // between two of them. The visitor's standing there has not changed, so
      // dropping the server from their picker would be the wrong answer.
      Await.result(asking.accessFor("u1", Set("g1")), 3.seconds).granted shouldBe List(access)
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
      Await.result(first, 3.seconds).granted shouldBe List(access)

      allowed = false
      val second = asking.accessFor("u1", Set("g1"))
      Await.result(akka.pattern.after(80.millis, scheduler)(consumer.sweep()), 3.seconds)
      Await.result(second, 3.seconds).granted shouldBe Nil

      // And the refusal sticks: losing access must not be undone by the next
      // beat the other bot happens to miss.
      Await.result(asking.accessFor("u1", Set("g1")), 3.seconds).granted shouldBe Nil
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
      Await.result(first, 3.seconds).granted shouldBe List(access)

      // A bot that cannot say now whether somebody is still a moderator is a
      // no, however recently it said yes — this is the check that moves
      // somebody else off a spawn.
      Await.result(asking.accessFor("u1", Set("g1"), remembering = false), 3.seconds).granted shouldBe Nil
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
