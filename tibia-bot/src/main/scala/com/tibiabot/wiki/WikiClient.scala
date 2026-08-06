package com.tibiabot.wiki

import com.tibiabot.domain.DreamScarSnapshot

/** Port over the Fandom (TibiaWiki) pages the bot scrapes. Constructing an
 *  implementation must do NO I/O — fetches happen only when these are called. */
trait WikiClient {
  /** The Dream Courts boss-of-the-day table, with the day the page was
   *  rendered for so the caller can tell a stale cached render from a fresh one. */
  def dreamScarSnapshot(): DreamScarSnapshot
  /** The ordered list of creature names. */
  def creatureNames(): List[String]
}
