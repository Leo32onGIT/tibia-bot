package com.tibiabot.wiki

import com.tibiabot.domain.BossEntry

/** Port over the Fandom (TibiaWiki) pages the bot scrapes. Constructing an
 *  implementation must do NO I/O — fetches happen only when these are called. */
trait WikiClient {
  /** The Dream Courts boss-of-the-day table. */
  def dreamScarBosses(): List[BossEntry]
  /** The ordered list of creature names. */
  def creatureNames(): List[String]
}
