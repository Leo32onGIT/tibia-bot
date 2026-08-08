package com.tibiabot.web

import com.tibiabot.discord.{DiscordGateway, MemberAccess}
import net.dv8tion.jda.api.entities.{Guild, User}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DashboardAccessServiceSpec extends AnyWordSpec with Matchers {

  /** A stand-in `Guild` answering only the two things the service asks it.
   *  A proxy rather than a hand-written class because JDA's Guild is a very
   *  large interface and the service touches none of the rest of it. */
  private def guildStub(id: String, name: String): Guild =
    java.lang.reflect.Proxy.newProxyInstance(
      classOf[Guild].getClassLoader, Array(classOf[Guild]),
      (_, method, _) => method.getName match {
        case "getId"   => id
        case "getName" => name
        case other     => throw new UnsupportedOperationException(s"Guild.$other is not stubbed")
      }
    ).asInstanceOf[Guild]

  private class FakeGateway(
    botGuilds: List[(String, String)],
    members: Map[(String, String), MemberAccess]
  ) extends DiscordGateway {
    /** Which channel-visibility questions were actually asked, so a test can
     *  show the REST call was skipped rather than merely ignored. */
    var lookups: List[String] = Nil

    def guilds: List[Guild] = botGuilds.map { case (id, name) => guildStub(id, name) }
    def guildById(id: String): Guild = botGuilds.find(_._1 == id).map { case (i, n) => guildStub(i, n) }.orNull
    def retrieveUser(id: String): User = null
    def memberAccess(guildId: String, userId: String, channelIds: List[String]): Option[MemberAccess] = {
      lookups = lookups :+ guildId
      members.get((guildId, userId))
    }
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
    configured: Set[String] = Set("g1"),
    worlds: Map[String, List[WorldChannel]] = Map("g1" -> List(WorldChannel("Antica", AnticaCategory))),
    moderatorRoles: Map[String, String] = Map.empty
  ) = {
    val gateway = new FakeGateway(botGuilds, members)
    (gateway, new DashboardAccessService(
      gateway,
      respawnConfigured = configured.contains,
      worldsOf = guildId => worlds.getOrElse(guildId, Nil),
      moderatorRoleOf = guildId => moderatorRoles.getOrElse(guildId, "0")
    ))
  }

  "accessFor" should {

    "give a member access to a guild whose world they can see" in {
      val (_, svc) = service()
      svc.accessFor("u1", Set("g1")) shouldBe
        List(GuildAccess("g1", "Violent", AccessTier.Member, List("Antica")))
    }

    // The cached guild list narrows the work; it must not be able to widen it.
    "ignore a guild the bot is not in, whatever the visitor's list claims" in {
      val (_, svc) = service()
      svc.accessFor("u1", Set("g1", "g-elsewhere")).map(_.guildId) shouldBe List("g1")
    }

    // An empty list is the absence of a hint, not the answer "none". It happens
    // whenever the login cache aged out or the process restarted, and reading it
    // as an answer would show somebody with a valid session an empty dashboard.
    "consider every guild when the visitor's list is missing" in {
      val (_, svc) = service()
      svc.accessFor("u1", Set.empty).map(_.guildId) shouldBe List("g1")
    }

    // Which is only safe because the hint never granted anything: membership is
    // resolved live, so a guild the visitor is not in still refuses them.
    "still refuse a guild the visitor is not in when there is no list to narrow by" in {
      val (_, svc) = service(
        botGuilds = List("g1" -> "Violent", "g2" -> "Somewhere else"),
        members = Map(("g1", "u1") -> member()),
        configured = Set("g1", "g2"),
        worlds = Map("g1" -> List(WorldChannel("Antica", AnticaCategory)),
                     "g2" -> List(WorldChannel("Secura", SecuraCategory))))
      svc.accessFor("u1", Set.empty).map(_.guildId) shouldBe List("g1")
    }

    // The widened search is bounded by how many guilds run respawns, not by how
    // many the bot is in — otherwise a restart would cost a REST call per guild.
    "not ask Discord about unconfigured guilds even with no list to narrow by" in {
      val (gateway, svc) = service(
        botGuilds = List("g1" -> "Violent", "g2" -> "Somewhere else"),
        configured = Set("g1"))
      svc.accessFor("u1", Set.empty)
      gateway.lookups shouldBe List("g1")
    }

    "refuse a guild that never set the respawn system up" in {
      val (_, svc) = service(configured = Set.empty)
      svc.accessFor("u1", Set("g1")) shouldBe empty
    }

    // Cheap local read first, so an unconfigured guild costs no REST call.
    "not ask Discord about a guild it can rule out locally" in {
      val (gateway, svc) = service(configured = Set.empty)
      svc.accessFor("u1", Set("g1"))
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
      val (_, svc) = service(configured = Set.empty)
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
        configured = Set("g1", "g2"),
        worlds = Map(
          "g1" -> List(WorldChannel("Antica", AnticaCategory)),
          "g2" -> List(WorldChannel("Antica", AnticaCategory)))
      )
      svc.entryFor("u1", Set("g1", "g2")) match {
        case DashboardEntry.Choose(options) => options.map(_.guildName) shouldBe List("Allies", "Violent")
        case other => fail(s"expected a picker, got $other")
      }
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
