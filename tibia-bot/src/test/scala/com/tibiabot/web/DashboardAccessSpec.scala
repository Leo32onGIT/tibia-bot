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
      DashboardAccess.entryFor(AccessReport.Empty) shouldBe DashboardEntry.Nowhere
    }

    "take a single guild straight through, asking nothing" in {
      val only = access("g1", "Violent")
      DashboardAccess.entryFor(AccessReport.of(List(only))) shouldBe DashboardEntry.Straight(only)
    }

    "offer a choice once there is more than one" in {
      val a = access("g1", "Violent")
      val b = access("g2", "Allies")
      DashboardAccess.entryFor(AccessReport.of(List(a, b))) match {
        case DashboardEntry.Choose(options, _) => options.map(_.guildId) shouldBe List("g2", "g1")
        case other => fail(s"expected a picker, got $other")
      }
    }

    // Nearly everybody who uses the bot has joined the support Discord, so
    // counting it would put a picker in front of every member of one community
    // — a question with one real answer.
    "take somebody with one community of their own straight there, ignoring the support server" in {
      val demo = access("support", "Violent Bot")
      val theirs = access("g1", "Antica Hunters")
      DashboardAccess.entryFor(AccessReport.of(List(demo, theirs)), demoGuildId = "support") shouldBe
        DashboardEntry.Straight(theirs)
    }

    // Somebody with no community of their own came to look at the thing, and
    // the support server's board is what there is to look at.
    "take somebody with nothing but the support server straight into it, as the demo" in {
      val demo = access("support", "Violent Bot")
      DashboardAccess.entryFor(AccessReport.of(List(demo)), demoGuildId = "support") shouldBe DashboardEntry.Straight(demo)
    }

    "ask only once there are two communities of their own to choose between" in {
      val demo = access("support", "Violent Bot")
      val a = access("g1", "Antica Hunters")
      val b = access("g2", "Belobra Bois")
      DashboardAccess.entryFor(AccessReport.of(List(demo, a, b)), demoGuildId = "support") match {
        // And the support server is not among the options: it is not what they
        // are choosing between.
        case DashboardEntry.Choose(options, _) => options.map(_.guildId) shouldBe List("g1", "g2")
        case other => fail(s"expected a picker, got $other")
      }
    }

    // The bug this whole distinction exists for. Two servers, one of which did
    // not answer, used to leave a list of one — which entryFor read as "there is
    // nothing to ask" and turned into a redirect straight into the survivor's
    // board. The visitor did not choose that server, was not told the other one
    // was missing, and the board they landed on hid the switcher because it
    // also counted the list as a list of one.
    "not skip the picker when a server failed to answer" in {
      val a = access("g1", "Violent")
      val report = AccessReport(List(a), List(UnreachableGuild("g2", "Ruckus")))
      DashboardAccess.entryFor(report) match {
        case DashboardEntry.Choose(options, missing) =>
          options.map(_.guildId) shouldBe List("g1")
          missing.map(_.guildName) shouldBe List("Ruckus")
        case other => fail(s"expected a picker, got $other")
      }
    }

    // The same guard, one server further along: a picker that is already a
    // picker still has to say it is short, or a visitor reads it as the whole
    // truth and concludes they were removed from the missing one.
    "carry what it could not reach into a picker it would have shown anyway" in {
      val report = AccessReport(
        List(access("g1", "Violent"), access("g2", "Allies")),
        List(UnreachableGuild("g3", "Ruckus")))
      DashboardAccess.entryFor(report) match {
        case DashboardEntry.Choose(options, missing) =>
          options.map(_.guildId) shouldBe List("g2", "g1")
          missing.map(_.guildId) shouldBe List("g3")
        case other => fail(s"expected a picker, got $other")
      }
    }

    // One server, genuinely resolved, and nothing missing. The straight-through
    // case has to survive all of this: it is what almost everybody gets.
    "still go straight through when the one server is the whole truth" in {
      val only = access("g1", "Violent")
      DashboardAccess.entryFor(AccessReport(List(only), Nil)) shouldBe DashboardEntry.Straight(only)
    }

    // "You have no servers here" and "we could not reach your servers" are
    // opposite advice — one says go and set the bot up, the other says wait a
    // moment and reload.
    "tell an empty answer apart from an unreachable one" in {
      val missing = List(UnreachableGuild("g1", "Violent"))
      DashboardAccess.entryFor(AccessReport(Nil, missing)) shouldBe DashboardEntry.Unreachable(missing)
    }

    // The support server is set aside from the landing decision, not from the
    // question of whether the answer was complete. Somebody whose own server
    // did not answer must not be dropped into the demo as though it were their
    // only one.
    "not fall back to the support server when their own did not answer" in {
      val demo = access("support", "Violent Bot")
      val report = AccessReport(List(demo), List(UnreachableGuild("g1", "Antica Hunters")))
      DashboardAccess.entryFor(report, demoGuildId = "support") match {
        // Offered, not entered: it is a choice they can make, next to the note
        // saying the server they actually wanted has not answered.
        case DashboardEntry.Choose(options, missing) =>
          options.map(_.guildId) shouldBe List("support")
          missing.map(_.guildName) shouldBe List("Antica Hunters")
        case other => fail(s"expected a picker, got $other")
      }
    }

    "still send somebody with nothing at all to the empty state" in {
      DashboardAccess.entryFor(AccessReport.Empty, demoGuildId = "support") shouldBe DashboardEntry.Nowhere
    }

    // A picker that reorders itself between visits is one nobody builds muscle
    // memory for.
    "order the picker by name, case-insensitively, whatever order it was given" in {
      val unordered = List(access("g1", "zeta"), access("g2", "Alpha"), access("g3", "beta"))
      DashboardAccess.entryFor(AccessReport.of(unordered)) match {
        case DashboardEntry.Choose(options, _) => options.map(_.guildName) shouldBe List("Alpha", "beta", "zeta")
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

  /** The third state a pass can end in: not short of a named server, but short
   *  of knowing what there was to name. */
  "an access report" should {

    // The silent failure. `unreachable` covers a guild we knew to ask about and
    // did not hear from; nothing covered failing to find out what the other
    // bots run at all, and a report of that shape - nothing granted, nothing
    // unreachable - is indistinguishable from a visitor who genuinely has no
    // servers elsewhere. Read as complete it was cached as the whole answer.
    "not call itself complete when it never learned what the fleet runs" in {
      AccessReport.FleetUnknown.complete shouldBe false
      AccessReport.FleetUnknown.unreachable shouldBe empty
    }

    // The distinction the flag exists to keep. An empty answer is a real
    // answer and must stay one, or every visitor in a single-bot deployment
    // would be re-resolved every few seconds forever.
    "still call a genuinely empty answer complete" in {
      AccessReport.Empty.complete shouldBe true
    }

    // Resolution is two halves joined by `++` - what this bot knows, and what
    // it had to ask for - and the doubt belongs to the join. A local half that
    // resolved perfectly must not launder a remote half that failed.
    "carry the doubt through a join with a half that went fine" in {
      val local = AccessReport.of(List(access("g1", "Violent")))
      (local ++ AccessReport.FleetUnknown).complete shouldBe false
      (AccessReport.FleetUnknown ++ local).complete shouldBe false
      (local ++ AccessReport.Empty).complete shouldBe true
    }

    // Both kinds of shortfall at once, which is the ordinary case when the
    // fleet is having a bad minute: one bot named and silent, another not
    // heard of at all. The named one still has to reach the page.
    "keep a named server alongside a fleet it could not read" in {
      val named = AccessReport(Nil, List(UnreachableGuild("g2", "Ruckus")))
      val both = named ++ AccessReport.FleetUnknown
      both.unreachable.map(_.guildName) shouldBe List("Ruckus")
      both.complete shouldBe false
    }
  }
}
