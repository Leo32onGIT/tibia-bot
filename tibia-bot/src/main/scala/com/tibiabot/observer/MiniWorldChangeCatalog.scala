package com.tibiabot.observer

/** Which TibiaWiki page a mini world change links to.
 *
 *  The Observer feed carries only a title and a body, and its titles don't always
 *  match the wiki's own names — it splits some changes by location (one Spirit Gate
 *  per region, for instance) — so a change is matched on a keyword its title
 *  contains rather than on the exact name. A title nothing here recognises links to
 *  the wiki's list of every change instead, never to a guessed page that might not
 *  exist. */
object MiniWorldChangeCatalog {

  private val Wiki = "https://tibia.fandom.com/wiki/"

  /** The wiki's list of every mini world change — the link for an unknown title. */
  val ListPage: String = s"${Wiki}Mini_World_Changes"

  /** Lower-cased keyword → the change's page, from TibiaWiki's Category:Mini World
   *  Changes. Every page there is named `<Name> Mini World Change`. */
  private val pages: List[(String, String)] = List(
    "bank robbery"        -> "Bank_Robbery",
    "bored"               -> "Bored",
    "chakoya"             -> "Chakoya_Iceberg",
    "chyllfroest"         -> "Chyllfroest",
    "devovorga"           -> "Devovorga's_Essence",
    "down the drain"      -> "Down_the_Drain",
    "fire from the earth" -> "Fire_from_the_Earth",
    "fury gate"           -> "Fury_Gates",
    "grimvale"            -> "Grimvale",
    "hive outpost"        -> "Hive_Outpost",
    "jungle camp"         -> "Jungle_Camp",
    "kingsday"            -> "Kingsday",
    "lumberjack"          -> "Lumberjack",
    "nightmare isle"      -> "Nightmare_Isles",
    "nomad"               -> "Nomads",
    "noodles"             -> "Noodles_is_Gone",
    "oriental trader"     -> "Oriental_Trader",
    "poacher"             -> "Poacher_Caves",
    "river runs deep"     -> "River_Runs_Deep",
    "spider nest"         -> "Spider_Nest",
    "spirit g"            -> "Spirit_Grounds",
    "stampede"            -> "Stampede",
    "thawing"             -> "Thawing",
    "warpath"             -> "Warpath"
  )

  def wikiUrl(title: String): String = {
    val t = title.toLowerCase
    pages.collectFirst { case (keyword, page) if t.contains(keyword) => s"$Wiki${page}_Mini_World_Change" }
      .getOrElse(ListPage)
  }
}
