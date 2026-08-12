package com.tibiabot.web

/** What a link crawler is shown when it asks for a page it cannot sign in to.
 *
 *  Every gated area answers an unauthenticated visitor with a redirect to
 *  Discord's OAuth screen, which is right for a person and wrong for a crawler:
 *  Discord's own unfurler follows both hops, lands on `discord.com`, and scrapes
 *  the tags it finds there — so a link to this bot's dashboard embedded as
 *  "Discord — Group Chat That's All Fun & Games", advertising Discord to a room
 *  full of people already using it.
 *
 *  A crawler cannot hold a session, so there is nothing to be gained by sending
 *  it round the loop. It gets this page instead: a real one, with the tags that
 *  describe what is actually behind the link.
 *
 *  Not cloaking. The page says the same thing the destination says — this is
 *  Violent Bot, sign in to reach it — so a reader who arrives here because the
 *  sniff misfired has lost nothing but a click, and the crawler is told the
 *  truth. It is only the *redirect* that is withheld, and only from something
 *  that could not have followed it usefully.
 */
object LinkPreview {

  /** The unfurlers worth answering, lower-cased and matched as substrings.
   *
   *  A deliberately short list of things that announce themselves. Anything not
   *  on it is treated as a person and redirected exactly as before, which is the
   *  failure we want: a crawler nobody listed embeds a little worse, where a
   *  person wrongly taken for one would be shown a page instead of being signed
   *  in. Guessing from "no Accept: text/html" or similar would invert that.
   */
  private val Crawlers: List[String] = List(
    "discordbot",           // the one this exists for
    "twitterbot",
    "facebookexternalhit",  // Facebook, Messenger, Instagram
    "slackbot",             // and Slack-ImgProxy for the image itself
    "slack-imgproxy",
    "telegrambot",
    "whatsapp",
    "linkedinbot",
    "redditbot",
    "skypeuripreview",
    "embedly",
    "bingpreview",
    "vkshare",
    "mastodon",
    "opengraph"
  )

  /** Whether this user agent is a link unfurler rather than somebody's browser.
   *
   *  Note that most of these introduce themselves inside a browser-shaped
   *  string — Discord's is `Mozilla/5.0 (compatible; Discordbot/2.0; …)` — so
   *  the test has to be a substring search and cannot be a prefix. */
  def isCrawler(userAgent: String): Boolean = {
    val agent = userAgent.toLowerCase
    Crawlers.exists(agent.contains)
  }

  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  /** The page itself.
   *
   *  `baseUrl` is where this deployment is actually reached, so the tags are
   *  right per environment rather than naming one domain in the source. The
   *  image is the landing site's avatar — the same file the public page
   *  advertises, so a link to the dashboard and a link to the site unfurl as
   *  recognisably the same bot.
   *
   *  `twitter:card` is not decoration: without it Discord renders the small
   *  thumbnail variant, and the avatar ends up as a corner icon rather than the
   *  picture on the embed.
   */
  def page(baseUrl: String, title: String, description: String): String = {
    val site = esc(baseUrl.stripSuffix("/"))
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<meta name="viewport" content="width=device-width, initial-scale=1">
       |<title>${esc(title)}</title>
       |<meta content="${esc(title)}" property="og:title">
       |<meta content="${esc(description)}" property="og:description">
       |<meta content="$site" property="og:url">
       |<meta content="$site/assets/img/avatar.png" property="og:image">
       |<meta content="48" property="og:image:width">
       |<meta content="48" property="og:image:height">
       |<meta content="website" property="og:type">
       |<meta content="Violent Bot" property="og:site_name">
       |<meta content="summary" name="twitter:card">
       |<meta content="${esc(description)}" name="description">
       |<style>
       |  body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
       |         background: #0b0d12; color: #e8e9ea; font-family: -apple-system, BlinkMacSystemFont,
       |         "Segoe UI", Roboto, sans-serif; text-align: center; padding: 24px; }
       |  a { color: #5b8cff; }
       |</style>
       |</head>
       |<body>
       |<main>
       |  <h1>${esc(title)}</h1>
       |  <p>${esc(description)}</p>
       |  <p>You need to sign in with Discord to see this page.</p>
       |  <p><a href="$site/dashboard">Sign in</a></p>
       |</main>
       |</body>
       |</html>""".stripMargin
  }

  val Title: String = "Violent Bot"
  val Description: String = "A Discord bot for the online MMORPG Tibia"

  /** The page as this bot serves it, ready to hand to [[DiscordAuth]]. */
  def default(baseUrl: String): String = page(baseUrl, Title, Description)
}
