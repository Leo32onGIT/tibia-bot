package com.tibiabot.web

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The page a link unfurler is shown instead of Discord's sign-in screen. */
class LinkPreviewSpec extends AnyFunSuite with Matchers {

  test("the unfurlers that matter are recognised inside a browser-shaped string") {
    // None of them announce themselves at the front: Discord's is a Mozilla
    // string with its name in the middle, which is why this is a substring test.
    LinkPreview.isCrawler("Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)") shouldBe true
    LinkPreview.isCrawler("Mozilla/5.0 (compatible; Twitterbot/1.0)") shouldBe true
    LinkPreview.isCrawler("facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)") shouldBe true
    LinkPreview.isCrawler("Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)") shouldBe true
    LinkPreview.isCrawler("WhatsApp/2.19.81 A") shouldBe true
  }

  test("a browser is not a crawler, and neither is an empty agent") {
    // Getting this wrong the other way costs somebody their sign-in, so the
    // ordinary strings are pinned.
    LinkPreview.isCrawler(
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36") shouldBe false
    LinkPreview.isCrawler(
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1") shouldBe false
    LinkPreview.isCrawler("") shouldBe false
  }

  test("the tags say what is behind the link") {
    val page = LinkPreview.default("https://violentbot.xyz")
    page should include("""<meta content="Respawn Claims" property="og:title">""")
    page should include("book much further in advance")
    page should include("Sign in with Discord to open it.")
    // The link's own area, not the site root: og:url is what the embed's title
    // is hyperlinked to.
    page should include("""<meta content="https://violentbot.xyz/dashboard" property="og:url">""")
    page should include("""<meta content="https://violentbot.xyz/assets/img/avatar.png" property="og:image">""")
    page should include("""<meta content="48" property="og:image:width">""")
    page should include("""<meta content="48" property="og:image:height">""")
    // Without this Discord draws the small thumbnail variant and the avatar ends
    // up as a corner icon rather than the picture on the embed.
    page should include("""name="twitter:card"""")
  }

  test("each area is coloured and titled as itself") {
    // theme-color is what Discord paints the embed's left stripe with. Both areas
    // currently carry the same purple, so it is the title rather than the stripe
    // that tells them apart; the values are pinned so a change to either is seen.
    val dashboard = LinkPreview.page("https://violentbot.xyz", LinkPreview.Dashboard)
    dashboard should include("""<meta content="#a78bfa" name="theme-color">""")

    val status = LinkPreview.page("https://violentbot.xyz", LinkPreview.Status)
    status should include("""<meta content="Admin Panel" property="og:title">""")
    status should include("""<meta content="#a78bfa" name="theme-color">""")
    status should include("""<meta content="https://violentbot.xyz/status" property="og:url">""")
    // Who may open it is settled by the sign-in; the card anybody can unfurl
    // does not announce it.
    status should not include "Owner only"
  }

  test("the page answered is the one for the path that was asked for") {
    val pages = LinkPreview.forPath("https://violentbot.xyz")
    pages("/status") should include("Admin Panel")
    pages("/status/thing") should include("Admin Panel")
    pages("/dashboard") should include("Respawn Claims")
    pages("/dashboard/g/8814") should include("Respawn Claims")
    // A mount nobody described still gets a truthful page rather than an error.
    pages("/somewhere-else") should include("Respawn Claims")
  }

  test("the origin is whatever this deployment answers on, not a domain in the source") {
    val page = LinkPreview.default("http://localhost:8081/")
    page should include("""<meta content="http://localhost:8081/dashboard" property="og:url">""")
    // The trailing slash is not doubled into the image path.
    page should include("http://localhost:8081/assets/img/avatar.png")
    page should not include "localhost:8081//"
  }

  test("nothing interpolated can break out of the markup") {
    // The origin is configuration rather than user input, but it lands in an
    // attribute either way and a mangled one should stay inside its quotes.
    val page = LinkPreview.page("""https://x"onload="alert(1)""",
      LinkPreview.Area("""/p"q""", """a"b<c""", "d&e", """#fff"onload="alert(1)"""))
    page should not include """content="a"b"""
    page should include("&quot;")
    page should include("&lt;c")
    page should include("d&amp;e")
  }

  test("a person who lands here is told what to do about it") {
    // The sniff can misfire, and a page that only exists for machines would
    // leave them staring at a blank one.
    val page = LinkPreview.default("https://violentbot.xyz")
    page should include("Sign in with Discord")
    page should include("""href="https://violentbot.xyz/dashboard"""")
    // Both lines of the description reach the visible page, not just the tag.
    page should include("<p>Sign in with Discord to open it.</p>")
  }
}
