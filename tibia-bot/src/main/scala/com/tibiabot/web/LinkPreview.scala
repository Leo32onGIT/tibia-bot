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
 *  One page per gated area rather than one for the site, because the areas are
 *  not the same thing to the person being shown the card — a link to the respawn
 *  board and a link to the bot's own monitoring both used to unfurl as the bot's
 *  name and a sentence about Tibia. See [[Area]] and [[forPath]].
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

  /** One gated area, as a crawler should describe it.
   *
   *  `path` is both what a request is matched against and where the visible
   *  page's sign-in link points, so an area cannot be described as one thing and
   *  send a reader to another.
   *
   *  `themeColor` is the one tag here that is not words: Discord paints the
   *  stripe down the left of the embed with it, which is the only part of the
   *  card that separates one area from another before anything is read.
   */
  final case class Area(path: String, title: String, description: String, themeColor: String)

  /** The member-facing dashboard — the link anybody in a guild might be given,
   *  and the one the board post itself carries. Titled the way the monitoring
   *  area has always titled itself, `<what it is> /<where it lives>`, so the two
   *  read as the same bot rather than as two products. */
  val Dashboard: Area = Area(
    path = "/dashboard",
    title = "Respawn Claims /dashboard",
    description =
      "Claim, book and leave respawns from the browser — this allows you to book much further in advance.\n" +
        "Sign in with Discord to open it.",
    // The accent the dashboard is built in, so the stripe on the embed and the
    // buttons behind the link are the same blue.
    themeColor = "#5b8cff")

  /** The owner's monitoring area. Its description says what is behind the link
   *  and stops there: who may open it is settled by the sign-in, and saying so
   *  on a card that anybody can unfurl only advertises the door. */
  val Status: Area = Area(
    path = "/status",
    title = "Violent Bot /status",
    description = "Stream health, queue depths and recent events for the bot itself.",
    // The purple the dashboard already uses for its own privileged tier.
    themeColor = "#a78bfa")

  private val Areas: List[Area] = List(Dashboard, Status)

  /** Which area a matched request path belongs to.
   *
   *  A plain prefix test, because the mounts are siblings rather than nested.
   *  Anything unrecognised reads as the dashboard: a path this does not know is
   *  a mount somebody added without describing it, and the primary area's page
   *  is a truthful answer for it where an error would not be. */
  def areaFor(path: String): Area =
    Areas.find(area => path.startsWith(area.path)).getOrElse(Dashboard)

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
   *
   *  The description carries newlines through into the embed, where Discord
   *  honours them. It does not render markdown there — unlike the embeds the bot
   *  builds itself — so this is plain text with line breaks and nothing else.
   */
  def page(baseUrl: String, area: Area): String = {
    val site = esc(baseUrl.stripSuffix("/"))
    val title = esc(area.title)
    val description = esc(area.description)
    val body = area.description.split('\n').map(line => s"  <p>${esc(line)}</p>").mkString("\n")
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<meta name="viewport" content="width=device-width, initial-scale=1">
       |<title>$title</title>
       |<meta content="$title" property="og:title">
       |<meta content="$description" property="og:description">
       |<meta content="$site${esc(area.path)}" property="og:url">
       |<meta content="$site/assets/img/avatar.png" property="og:image">
       |<meta content="48" property="og:image:width">
       |<meta content="48" property="og:image:height">
       |<meta content="website" property="og:type">
       |<meta content="Violent Bot" property="og:site_name">
       |<meta content="summary" name="twitter:card">
       |<meta content="${esc(area.themeColor)}" name="theme-color">
       |<meta content="$description" name="description">
       |<style>
       |  body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
       |         background: #0b0d12; color: #e8e9ea; font-family: -apple-system, BlinkMacSystemFont,
       |         "Segoe UI", Roboto, sans-serif; text-align: center; padding: 24px; }
       |  a { color: ${esc(area.themeColor)}; }
       |</style>
       |</head>
       |<body>
       |<main>
       |  <h1>$title</h1>
       |$body
       |  <p><a href="$site${esc(area.path)}">Sign in with Discord</a></p>
       |</main>
       |</body>
       |</html>""".stripMargin
  }

  /** Every area's page, rendered once, picked by the path that was asked for.
   *
   *  Built up front rather than per request. A crawler's visit then costs a
   *  lookup, and nothing about answering one can fail at the one moment when the
   *  only thing watching would report the failure as this link's description.
   */
  def forPath(baseUrl: String): String => String = {
    val pages = Areas.map(area => area.path -> page(baseUrl, area)).toMap
    path => pages.getOrElse(areaFor(path).path, page(baseUrl, Dashboard))
  }

  /** The primary area's page on its own, for callers with no path to offer. */
  def default(baseUrl: String): String = page(baseUrl, Dashboard)
}
