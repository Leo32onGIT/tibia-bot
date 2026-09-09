package com.tibiabot
package tibiadata

import java.net.URLEncoder

/** A highscore category, by the slug the endpoint's path expects.
 *
 *  Only the categories this bot asks for. The endpoint accepts a dozen more
 *  (achievements, charm points, boss points, drome, loyalty, fishing, ...), but
 *  none of them is a skill advancement and each one costs another 20 pages per
 *  world per snapshot, so they are deliberately absent rather than unlisted. */
sealed abstract class HighscoreCategory(val slug: String, val label: String) {

  /** Whether an increase in this category is announced in the Levels channel.
   *
   *  Experience is recorded and never posted: level-ups already reach that
   *  channel within about a minute from the online-list comparison in
   *  [[com.tibiabot.TibiaBot]], and a highscores-derived one would be the same
   *  event, duplicated, an hour late. */
  def postsAdvances: Boolean = this != HighscoreCategory.Experience

  /** How an advance in this category reads in a Levels post.
   *
   *  Magic level already carries "level" in its name, so the weapon skills get
   *  the word appended and it does not — "advanced to magic level 108", not
   *  "advanced to magic level level 108". */
  def advancement(score: Long): String = this match {
    case HighscoreCategory.MagicLevel => s"magic level **$score**"
    case HighscoreCategory.Experience => s"**$score** experience"
    case other                        => s"${other.label} level **$score**"
  }
}

object HighscoreCategory {
  case object Experience extends HighscoreCategory("experience", "experience")
  case object MagicLevel extends HighscoreCategory("magiclevel", "magic level")
  case object Shielding extends HighscoreCategory("shielding", "shielding")
  case object SwordFighting extends HighscoreCategory("swordfighting", "sword fighting")
  case object AxeFighting extends HighscoreCategory("axefighting", "axe fighting")
  case object ClubFighting extends HighscoreCategory("clubfighting", "club fighting")
  case object DistanceFighting extends HighscoreCategory("distancefighting", "distance fighting")
  case object FistFighting extends HighscoreCategory("fistfighting", "fist fighting")

  val all: List[HighscoreCategory] = List(
    Experience, MagicLevel, Shielding, SwordFighting,
    AxeFighting, ClubFighting, DistanceFighting, FistFighting
  )

  /** The category a stored slug names. None for anything this bot no longer
   *  fetches, so a row filed by an older build cannot crash the feed reading
   *  it — it is simply not announced. */
  def fromSlug(slug: String): Option[HighscoreCategory] = all.find(_.slug == slug)
}

/** The vocation filter, by path slug.
 *
 *  `All` is the only value the public API accepts — anything else is refused
 *  with HTTP 400 / error 9002 unless the instance runs with
 *  `TIBIADATA_RESTRICTION_MODE` off. That single fact is what decides which
 *  host a list is fetched from; see [[HighscoreList.source]]. */
sealed abstract class HighscoreVocation(val slug: String)

object HighscoreVocation {
  case object All extends HighscoreVocation("all")
  case object Druids extends HighscoreVocation("druids")
  case object Knights extends HighscoreVocation("knights")
  case object Paladins extends HighscoreVocation("paladins")
  case object Sorcerers extends HighscoreVocation("sorcerers")
  case object Monks extends HighscoreVocation("monks")

  /** Every real vocation, i.e. every value that actually splits a mixed list.
   *  Excludes `All`, and excludes the endpoint's `none` (Rookgaard characters
   *  with no vocation), who hold no skill worth announcing. */
  val vocations: List[HighscoreVocation] = List(Druids, Sorcerers, Paladins, Knights, Monks)
}

/** Which TibiaData instance serves a list. */
sealed trait HighscoreSource
object HighscoreSource {
  /** api.tibiadata.com — Kong-cached, shared with everyone, and it costs
   *  tibia.com nothing extra from us. The default wherever it will do. */
  case object Public extends HighscoreSource

  /** Our own instance, which reaches tibia.com from the VPS IP directly. The
   *  only thing it buys is the vocation filter, so nothing goes here that does
   *  not need one. */
  case object Local extends HighscoreSource
}

/** One highscore list: a category read through one vocation filter.
 *
 *  A world's list is fetched a page at a time; the list itself is the same on
 *  every world, so this is the unit the catalogue enumerates and the scheduler
 *  paces. */
final case class HighscoreList(category: HighscoreCategory, vocation: HighscoreVocation) {

  /** Public unless the vocation filter forces our own instance.
   *
   *  Derived rather than declared on purpose: needing the filter is the only
   *  reason a list may cost us a tibia.com scrape, and deriving it makes the
   *  wrong answer unrepresentable. A public-side outage is a fallback for the
   *  fetcher to decide, not something to encode here. */
  def source: HighscoreSource =
    if (vocation == HighscoreVocation.All) HighscoreSource.Public else HighscoreSource.Local

  /** Whether an increase in this list is announced — a fact about the category,
   *  not about the vocation filter it was read through. */
  def postsAdvances: Boolean = category.postsAdvances

  /** The endpoint path for one page of this list on one world.
   *
   *  Page is a path segment, not a query parameter — `?page=` is accepted and
   *  silently ignored, returning page 1 every time. */
  def path(world: String, page: Int): String = {
    require(page >= 1 && page <= Highscores.MaxPages, s"page out of range: $page")
    val encodedWorld = URLEncoder.encode(world, "UTF-8").replaceAll("\\+", "%20")
    s"/v4/highscores/$encodedWorld/${category.slug}/${vocation.slug}/$page"
  }

  override def toString: String = s"${category.slug}/${vocation.slug}"
}

/** Limits of the endpoint itself, measured rather than documented. */
object Highscores {
  /** Rows per page. */
  val PageSize: Int = 50

  /** The most pages a list can have. Page 21 is refused with HTTP 400 /
   *  error 11008, and so is any page past a short list's end — see below. */
  val MaxPages: Int = 20

  /** The ceiling on how deep this feature can ever see, not the size of every
   *  list. tibia.com reports the full 20 pages / 1000 records for every
   *  unfiltered list tried, small and new worlds included, because the
   *  unfiltered list holds the whole world. A vocation filter can run out
   *  first: Penumbra's `magiclevel/monks` reported 11 pages and 517 records in
   *  September 2026, a young world and a young vocation. Read `total_pages`
   *  off the response rather than assuming this. */
  val MaxRecords: Int = PageSize * MaxPages

  /** Every page of a list, in order. */
  val pages: List[Int] = (1 to MaxPages).toList
}
