package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The two pages a partly-resolved sign-in can produce.
 *
 *  Both are server-rendered from a single substitution into `respawn.html`, and
 *  both only ever appear when something has already gone wrong — which is
 *  exactly the combination nobody clicks through by hand. Pinning the markup
 *  here is the only thing standing between a failed resolution and a page that
 *  says nothing about it.
 */
class PartialAccessPageSpec extends AnyWordSpec with Matchers {

  private def access(id: String, name: String) =
    GuildAccess(id, name, AccessTier.Member, List("Antica"))

  private val missing = List(UnreachableGuild("g2", "Refugia Social"))

  "a picker that knows it is short" should {

    "say so, and name what is missing" in {
      val html = RespawnDashboardRoute.pickerBody(List(access("g1", "Violent")), missing)
      html should include("didn't answer")
      html should include("Refugia Social")
      html should include("Reload in a moment")
      html should include("""class="hint warn"""")
    }

    // The server that did not answer is named, not offered: we never found out
    // whether it is theirs to open.
    "not offer the missing server as somewhere to go" in {
      val html = RespawnDashboardRoute.pickerBody(List(access("g1", "Violent")), missing)
      html should include("""href="/dashboard/g/g1"""")
      html should not include """href="/dashboard/g/g2""""
    }

    "say nothing about missing servers when nothing is missing" in {
      val html = RespawnDashboardRoute.pickerBody(List(access("g1", "Violent"), access("g2", "Allies")))
      html should not include "didn't answer"
      html should not include "hint warn"
      html should include("more than one")
    }

    // A one-entry picker is an odd screen, and the note is the whole reason it
    // is worth showing at all — so it must not also claim there is more than one.
    "not claim there is a choice when the list has one entry" in {
      val html = RespawnDashboardRoute.pickerBody(List(access("g1", "Violent")), missing)
      html should not include "more than one"
    }

    "escape a server name rather than letting it write markup" in {
      val html = RespawnDashboardRoute.pickerBody(
        Nil, List(UnreachableGuild("g2", """<img src=x onerror="alert(1)">""")))
      html should not include "<img"
      html should include("&lt;img")
    }

    // A guild whose name was never known is still worth naming by id: it is
    // something to search a log for, where a blank is nothing at all.
    "fall back to the id when the roster never carried a name" in {
      val html = RespawnDashboardRoute.pickerBody(Nil, List(UnreachableGuild("881", "")))
      html should include("881")
    }
  }

  "the try-again page" should {

    // Never the empty state. That page tells somebody to go and set the bot up,
    // which is the wrong advice for a visitor whose servers are all fine and
    // merely did not reply — and advice they would have acted on, since nothing
    // on it suggests reloading.
    "offer a retry rather than telling them to set the bot up" in {
      val html = RespawnDashboardRoute.unreachableBody(missing)
      html should include("""href="/dashboard"""")
      html should include("Refugia Social")
      html should not include "needs a Discord server where Violent Bot is set up"
    }

    "say plainly that this is not about them" in {
      val html = RespawnDashboardRoute.unreachableBody(missing)
      html should include("Nothing has changed")
    }
  }
}
