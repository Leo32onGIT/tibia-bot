package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The Claim button on the dashboard wears the guild's configured `daily-emoji`,
 *  the same one the Discord button wears. That setting is free-form config, and
 *  what comes out of it is written straight into a JS string literal on the
 *  page — so what it will and will not accept is worth pinning down.
 */
class ClaimEmojiSpec extends AnyWordSpec with Matchers {

  private def url(formatted: String) = RespawnDashboardRoute.emojiImageUrl(formatted)

  "emojiImageUrl" should {

    "resolve the configured daily emoji to its CDN image" in {
      url("<:daily:1133349016814485584>") shouldBe
        Some("https://cdn.discordapp.com/emojis/1133349016814485584.png")
    }

    "ask for a gif when the emoji is animated" in {
      url("<a:dreamscar:1504728980010438717>") shouldBe
        Some("https://cdn.discordapp.com/emojis/1504728980010438717.gif")
    }

    "tolerate surrounding whitespace, which a config file invites" in {
      url("  <:daily:1133349016814485584>  ").isDefined shouldBe true
    }

    // Each of these gives None rather than a URL, so the button goes without a
    // face instead of the page carrying a broken image.
    "give nothing for a unicode emoji" in {
      url("📅") shouldBe None
    }

    "give nothing for an empty or absent setting" in {
      url("") shouldBe None
      url("   ") shouldBe None
    }

    "give nothing for a malformed emoji" in {
      url("<:daily:>") shouldBe None
      url("<:1133349016814485584>") shouldBe None
      url("daily:1133349016814485584") shouldBe None
    }

    // The value lands inside quotes in a <script>, so anything that could close
    // that string, or the tag around it, must not survive the parse. The id is
    // matched as digits and the name as word characters, so none of this does.
    "refuse anything that could break out of the JS string it lands in" in {
      url("<:daily:1'+alert(1)+'>") shouldBe None
      url("<:daily:1</script><script>alert(1)</script>>") shouldBe None
      url("<:da\"ily:123>") shouldBe None
      url("<:daily:123><:daily:456>") shouldBe None
    }

    "refuse an id long enough to be something other than an id" in {
      url(s"<:daily:${"1" * 40}>") shouldBe None
    }
  }
}
