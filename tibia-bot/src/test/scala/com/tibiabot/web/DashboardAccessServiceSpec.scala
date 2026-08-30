package com.tibiabot.web

import com.tibiabot.discord.{DiscordGateway, MemberAccess, MemberLookup}
import net.dv8tion.jda.api.entities.{Guild, User}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DashboardAccessServiceSpec extends AnyWordSpec with Matchers {

  /** A stand-in `Guild` answering only the three things the service asks it.
   *  A proxy rather than a hand-written class because JDA's Guild is a very
   *  large interface and the service touches none of the rest of it.
   *
   *  `icon` is null by default, which is what JDA returns for a guild that never
   *  set one — the common case, and the one the dashboard has to fall back
   *  from. */
  private def guildStub(id: String, name: String, icon: String = null): Guild =
    java.lang.reflect.Proxy.newProxyInstance(
      classOf[Guild].getClassLoader, Array(classOf[Guild]),
      (_, method, _) => method.getName match {
        case "getId"      => id
        case "getName"    => name
        case "getIconUrl" => icon
        case other        => throw new UnsupportedOperationException(s"Guild.$other is not stubbed")
      }
    ).asInstanceOf[Guild]

  private class FakeGateway(
    botGuilds: List[(String, String)],
    /** A `var` so a test can have somebody become resolvable between two calls
     *  — which is what a guild run by another bot looks like when the answer
     *  arrives after the first page load gave up waiting for it. */
    var members: Map[(String, String), MemberAccess]
  ) extends DiscordGateway {
    /** Which channel-visibility questions were actually asked, so a test can
     *  show the REST call was skipped rather than merely ignored.
     *
     *  Appended under a lock because a visitor's guilds are now resolved at the
     *  same time as each other, so this is written from several threads at
     *  once. For the same reason the *order* here means nothing beyond one
     *  guild: assert on what was asked, not on the sequence. */
    var lookups: List[String] = Nil
    private def noteLookup(guildId: String): Unit =
      synchronized { lookups = lookups :+ guildId }

    /** Set to give every stub guild an icon, for the one test that cares. */
    var iconUrl: String = null

    def guilds: List[Guild] = botGuilds.map { case (id, name) => guildStub(id, name, iconUrl) }
    def guildById(id: String): Guild = botGuilds.find(_._1 == id).map { case (i, n) => guildStub(i, n, iconUrl) }.orNull
    def retrieveUser(id: String): User = null
    /** Guilds whose lookup fails outright rather than answering. A Discord rate
     *  limit, in other words — which is not a statement about the visitor and
     *  must not read as one. */
    var unreachable: Set[String] = Set.empty

    def memberAccess(guildId: String, userId: String, channelIds: List[String]): Option[MemberAccess] = {
      noteLookup(guildId)
      members.get((guildId, userId))
    }

    override def memberLookup(guildId: String, userId: String,
                              channelIds: List[String]): MemberLookup =
      if (unreachable.contains(guildId)) {
        noteLookup(guildId)
        MemberLookup.Unreachable("rate limited")
      } else super.memberLookup(guildId, userId, channelIds)
    def selfUserId: String = "self"
    def selfUserName: String = "ViolentBot"
    def selfUserAvatarUrl: String = "https://example.com/avatar.png"
    def applicationOwnerId: String = "owner"
    def setWatchingActivity(text: String): Unit = ()
  }

  private val AnticaCategory = "cat-antica"
  private val SecuraCategory = "cat-secura"
  private val ModRole = "role-mod"

  private def member(manageServer: Boolean = false, roles: Set[String] = Set.empty,
                     visible: Set[String] = Set(AnticaCategory)) =
    MemberAccess(manageServer, roles, visible)

  private def service(
    botGuilds: List[(String, String)] = List("g1" -> "Violent"),
    members: Map[(String, String), MemberAccess] = Map(("g1", "u1") -> member()),
    /** Guilds with a respawn forum that is actually there — a settings row
     *  naming a channel that still resolves on Discord. A guild outside this
     *  set has one or the other missing, which the service treats alike. */
    withForum: Set[String] = Set("g1"),
    worlds: Map[String, List[WorldChannel]] = Map("g1" -> List(WorldChannel("Antica", AnticaCategory))),
    moderatorRoles: Map[String, String] = Map.empty
  ) = {
    val gateway = new FakeGateway(botGuilds, members)
    (gateway, new DashboardAccessService(
      gateway,
      respawnForumExists = guild => withForum.contains(guild.getId),
      worldsOf = guildId => worlds.getOrElse(guildId, Nil),
      moderatorRoleOf = guildId => moderatorRoles.getOrElse(guildId, "0")
    ))
  }

  /** A gateway whose every lookup takes real time, so a test can hold work in
   *  flight or make one guild slower than another. */
  private class SlowGateway(botGuilds: List[(String, String)], perLookup: Long)
      extends FakeGateway(botGuilds, botGuilds.map { case (id, _) => (id, "u1") -> member() }.toMap) {
    override def memberAccess(guildId: String, userId: String,
                              channelIds: List[String]): Option[MemberAccess] = {
      Thread.sleep(perLookup)
      super.memberAccess(guildId, userId, channelIds)
    }
  }

  /** A gateway that lets no lookup finish until every one of them has started:
   *  each arrival waits for the rest before answering.
   *
   *  This is what makes the fan-out provable without a stopwatch. Resolved one
   *  at a time, the first lookup waits for guilds that will not be asked about
   *  until it returns — so nobody joins it, and [[resolvedAlone]] records that
   *  rather than the suite hanging on it. Concurrently, all three meet as soon
   *  as the pool schedules them and the wait is never approached, however busy
   *  the machine.
   */
  private class RendezvousGateway(botGuilds: List[(String, String)], waitFor: java.time.Duration)
      extends FakeGateway(botGuilds, botGuilds.map { case (id, _) => (id, "u1") -> member() }.toMap) {
    private val allStarted = new java.util.concurrent.CountDownLatch(botGuilds.size)
    /** Whether any lookup gave up waiting for the others to join it, which is
     *  what a serialised fan-out looks like from inside one. */
    @volatile var resolvedAlone = false
    override def memberAccess(guildId: String, userId: String,
                              channelIds: List[String]): Option[MemberAccess] = {
      allStarted.countDown()
      // Once one lookup has waited alone the answer is settled, so the rest go
      // straight through: a failing test costs one wait rather than one each.
      if (!resolvedAlone &&
          !allStarted.await(waitFor.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
        resolvedAlone = true
      super.memberAccess(guildId, userId, channelIds)
    }
  }

  /** The service under test over a gateway of the caller's choosing, fanning
   *  its lookups out onto `on`. */
  private def serviceOver(gateway: FakeGateway, on: scala.concurrent.ExecutionContext) =
    new DashboardAccessService(
      gateway,
      respawnForumExists = _ => true,
      worldsOf = _ => List(WorldChannel("Antica", AnticaCategory)),
      moderatorRoleOf = _ => "0",
      lookupOn = on)

  private def slowService(guilds: List[(String, String)], perLookup: Long,
                          on: scala.concurrent.ExecutionContext) = {
    val gateway = new SlowGateway(guilds, perLookup)
    (gateway, serviceOver(gateway, on))
  }

  "localAccessFor" should {

    // Each guild is a blocking round trip to Discord and no guild's answer
    // bears on any other's, so somebody in three of them should wait for the
    // slowest rather than for the sum. This is what decides how many visitors
    // one small refresh pool can keep up with.
    //
    // Asserted by having the lookups meet each other rather than by timing
    // them. A stopwatch measures the machine as much as the code: a bound wide
    // enough to survive a loaded one sits barely under the serial time it is
    // meant to rule out, so it fails on a busy suite while a real regression
    // squeaks past it.
    "ask about a visitor's guilds at the same time, not one after another" in {
      val pool = scala.concurrent.ExecutionContext.fromExecutorService(
        java.util.concurrent.Executors.newFixedThreadPool(4))
      try {
        // Only ever waited out by a test that has already failed, so it can be
        // as generous as it likes; well under the service's own lookup
        // backstop, which would otherwise be what reported the failure.
        val gateway = new RendezvousGateway(
          List("g1" -> "One", "g2" -> "Two", "g3" -> "Three"),
          waitFor = java.time.Duration.ofSeconds(5))
        val granted = serviceOver(gateway, pool).accessFor("u1", Set("g1", "g2", "g3"))

        withClue("a lookup waited for the others and none arrived, so the " +
                 "guilds were resolved one at a time: ") {
          gateway.resolvedAlone shouldBe false
        }
        granted.map(_.guildId).sorted shouldBe List("g1", "g2", "g3")
        gateway.lookups should contain theSameElementsAs List("g1", "g2", "g3")
      } finally pool.shutdown()
    }

    // Order used to come from folding over the guilds in turn, and several
    // things downstream read the first element — so it has to survive the
    // change from a fold to a fan-out.
    "keep the visitor's guilds in the order the gateway lists them" in {
      val pool = scala.concurrent.ExecutionContext.fromExecutorService(
        java.util.concurrent.Executors.newFixedThreadPool(4))
      try {
        // Slowest first, so anything ordering by completion would invert it.
        val (_, svc) = slowService(List("g1" -> "One", "g2" -> "Two", "g3" -> "Three"),
                                   perLookup = 50, on = pool)
        svc.accessFor("u1", Set("g1", "g2", "g3")).map(_.guildId) shouldBe List("g1", "g2", "g3")
      } finally pool.shutdown()
    }

    // Much the commonest case, and it should cost nothing extra: handing a
    // single lookup to another pool and waiting for it back is a thread hop
    // that buys nothing. Proved with a pool that refuses everything — if the
    // one-guild path touched it, this could not resolve at all.
    "resolve a single guild without going near the lookup pool" in {
      val refusing = new scala.concurrent.ExecutionContext {
        def execute(runnable: Runnable): Unit = throw new java.util.concurrent.RejectedExecutionException("nope")
        def reportFailure(cause: Throwable): Unit = ()
      }
      val (_, svc) = slowService(List("g1" -> "One"), perLookup = 0, on = refusing)
      svc.accessFor("u1", Set("g1")).map(_.guildId) shouldBe List("g1")
    }

    // A wedged Discord call has no timeout of its own, so before the backstop
    // it held the request until akka gave up. Reported as unreachable rather
    // than as a visitor with fewer servers than they have.
    "report guilds as unanswered rather than hanging on a lookup that never returns" in {
      val pool = scala.concurrent.ExecutionContext.fromExecutorService(
        java.util.concurrent.Executors.newFixedThreadPool(4))
      try {
        val gateway = new SlowGateway(List("g1" -> "One", "g2" -> "Two"), perLookup = 5000)
        val svc = new DashboardAccessService(
          gateway, respawnForumExists = _ => true,
          worldsOf = _ => List(WorldChannel("Antica", AnticaCategory)),
          moderatorRoleOf = _ => "0",
          lookupOn = pool, lookupWait = java.time.Duration.ofMillis(300))
        val report = svc.accessReportFor("u1", Set("g1", "g2"))
        report.granted shouldBe Nil
        report.unreachable.map(_.guildId) should contain theSameElementsAs List("g1", "g2")
      } finally pool.shutdown()
    }
  }

  "accessIn" should {

    "resolve a guild this bot is in without troubling the other bots" in {
      // The relay is the slow part — Redis, then however many bots have
      // published a roster — and a moderator acting on a guild we are in has no
      // need of it. Going through accessFor made every force-leave wait on it.
      var asked = false
      val gateway = new FakeGateway(List("g1" -> "Violent"), Map(("g1", "u1") -> member()))
      val svc = new DashboardAccessService(
        gateway,
        respawnForumExists = guild => guild.getId == "g1",
        worldsOf = _ => List(WorldChannel("Antica", AnticaCategory)),
        moderatorRoleOf = _ => "0",
        remote = Some(new RemoteGuildAccess(
          com.tibiabot.persistence.NoopRedisCache, akka.actor.ActorSystem(
            "access-in-spec", com.typesafe.config.ConfigFactory.defaultReference()).scheduler,
          isLocal = _ => { asked = true; false })(scala.concurrent.ExecutionContext.global)))

      svc.accessIn("u1", Set("g1"), "g1") shouldBe
        List(GuildAccess("g1", "Violent", AccessTier.Member, List("Antica")))
      asked shouldBe false
    }

    "answer nothing for a guild the visitor is not even in, without asking anybody" in {
      val (gateway, svc) = service()
      svc.accessIn("u1", Set("g1"), "g-elsewhere") shouldBe Nil
      // Not a member lookup either: the guild list rules it out on its own.
      gateway.lookups shouldBe Nil
    }
  }

  "accessFor" should {

    "give a member access to a guild whose world they can see" in {
      val (_, svc) = service()
      svc.accessFor("u1", Set("g1")) shouldBe
        List(GuildAccess("g1", "Violent", AccessTier.Member, List("Antica")))
    }

    "carry the guild's own icon through, for the server menu to wear" in {
      val (gateway, svc) = service()
      gateway.iconUrl = "https://cdn.discordapp.com/icons/g1/abc.png"
      svc.accessFor("u1", Set("g1")).head.iconUrl shouldBe
        Some("https://cdn.discordapp.com/icons/g1/abc.png")
    }

    // JDA answers null rather than throwing, and an absence is what every
    // surface showing it falls back from.
    "report no icon at all for a guild that never set one" in {
      val (_, svc) = service()
      svc.accessFor("u1", Set("g1")).head.iconUrl shouldBe None
    }

    // The cached guild list narrows the work; it must not be able to widen it.
    "ignore a guild the bot is not in, whatever the visitor's list claims" in {
      val (_, svc) = service()
      svc.accessFor("u1", Set("g1", "g-elsewhere")).map(_.guildId) shouldBe List("g1")
    }

    // Briefly the opposite: an empty list meant "no hint" and every guild was
    // considered, so a visitor whose cache had aged out still saw their board.
    // Each candidate costs a blocking member lookup, though, and after a restart
    // every visitor's list is empty at once — which took GET /dashboard past
    // akka's request timeout in production. The narrowing is what makes this
    // affordable, so an empty list resolves to no access and the cache going
    // empty is fixed where it happens instead.
    "skip every guild when the visitor's list is missing" in {
      val (gateway, svc) = service()
      svc.accessFor("u1", Set.empty) shouldBe empty
      // Nothing was asked of Discord at all, which is the point.
      gateway.lookups shouldBe empty
    }

    "refuse a guild that never set the respawn system up" in {
      val (_, svc) = service(withForum = Set.empty)
      svc.accessFor("u1", Set("g1")) shouldBe empty
    }

    // Cheap local read first, so an unconfigured guild costs no REST call.
    "not ask Discord about a guild it can rule out locally" in {
      val (gateway, svc) = service(withForum = Set.empty)
      svc.accessFor("u1", Set("g1"))
      gateway.lookups shouldBe empty
    }

    // The settings row is written before the forum is built, and survives the
    // forum being deleted afterwards — so "has a row" was never the same
    // question as "has a forum", and answering the first offered people a
    // server whose dashboard had nothing behind it.
    "refuse a guild whose respawn forum is gone" in {
      val (gateway, svc) = service(withForum = Set.empty)
      svc.accessFor("u1", Set("g1")) shouldBe empty
      // Ruled out on a local channel lookup, before any REST call.
      gateway.lookups shouldBe empty
    }

    "refuse a guild whose worlds the visitor cannot see" in {
      val (_, svc) = service(members = Map(("g1", "u1") -> member(visible = Set.empty)))
      svc.accessFor("u1", Set("g1")) shouldBe empty
    }

    "refuse a guild where the member cannot be resolved at all" in {
      val (_, svc) = service(members = Map.empty)
      svc.accessFor("u1", Set("g1")) shouldBe empty
    }

    // A world with no category recorded proves nothing, so it must not be
    // treated as visible to everyone.
    "ignore worlds with no category, and the guild with them if none are left" in {
      val (_, svc) = service(worlds = Map("g1" -> List(WorldChannel("Antica", ""))))
      svc.accessFor("u1", Set("g1")) shouldBe empty
    }

    "report only the worlds actually visible, not every world in the guild" in {
      val (_, svc) = service(
        worlds = Map("g1" -> List(WorldChannel("Antica", AnticaCategory), WorldChannel("Secura", SecuraCategory))),
        members = Map(("g1", "u1") -> member(visible = Set(SecuraCategory)))
      )
      svc.accessFor("u1", Set("g1")).map(_.worlds) shouldBe List(List("Secura"))
    }
  }

  "tier resolution" should {

    "promote somebody holding the guild's moderator role" in {
      val (_, svc) = service(
        members = Map(("g1", "u1") -> member(roles = Set(ModRole))),
        moderatorRoles = Map("g1" -> ModRole)
      )
      svc.accessFor("u1", Set("g1")).map(_.tier) shouldBe List(AccessTier.Moderator)
    }

    "promote Manage Server to admin without any role" in {
      val (_, svc) = service(members = Map(("g1", "u1") -> member(manageServer = true)))
      svc.accessFor("u1", Set("g1")).map(_.tier) shouldBe List(AccessTier.Admin)
    }

    "not promote somebody holding a different guild's moderator role" in {
      val (_, svc) = service(
        members = Map(("g1", "u1") -> member(roles = Set("role-somewhere-else"))),
        moderatorRoles = Map("g1" -> ModRole)
      )
      svc.accessFor("u1", Set("g1")).map(_.tier) shouldBe List(AccessTier.Member)
    }

    // "0" is how a guild with no moderator role is recorded. Matching it would
    // promote everybody, since nobody holds a role with that id.
    "treat an unset moderator role as matching nobody" in {
      val (_, svc) = service(
        members = Map(("g1", "u1") -> member(roles = Set("0"))),
        moderatorRoles = Map("g1" -> "0")
      )
      svc.accessFor("u1", Set("g1")).map(_.tier) shouldBe List(AccessTier.Member)
    }
  }

  "entryFor" should {

    "send a visitor with no usable guild to the empty state" in {
      val (_, svc) = service(withForum = Set.empty)
      svc.entryFor("u1", Set("g1")) shouldBe DashboardEntry.Nowhere
    }

    "take a visitor with one usable guild straight through" in {
      val (_, svc) = service()
      svc.entryFor("u1", Set("g1")) shouldBe
        DashboardEntry.Straight(GuildAccess("g1", "Violent", AccessTier.Member, List("Antica")))
    }

    "offer a picker once there are two" in {
      val (_, svc) = service(
        botGuilds = List("g1" -> "Violent", "g2" -> "Allies"),
        members = Map(("g1", "u1") -> member(), ("g2", "u1") -> member()),
        withForum = Set("g1", "g2"),
        worlds = Map(
          "g1" -> List(WorldChannel("Antica", AnticaCategory)),
          "g2" -> List(WorldChannel("Antica", AnticaCategory)))
      )
      svc.entryFor("u1", Set("g1", "g2")) match {
        case DashboardEntry.Choose(options, _) => options.map(_.guildName) shouldBe List("Allies", "Violent")
        case other => fail(s"expected a picker, got $other")
      }
    }

    // The reported bug, end to end. A Discord lookup that fails for one of two
    // servers used to leave a list of one, which was read as "nothing to ask"
    // and became a redirect into the other server's board — arriving somewhere
    // they never chose, with the switcher hidden because that list was also of
    // length one.
    "show the picker when a server's lookup failed, rather than jumping into the other" in {
      val (gateway, svc) = twoGuilds
      gateway.unreachable = Set("g2")
      svc.entryFor("u1", Set("g1", "g2")) match {
        case DashboardEntry.Choose(options, missing) =>
          options.map(_.guildId) shouldBe List("g1")
          missing.map(_.guildName) shouldBe List("Allies")
        case other => fail(s"expected a picker, got $other")
      }
    }

    // A refusal is still a refusal. Somebody genuinely removed from a server
    // should see it quietly disappear, not be told the dashboard is broken.
    "still go straight through when the second server simply is not theirs" in {
      val (_, svc) = twoGuilds
      svc.entryFor("u1", Set("g1")) shouldBe
        DashboardEntry.Straight(GuildAccess("g1", "Violent", AccessTier.Member, List("Antica")))
    }

    "send somebody whose every server failed to the try-again page, not the empty one" in {
      val (gateway, svc) = twoGuilds
      gateway.unreachable = Set("g1", "g2")
      svc.entryFor("u1", Set("g1", "g2")) match {
        case DashboardEntry.Unreachable(guilds) => guilds.map(_.guildId) should contain theSameElementsAs List("g1", "g2")
        case other => fail(s"expected the try-again page, got $other")
      }
    }
  }

  /** Two guilds the visitor can use, both resolvable — so a test can break
   *  exactly one of them and say what that should look like. */
  private def twoGuilds = service(
    botGuilds = List("g1" -> "Violent", "g2" -> "Allies"),
    members = Map(("g1", "u1") -> member(), ("g2", "u1") -> member()),
    withForum = Set("g1", "g2"),
    worlds = Map(
      "g1" -> List(WorldChannel("Antica", AnticaCategory)),
      "g2" -> List(WorldChannel("Antica", AnticaCategory))))

  /** Two guilds, one of which starts out unresolvable — the shape of a guild
   *  another bot runs that has not answered yet. */
  private def twoServers = {
    val gateway = new FakeGateway(
      List("g1" -> "Violent", "g2" -> "Ruckus"),
      Map(("g1", "u1") -> member()))
    (gateway, new DashboardAccessService(
      gateway,
      respawnForumExists = guild => Set("g1", "g2").contains(guild.getId),
      worldsOf = _ => List(WorldChannel("Antica", AnticaCategory)),
      moderatorRoleOf = _ => "0"))
  }

  "rememberedAccessFor" should {

    "answer a second read from the last few seconds rather than asking again" in {
      val (gateway, svc) = twoServers
      svc.rememberedAccessFor("u1", Set("g1", "g2"), Some("g1")).map(_.guildId) shouldBe List("g1")
      svc.rememberedAccessFor("u1", Set("g1", "g2"), Some("g1")).map(_.guildId) shouldBe List("g1")
      // One pass over the candidates, not two — this is what keeps the ten
      // second board poll from costing a Discord call each time. The two are
      // asked concurrently, so which arrives first is not a fact about the
      // code: what matters is that each was asked exactly once.
      gateway.lookups should contain theSameElementsAs List("g1", "g2")
    }

    // The bug this exists for: one page load that lost its race with the bot
    // running the other guild was remembered as "no such server for you", and
    // every reload for the next forty-five seconds was refused from that memory
    // rather than asked afresh.
    "ask again rather than refuse a guild an earlier answer had lost" in {
      val (gateway, svc) = twoServers
      svc.rememberedAccessFor("u1", Set("g1", "g2"), Some("g2")).map(_.guildId) shouldBe List("g1")

      gateway.members += (("g2", "u1") -> member())
      svc.rememberedAccessFor("u1", Set("g1", "g2"), Some("g2")).map(_.guildId) shouldBe
        List("g1", "g2")
    }

    "still answer from memory when nothing in particular was asked for" in {
      val (gateway, svc) = twoServers
      svc.rememberedAccessFor("u1", Set("g1", "g2")).map(_.guildId) shouldBe List("g1")
      gateway.members += (("g2", "u1") -> member())
      // No guild was named, so there is no refusal to second-guess and the
      // remembered answer stands until it ages out.
      svc.rememberedAccessFor("u1", Set("g1", "g2")).map(_.guildId) shouldBe List("g1")
    }
  }

  "permits" should {

    "allow a moderator to act in the guild they hold it in" in {
      val (_, svc) = service(
        members = Map(("g1", "u1") -> member(roles = Set(ModRole))),
        moderatorRoles = Map("g1" -> ModRole)
      )
      svc.permits("u1", Set("g1"), "g1", AccessTier.Moderator) shouldBe true
    }

    "refuse a plain member the moderator tools" in {
      val (_, svc) = service()
      svc.permits("u1", Set("g1"), "g1", AccessTier.Moderator) shouldBe false
      svc.permits("u1", Set("g1"), "g1", AccessTier.Member) shouldBe true
    }

    // The check that stops somebody naming a guild they were never resolved in.
    "refuse a guild the visitor has no access to" in {
      val (_, svc) = service()
      svc.permits("u1", Set("g1"), "g-other", AccessTier.Member) shouldBe false
    }

    // Resolved fresh, so losing the role takes effect on the next action rather
    // than whenever a cache happens to expire.
    "re-resolve rather than reusing an earlier answer" in {
      val (gateway, svc) = service(
        members = Map(("g1", "u1") -> member(roles = Set(ModRole))),
        moderatorRoles = Map("g1" -> ModRole)
      )
      svc.permits("u1", Set("g1"), "g1", AccessTier.Moderator) shouldBe true
      svc.permits("u1", Set("g1"), "g1", AccessTier.Moderator) shouldBe true
      gateway.lookups shouldBe List("g1", "g1")
    }
  }
}
