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
      val cache = new AccessCache(Duration.ofSeconds(45), now = clock)
      cache.put("u1", access)
      clock.advance(44)
      cache.get("u1") shouldBe Some(access)
    }

    "forget once the window has passed" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), now = clock)
      cache.put("u1", access)
      clock.advance(46)
      cache.get("u1") shouldBe None
    }

    // An expired entry that is never asked for again would otherwise sit there
    // for the life of the process.
    "drop an expired entry rather than keep it around" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), now = clock)
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
      cache.get("u1") shouldBe Some(AccessReport.Empty)
    }

    // Caching only the granted half would hand the next reader a list that
    // looks complete and is not — which is the whole confusion the report was
    // introduced to remove, reappearing one layer down.
    "remember that a pass could not reach a server" in {
      val cache = new AccessCache(Duration.ofSeconds(45))
      val partial = AccessReport(access.granted, List(UnreachableGuild("g2", "Elsewhere")))
      cache.put("u1", partial)
      cache.get("u1").map(_.unreachable) shouldBe Some(List(UnreachableGuild("g2", "Elsewhere")))
      cache.get("u1").map(_.complete) shouldBe Some(false)
    }

    "stay bounded when a lot of visitors arrive at once" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), maxEntries = 10, now = clock)
      (1 to 50).foreach(i => cache.put(s"u$i", access))
      cache.size should be <= 10
    }

    "make room by dropping what has expired before dropping anything else" in {
      val clock = new Clock
      val cache = new AccessCache(Duration.ofSeconds(45), maxEntries = 4, now = clock)
      // Filled to capacity, because the sweep is lazy: nothing is tidied while
      // there is still room, which keeps the common path free of bookkeeping.
      (1 to 4).foreach(i => cache.put(s"old$i", access))
      clock.advance(46)
      cache.put("fresh", access)          // sweeps the four that expired
      cache.get("fresh") shouldBe Some(access)
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
      cache = new AccessCache(Duration.ofSeconds(45), now = clock))


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
