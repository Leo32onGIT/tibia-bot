package com.tibiabot.web

import com.tibiabot.discord.{DiscordGateway, MemberAccess}
import net.dv8tion.jda.api.entities.{Guild, User}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}

/** The short memory that keeps an open board from asking Discord six times a
 *  minute whether you are still in the server. */
class AccessCacheSpec extends AnyWordSpec with Matchers {

  private val start = Instant.parse("2026-08-08T12:00:00Z")

  /** A clock the test moves by hand, so nothing here waits on real seconds. */
  private class Clock(var at: Instant = start) extends (() => Instant) {
    def apply(): Instant = at
    def advance(seconds: Long): Unit = at = at.plusSeconds(seconds)
  }

  private val access = AccessReport.of(
    List(GuildAccess("g1", "Violent", AccessTier.Member, List("Antica"))))

  "the cache" should {

    "answer from memory inside the window" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45), now = clock)
      cache.put("u1", access)
      clock.advance(44)
      cache.get("u1").map(_.report) shouldBe Some(access)
    }

    "forget once the window has passed" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45), now = clock)
      cache.put("u1", access)
      clock.advance(46)
      cache.get("u1") shouldBe None
    }

    // An expired entry that is never asked for again would otherwise sit there
    // for the life of the process.
    "drop an expired entry rather than keep it around" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45), now = clock)
      cache.put("u1", access)
      clock.advance(46)
      cache.get("u1")
      cache.size shouldBe 0
    }

    "remember nothing about somebody it was never told about" in {
      new AccessCache(Duration.ofSeconds(45)).get("nobody") shouldBe None
    }

    // Empty is a real answer — "you have access to nothing" — and caching it is
    // the case worth caching most, since it is what a stranger's every request
    // would otherwise cost.
    "remember an empty answer as an answer" in {
      val cache = new AccessCache(Duration.ofSeconds(45))
      cache.put("u1", AccessReport.Empty)
      cache.get("u1").map(_.report) shouldBe Some(AccessReport.Empty)
    }

    // Caching only the granted half would hand the next reader a list that
    // looks complete and is not — which is the whole confusion the report was
    // introduced to remove, reappearing one layer down.
    "remember that a pass could not reach a server" in {
      val cache = new AccessCache(Duration.ofSeconds(45))
      val partial = AccessReport(access.granted, List(UnreachableGuild("g2", "Elsewhere")))
      cache.put("u1", partial)
      cache.get("u1").map(_.report.unreachable) shouldBe Some(List(UnreachableGuild("g2", "Elsewhere")))
      cache.get("u1").map(_.report.complete) shouldBe Some(false)
    }

    // A pass that could not reach a server is a report about one bad moment,
    // not a fact about the visitor — so it must not be handed back for the full
    // window. Held that long, one missed round trip made the picker say a
    // server was missing on every reload for the next three quarters of a
    // minute, well after the bot in question had started answering again.
    "let go of an incomplete pass quickly" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45),
                                  partialTtl = Duration.ofSeconds(5), now = clock)
      val partial = AccessReport(access.granted, List(UnreachableGuild("g2", "Elsewhere")))
      cache.put("u1", partial)
      cache.get("u1").map(_.report) shouldBe Some(partial)
      clock.advance(6)
      cache.get("u1") shouldBe None
    }

    // The same rule, for the failure that has nothing to show for itself. A
    // pass that could not read the rosters grants nothing and names nothing, so
    // by shape alone it is identical to a visitor with no servers elsewhere —
    // and it took the full window on that resemblance. One Redis blip then
    // spent ten minutes telling somebody their other servers did not exist.
    "let go of a pass that never learned what the fleet runs" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45),
                                  partialTtl = Duration.ofSeconds(5), now = clock)
      cache.put("u1", AccessReport.FleetUnknown)
      cache.get("u1").map(_.report) shouldBe Some(AccessReport.FleetUnknown)
      clock.advance(6)
      cache.get("u1") shouldBe None
    }

    // The other half of the same rule: a complete answer is the thing this
    // exists to keep, and must not have been shortened along with the failures.
    "keep a complete pass for the full window" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45),
                                  partialTtl = Duration.ofSeconds(5), now = clock)
      cache.put("u1", access)
      clock.advance(6)
      cache.get("u1").map(_.report) shouldBe Some(access)
    }

    // The middle state, and the whole reason there are two horizons. An answer
    // past its first horizon is still handed over — the reader is not made to
    // wait on Discord — but it is flagged, so whoever took it knows to resolve
    // it again behind them.
    "hand over an answer that has fallen due, and say that it has" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofMinutes(3), hardTtl = Duration.ofMinutes(10),
                                  now = clock)
      cache.put("u1", access)
      cache.get("u1") shouldBe Some(AccessCache.Cached(access, stale = false))
      clock.advance(200)
      cache.get("u1") shouldBe Some(AccessCache.Cached(access, stale = true))
    }

    // The outer horizon is what stops a refresh that never succeeds from
    // keeping an answer alive for ever.
    "stop handing over an answer once even the outer horizon has passed" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofMinutes(3), hardTtl = Duration.ofMinutes(10),
                                  now = clock)
      cache.put("u1", access)
      clock.advance(601)
      cache.get("u1") shouldBe None
    }

    // A failure has nothing worth serving stale: "reload in a moment to try
    // again" has to mean a real retry rather than being handed the same bad
    // moment back with a note asking somebody to refresh it.
    "never hand over an incomplete pass as merely stale" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofMinutes(3), hardTtl = Duration.ofMinutes(10),
                                  partialTtl = Duration.ofSeconds(5), now = clock)
      cache.put("u1", AccessReport(access.granted, List(UnreachableGuild("g2", "Elsewhere"))))
      cache.get("u1").map(_.stale) shouldBe Some(false)
      clock.advance(6)
      cache.get("u1") shouldBe None
    }

    "stay bounded when a lot of visitors arrive at once" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45), maxEntries = 10, now = clock)
      (1 to 50).foreach(i => cache.put(s"u$i", access))
      cache.size should be <= 10
    }

    "make room by dropping what has expired before dropping anything else" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45), maxEntries = 4, now = clock)
      // Filled to capacity, because the sweep is lazy: nothing is tidied while
      // there is still room, which keeps the common path free of bookkeeping.
      (1 to 4).foreach(i => cache.put(s"old$i", access))
      clock.advance(46)
      cache.put("fresh", access)          // sweeps the four that expired
      cache.get("fresh").map(_.report) shouldBe Some(access)
      cache.size shouldBe 1
    }
  }


  /** Counts what was asked of Discord, which is the whole point of the cache. */
  private class CountingGateway extends DiscordGateway {
    var lookups = 0
    def guilds: List[Guild] = List(guildStub)
    def guildById(id: String): Guild = if (id == "g1") guildStub else null
    def retrieveUser(id: String): User = null
    def memberAccess(guildId: String, userId: String, channelIds: List[String]): Option[MemberAccess] = {
      lookups += 1
      Some(MemberAccess(hasManageServer = false, Set.empty, Set("cat")))
    }
    def selfUserId: String = "self"
    def selfUserName: String = "ViolentBot"
    def selfUserAvatarUrl: String = ""
    def applicationOwnerId: String = "owner"
    def setWatchingActivity(text: String): Unit = ()
  }

  private def guildStub: Guild =
    java.lang.reflect.Proxy.newProxyInstance(
      classOf[Guild].getClassLoader, Array(classOf[Guild]),
      (_, method, _) => method.getName match {
        case "getId"      => "g1"
        case "getName"    => "Violent"
        // Null is what JDA gives for a guild that never set an icon.
        case "getIconUrl" => null
        case other        => throw new UnsupportedOperationException(other)
      }).asInstanceOf[Guild]

  private def service(gateway: DiscordGateway, clock: Clock) =
    new DashboardAccessService(gateway, _ => true,
      _ => List(WorldChannel("Antica", "cat")), _ => "0",
      cache = new AccessCache(Duration.ofSeconds(45), hardTtl = Duration.ofSeconds(45), now = clock))

  /** An execution context that runs nothing until the test says so, which is
   *  what makes "handed the old answer, and resolved again behind them"
   *  checkable rather than a race. */
  private class Deferred extends scala.concurrent.ExecutionContext {
    private val queued = scala.collection.mutable.Queue.empty[Runnable]
    def execute(runnable: Runnable): Unit = queued.enqueue(runnable)
    def reportFailure(cause: Throwable): Unit = ()
    def pending: Int = queued.size
    def runAll(): Unit = while (queued.nonEmpty) queued.dequeue().run()
  }

  /** As [[service]], with both horizons apart and the refresh held back. */
  private def refreshingService(gateway: DiscordGateway, clock: Clock, refresh: Deferred) =
    new DashboardAccessService(gateway, _ => true,
      _ => List(WorldChannel("Antica", "cat")), _ => "0",
      cache = new AccessCache(Duration.ofMinutes(3), hardTtl = Duration.ofMinutes(10), now = clock),
      refreshOn = refresh)


  "the access service" should {

    // Six polls a minute used to be six round trips to Discord.
    "ask Discord once however many times a poll repeats" in {
      val gateway = new CountingGateway
      val svc = service(gateway, new Clock)
      (1 to 10).foreach(_ => svc.rememberedAccessFor("u1", Set("g1")))
      gateway.lookups shouldBe 1
    }

    "ask again once the memory has aged out" in {
      val clock = new Clock
      val gateway = new CountingGateway
      val svc = service(gateway, clock)
      svc.rememberedAccessFor("u1", Set("g1"))
      clock.advance(46)
      svc.rememberedAccessFor("u1", Set("g1"))
      gateway.lookups shouldBe 2
    }

    // The point of the middle horizon: the reader is handed what we already
    // had and pays nothing, and Discord is asked again behind them. Before
    // this, whichever poll happened to arrive as the entry fell due wore the
    // whole chain of blocking lookups itself.
    "answer from a stale entry without asking Discord on the reader's thread" in {
      val clock = new Clock
      val gateway = new CountingGateway
      val refresh = new Deferred
      val svc = refreshingService(gateway, clock, refresh)

      svc.rememberedAccessFor("u1", Set("g1")).map(_.guildId) shouldBe List("g1")
      gateway.lookups shouldBe 1

      clock.advance(200)                  // past the first horizon, inside the second
      svc.rememberedAccessFor("u1", Set("g1")).map(_.guildId) shouldBe List("g1")
      // Nothing was asked of Discord to answer that call ...
      gateway.lookups shouldBe 1
      // ... but a refresh was left waiting to run.
      refresh.pending shouldBe 1

      refresh.runAll()
      gateway.lookups shouldBe 2
    }

    // A refresh already under way is not started again by the next reader
    // through — otherwise a stale entry on a busy board would queue one per
    // poll, which is the stampede this is meant to prevent.
    "start one refresh behind a stale entry, however many readers take it" in {
      val clock = new Clock
      val gateway = new CountingGateway
      val refresh = new Deferred
      val svc = refreshingService(gateway, clock, refresh)

      svc.rememberedAccessFor("u1", Set("g1"))
      clock.advance(200)
      (1 to 10).foreach(_ => svc.rememberedAccessFor("u1", Set("g1")))

      refresh.pending shouldBe 1
      refresh.runAll()
      gateway.lookups shouldBe 2
    }

    // Once even the outer horizon has passed there is nothing safe to hand
    // over, so the reader waits for a live answer as they always did.
    "make a reader wait once the entry is past both horizons" in {
      val clock = new Clock
      val gateway = new CountingGateway
      val refresh = new Deferred
      val svc = refreshingService(gateway, clock, refresh)

      svc.rememberedAccessFor("u1", Set("g1"))
      clock.advance(601)
      svc.rememberedAccessFor("u1", Set("g1")).map(_.guildId) shouldBe List("g1")

      gateway.lookups shouldBe 2          // resolved on the reader's own thread
      refresh.pending shouldBe 0
    }

    // The cold start, which is the worst moment to multiply Discord calls:
    // every visitor's entry is missing at once and they all arrive together.
    // One of them resolves and the rest take that answer.
    "resolve once when a crowd arrives on a cold entry together" in {
      val started = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val gateway = new CountingGateway {
        override def memberAccess(guildId: String, userId: String,
                                  channelIds: List[String]): Option[MemberAccess] = {
          started.countDown()
          release.await()
          super.memberAccess(guildId, userId, channelIds)
        }
      }
      val svc = service(gateway, new Clock)

      def reader() = {
        val t = new Thread(() => { svc.rememberedAccessFor("u1", Set("g1")); () })
        t.start(); t
      }

      val first = reader()
      // Once the lookup is under way the resolution is registered, so every
      // reader started from here on is guaranteed to find it and wait.
      started.await()
      val rest = (1 to 7).map(_ => reader())
      release.countDown()
      (first +: rest).foreach(_.join(10000))

      gateway.lookups shouldBe 1
    }

    // Signing in again with a different set of servers must not be answered
    // from the old one, or a newly joined guild would be invisible for a while.
    "not answer a different guild list from the same visitor's memory" in {
      val gateway = new CountingGateway
      val svc = service(gateway, new Clock)
      svc.rememberedAccessFor("u1", Set("g1"))
      svc.rememberedAccessFor("u1", Set("g1", "g2"))
      gateway.lookups shouldBe 2
    }

    "keep one visitor's answer out of another's" in {
      val gateway = new CountingGateway
      val svc = service(gateway, new Clock)
      svc.rememberedAccessFor("u1", Set("g1"))
      svc.rememberedAccessFor("u2", Set("g1"))
      gateway.lookups shouldBe 2
    }

    // The moderator tools take this path, and they act on other people's
    // claims — a role removed a minute ago has to be felt now.
    "never answer the live question from memory" in {
      val gateway = new CountingGateway
      val svc = service(gateway, new Clock)
      (1 to 3).foreach(_ => svc.accessFor("u1", Set("g1")))
      gateway.lookups shouldBe 3
    }
  }
}
