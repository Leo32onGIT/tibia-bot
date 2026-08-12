package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The masthead's "which Discord is this" chip, and the menu that leaves it.
 *
 *  Worth pinning down because the whole switcher is server-rendered: the page
 *  only opens and shuts what this produces, so a mistake here is a control that
 *  is drawn wrong rather than one that behaves wrong, and nothing on the page
 *  would notice.
 */
class ServerChipSpec extends AnyWordSpec with Matchers {

  private def access(id: String, name: String, tier: AccessTier = AccessTier.Member,
                     icon: Option[String] = None) =
    GuildAccess(id, name, tier, List("Antica"), icon)

  private val here = access("1", "Violent")
  private val other = access("2", "Refugia Social")

  "the server chip" should {

    "name the page after the server, and say which page it is" in {
      val html = RespawnDashboardRoute.serverChip(here, List(here))
      html should include("Violent")
      html should include("""<span class="brand-suffix">/dashboard</span>""")
    }

    "offer nothing to press when there is only one server" in {
      val html = RespawnDashboardRoute.serverChip(here, List(here))
      // A chevron here would promise a choice that does not exist.
      html should not include "chev"
      html should not include "sw-menu"
      html should not include "<button"
      html should include("""<span class="server">""")
    }

    "become a menu once there is somewhere to go" in {
      val html = RespawnDashboardRoute.serverChip(here, List(here, other))
      html should include("""id="server-switch"""")
      html should include("""id="server-switch-btn"""")
      html should include("sw-menu")
      html should include("chev")
    }

    "list every server the viewer can reach, each as a real link" in {
      val html = RespawnDashboardRoute.serverChip(here, List(here, other))
      html should include("""href="/dashboard/g/1"""")
      html should include("""href="/dashboard/g/2"""")
      html should include("Refugia Social")
    }

    "mark the one being looked at, and only that one" in {
      val html = RespawnDashboardRoute.serverChip(here, List(here, other))
      html should include("""<a class="sw-item on" href="/dashboard/g/1"""")
      html should include("""<a class="sw-item" href="/dashboard/g/2"""")
      // One tick, against the current server.
      html.sliding("sw-check".length).count(_ == "sw-check") shouldBe 1
    }

    "say what the viewer is on each server" in {
      // A board you moderate and a board you are a member of are different
      // places, and finding out which by landing on it costs a page load.
      val admin = access("1", "Violent", AccessTier.Admin)
      val html = RespawnDashboardRoute.serverChip(admin, List(admin, other))
      html should include("""<span class="tier tier-admin">admin</span>""")
      html should include("""<span class="tier tier-member">member</span>""")
    }

    "keep saying nothing about worlds" in {
      // Which worlds a guild tracks is what tells two entries apart on the full
      // picker, where there is room for it. Here it is noise on every row.
      RespawnDashboardRoute.serverChip(here, List(here, other)) should not include "Antica"
    }

    "wear each server's own face when it has one" in {
      val withIcon = access("1", "Violent", icon = Some("https://cdn.discordapp.com/icons/1/abc.png"))
      val html = RespawnDashboardRoute.serverChip(withIcon, List(withIcon, other))
      html should include("""<img class="sw-icon" src="https://cdn.discordapp.com/icons/1/abc.png"""")
    }

    "fall back to the glyph for a server that never set an icon" in {
      // Not a broken image, and not a gap that unaligns the names beside it.
      val html = RespawnDashboardRoute.serverChip(here, List(here, other))
      html should include("sw-glyph")
      html should not include "<img"
    }

    "escape an icon url rather than letting it break out of the attribute" in {
      val nasty = access("9", "Odd", icon = Some("""x" onerror="alert(1)"""))
      val html = RespawnDashboardRoute.serverChip(nasty, List(nasty, other))
      html should not include """onerror="alert(1)""""
      html should include("&quot;")
    }

    "escape a server name rather than letting it write markup" in {
      val nasty = access("3", """<img src=x onerror="alert(1)">""")
      val html = RespawnDashboardRoute.serverChip(nasty, List(nasty, other))
      html should not include "<img"
      html should include("&lt;img")
    }
  }
}
