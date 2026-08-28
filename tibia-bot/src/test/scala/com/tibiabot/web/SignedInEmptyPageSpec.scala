package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The two screens a signed-in visitor with nothing to show can land on.
 *
 *  They were one screen until now, and that is precisely what made the bug: the
 *  page addressed the rare cause and offered the common cause's fix, so anybody
 *  in the rare state signed in again, came back to the same page, and signed in
 *  again. These tests exist to keep the two apart — most of all to keep the
 *  sign-in control off the branch where pressing it changes nothing.
 */
class SignedInEmptyPageSpec extends AnyWordSpec with Matchers {

  private def page(guildIdsKnown: Boolean) =
    RespawnDashboardRoute.signedInEmptyPage(guildIdsKnown)

  "the reconnect screen" should {

    // We hold no list of their servers, so there is genuinely nothing to say
    // except "let us ask Discord again".
    "be what somebody whose server list we never had sees" in {
      val (title, body) = page(guildIdsKnown = false)
      title shouldBe "Reconnect"
      body should include("Reconnect your Discord")
      body should include("Reconnect to see the respawn list.")
    }

    "send them to the sign-in, and say what it reads" in {
      val (_, body) = page(guildIdsKnown = false)
      body should include(s"""href="${RespawnDashboardRoute.LoginPath}"""")
      body should include("Continue with Discord")
      body should include("only reads which servers you're in")
    }

    // The wire is waiting to be joined, which is the whole difference between
    // this composition and the other one.
    "draw the connection as not yet made" in {
      val (_, body) = page(guildIdsKnown = false)
      body should include("""<span class="wire"></span>""")
      body should not include "wire done"
    }
  }

  "the no-lists screen" should {

    "be what somebody we checked and found nothing for sees" in {
      val (title, body) = page(guildIdsKnown = true)
      title shouldBe "No respawn lists"
      body should include("No respawn lists on your servers")
      body should include("none of the Discord servers you're in have a respawn list")
    }

    // The whole point of splitting the page. Offering a sign-in to somebody
    // whose sign-in already worked is the loop, and it must not come back.
    "not offer to sign them in again" in {
      val (_, body) = page(guildIdsKnown = true)
      body should not include RespawnDashboardRoute.LoginPath
      body should not include "Continue with Discord"
    }

    // A reload is the action that actually helps: they are nearly always waiting
    // on a moderator to let them into a channel, and the access behind this page
    // is cached for the best part of a minute.
    "offer a reload, and a way to get access that isn't a button" in {
      val (_, body) = page(guildIdsKnown = true)
      body should include("Check again")
      body should include("""href="/dashboard"""")
      body should include("Ask a moderator to let you see the respawn channels.")
    }

    "draw the connection as made, running into nothing" in {
      val (_, body) = page(guildIdsKnown = true)
      body should include("wire done")
      body should include("slot")
    }
  }

  "both screens" should {

    // They are a pair, and the page they are substituted into styles them as one.
    "share the card, the handshake and the button" in {
      val bodies = List(page(true)._2, page(false)._2)
      bodies.foreach { body =>
        body should include("""class="empty auth"""")
        body should include("""class="shake"""")
        body should include("btn btn-discord")
      }
    }

    // The old page led with three lines of eligibility rules that almost never
    // applied to the person reading them.
    "have dropped the eligibility lecture" in {
      val bodies = List(page(true)._2, page(false)._2)
      bodies.foreach { body =>
        body should not include "tracked world's channels"
        body should not include "Nothing to show yet"
        body should not include "Refresh sign-in"
      }
    }

    // Decoration, and read aloud it would be three meaningless images before the
    // sentence that matters.
    "keep the handshake out of the accessibility tree" in {
      val bodies = List(page(true)._2, page(false)._2)
      bodies.foreach(_ should include("""<div class="shake" aria-hidden="true">"""))
    }
  }
}
