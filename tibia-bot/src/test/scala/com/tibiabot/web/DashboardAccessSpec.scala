package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DashboardAccessSpec extends AnyWordSpec with Matchers {

  private def access(guildId: String, name: String, tier: AccessTier = AccessTier.Member,
                     worlds: List[String] = List("Antica")) =
    GuildAccess(guildId, name, tier, worlds)

  "AccessTier.of" should {
    "give a plain member no elevation" in {
      AccessTier.of(hasManageServer = false, hasModeratorRole = false) shouldBe AccessTier.Member
    }

    "promote the moderator role" in {
      AccessTier.of(hasManageServer = false, hasModeratorRole = true) shouldBe AccessTier.Moderator
    }

    "treat Manage Server as admin" in {
      AccessTier.of(hasManageServer = true, hasModeratorRole = false) shouldBe AccessTier.Admin
    }

    // Permissions.isModerator already treats Manage Server as granting the
    // moderator powers, so holding both must not read as anything less.
    "not demote somebody holding both" in {
      AccessTier.of(hasManageServer = true, hasModeratorRole = true) shouldBe AccessTier.Admin
    }
  }

  "AccessTier.atLeast" should {
    "let a tier satisfy itself" in {
      AccessTier.Moderator.atLeast(AccessTier.Moderator) shouldBe true
    }

    "let a higher tier satisfy a lower requirement" in {
      AccessTier.Admin.atLeast(AccessTier.Moderator) shouldBe true
      AccessTier.Admin.atLeast(AccessTier.Member) shouldBe true
      AccessTier.Moderator.atLeast(AccessTier.Member) shouldBe true
    }

    "refuse a lower tier where more is required" in {
      AccessTier.Member.atLeast(AccessTier.Moderator) shouldBe false
      AccessTier.Moderator.atLeast(AccessTier.Admin) shouldBe false
    }
  }

  "DashboardAccess.eligible" should {
    "accept a configured guild whose visitor can see a world" in {
      DashboardAccess.eligible(respawnConfigured = true, visibleWorlds = List("Antica")) shouldBe true
    }

    // Being in the Discord says nothing about whether the Tibia team let you
    // into their channels, which is the whole point of the check.
    "refuse a guild whose worlds the visitor cannot see" in {
      DashboardAccess.eligible(respawnConfigured = true, visibleWorlds = Nil) shouldBe false
    }

    "refuse a guild that never set the respawn system up" in {
      DashboardAccess.eligible(respawnConfigured = false, visibleWorlds = List("Antica")) shouldBe false
    }

    "refuse when neither holds" in {
      DashboardAccess.eligible(respawnConfigured = false, visibleWorlds = Nil) shouldBe false
    }
  }

  "DashboardAccess.entryFor" should {
    "send somebody with nothing to the empty state" in {
      DashboardAccess.entryFor(Nil) shouldBe DashboardEntry.Nowhere
    }

    "take a single guild straight through, asking nothing" in {
      val only = access("g1", "Violent")
      DashboardAccess.entryFor(List(only)) shouldBe DashboardEntry.Straight(only)
    }

    "offer a choice once there is more than one" in {
      val a = access("g1", "Violent")
      val b = access("g2", "Allies")
      DashboardAccess.entryFor(List(a, b)) match {
        case DashboardEntry.Choose(options) => options.map(_.guildId) shouldBe List("g2", "g1")
        case other => fail(s"expected a picker, got $other")
      }
    }

    // Nearly everybody who uses the bot has joined the support Discord, so
    // counting it would put a picker in front of every member of one community
    // — a question with one real answer.
    "take somebody with one community of their own straight there, ignoring the support server" in {
      val demo = access("support", "Violent Bot")
      val theirs = access("g1", "Antica Hunters")
      DashboardAccess.entryFor(List(demo, theirs), demoGuildId = "support") shouldBe
        DashboardEntry.Straight(theirs)
    }

    // Somebody with no community of their own came to look at the thing, and
    // the support server's board is what there is to look at.
    "take somebody with nothing but the support server straight into it, as the demo" in {
      val demo = access("support", "Violent Bot")
      DashboardAccess.entryFor(List(demo), demoGuildId = "support") shouldBe DashboardEntry.Straight(demo)
    }

    "ask only once there are two communities of their own to choose between" in {
      val demo = access("support", "Violent Bot")
      val a = access("g1", "Antica Hunters")
      val b = access("g2", "Belobra Bois")
      DashboardAccess.entryFor(List(demo, a, b), demoGuildId = "support") match {
        // And the support server is not among the options: it is not what they
        // are choosing between.
        case DashboardEntry.Choose(options) => options.map(_.guildId) shouldBe List("g1", "g2")
        case other => fail(s"expected a picker, got $other")
      }
    }

    "still send somebody with nothing at all to the empty state" in {
      DashboardAccess.entryFor(Nil, demoGuildId = "support") shouldBe DashboardEntry.Nowhere
    }

    // A picker that reorders itself between visits is one nobody builds muscle
    // memory for.
    "order the picker by name, case-insensitively, whatever order it was given" in {
      val unordered = List(access("g1", "zeta"), access("g2", "Alpha"), access("g3", "beta"))
      DashboardAccess.entryFor(unordered) match {
        case DashboardEntry.Choose(options) => options.map(_.guildName) shouldBe List("Alpha", "beta", "zeta")
        case other => fail(s"expected a picker, got $other")
      }
    }
  }

  "DashboardAccess.permits" should {
    val accesses = List(
      access("g-member", "Member Guild", AccessTier.Member),
      access("g-mod", "Mod Guild", AccessTier.Moderator),
      access("g-admin", "Admin Guild", AccessTier.Admin)
    )

    "allow an action within a guild the visitor holds the tier in" in {
      DashboardAccess.permits(accesses, "g-mod", AccessTier.Moderator) shouldBe true
      DashboardAccess.permits(accesses, "g-admin", AccessTier.Moderator) shouldBe true
      DashboardAccess.permits(accesses, "g-member", AccessTier.Member) shouldBe true
    }

    "refuse an action the visitor's tier in that guild does not reach" in {
      DashboardAccess.permits(accesses, "g-member", AccessTier.Moderator) shouldBe false
      DashboardAccess.permits(accesses, "g-mod", AccessTier.Admin) shouldBe false
    }

    // The one that matters: holding moderator somewhere must never carry into a
    // guild where you are a plain member, or a mod of any server could move
    // claims in every server they belong to.
    "not let a tier held in one guild leak into another" in {
      DashboardAccess.permits(accesses, "g-member", AccessTier.Moderator) shouldBe false
      DashboardAccess.permits(accesses, "g-mod", AccessTier.Admin) shouldBe false
    }

    "refuse a guild the visitor has no access to at all" in {
      DashboardAccess.permits(accesses, "g-unknown", AccessTier.Member) shouldBe false
    }

    "refuse everything when the visitor has no access anywhere" in {
      DashboardAccess.permits(Nil, "g-mod", AccessTier.Member) shouldBe false
    }
  }
}
