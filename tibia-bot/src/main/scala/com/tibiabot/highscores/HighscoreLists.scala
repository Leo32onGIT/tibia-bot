package com.tibiabot.highscores

import com.tibiabot.tibiadata.{HighscoreCategory, HighscoreList, HighscoreSource, HighscoreVocation, Highscores}

/** Which highscore lists this bot actually fetches, and why each one is shaped
 *  the way it is.
 *
 *  The vocation filter is only worth a request where the unfiltered list is
 *  genuinely mixed — and measured against Antica, most are not. Sampling pages
 *  1, 10 and 20 of each category:
 *
 *  {{{
 *  swordfighting     100% Knight            axefighting     100% Knight
 *  clubfighting      100% Knight            distancefighting  100% Paladin
 *  fistfighting      100% Monk              shielding       57% Knight, 43% Paladin
 *  magiclevel        53% Druid, 47% Sorcerer
 *  experience        all five vocations
 *  }}}
 *
 *  So the four weapon skills and fist fighting are already single-vocation
 *  lists under `all`; filtering them would return the same characters for the
 *  cost of a tibia.com scrape from our own IP. Magic level is the one category
 *  where the filter genuinely buys coverage: knights, paladins and monks are
 *  shut out of the unfiltered list entirely, so each gets its own thousand.
 *
 *  Shielding and experience are taken unfiltered by decision rather than by
 *  measurement — both would go deeper split, and neither is worth the local
 *  load. The cost is real and worth stating: experience covers the top 1,000
 *  of a world rather than 1,000 per vocation, so on an old world it reaches
 *  roughly level 700 and no further.
 *
 *  Net effect: 12 lists, 240 pages per world per snapshot, of which only magic
 *  level's 100 touch our own instance. */
object HighscoreLists {

  /** Magic level, split five ways — the only lists needing our own instance. */
  val magicLevel: List[HighscoreList] =
    HighscoreVocation.vocations.map(HighscoreList(HighscoreCategory.MagicLevel, _))

  /** Categories whose unfiltered list is already the vocation that matters, or
   *  which we have chosen to take unfiltered. Weapon skills first, then
   *  shielding. */
  val unfilteredSkills: List[HighscoreList] = List(
    HighscoreCategory.SwordFighting,
    HighscoreCategory.AxeFighting,
    HighscoreCategory.ClubFighting,
    HighscoreCategory.DistanceFighting,
    HighscoreCategory.FistFighting,
    HighscoreCategory.Shielding
  ).map(HighscoreList(_, HighscoreVocation.All))

  /** Every list whose increases are announced in the Levels channel. */
  val skills: List[HighscoreList] = magicLevel ::: unfilteredSkills

  /** Recorded, never posted — banked for the Statistics channel. */
  val experience: HighscoreList =
    HighscoreList(HighscoreCategory.Experience, HighscoreVocation.All)

  val all: List[HighscoreList] = skills :+ experience

  /** Lists served by our own instance. The number to watch: these are the only
   *  ones that put tibia.com traffic on the VPS IP. */
  val local: List[HighscoreList] = all.filter(_.source == HighscoreSource.Local)

  val public: List[HighscoreList] = all.filter(_.source == HighscoreSource.Public)

  /** Page requests one world costs per snapshot, for load arithmetic. */
  def pagesPerWorld(lists: List[HighscoreList]): Int = lists.size * Highscores.MaxPages
}
