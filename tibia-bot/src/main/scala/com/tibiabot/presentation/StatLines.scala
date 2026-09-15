package com.tibiabot.presentation

import java.util.Locale

/** The row shape every list in the statistics post is built from.
 *
 *  `{vocation} **[name](url)** {side icon} · *level* · {figure}` — vocation
 *  first, side icon after the name, which is the order
 *  [[com.tibiabot.TibiaBot]]'s online list already uses. Shared rather than
 *  written twice because the world embed and the PVP embed differ only in what
 *  the last cell holds.
 *
 *  Config-free, like everything else in this package that the statistics post
 *  touches: the side icon arrives as a string the caller resolved. */
object StatLines {

  /** The separator between cells. One character doing a lot of work, so it is
   *  named once rather than spelled out at every call site. */
  val Dot: String = " · "

  /** Vocation, linked name, and which side they are on.
   *
   *  Either icon can be missing — a vocation nothing recorded, or somebody this
   *  discord does not track — and the row closes up around whichever is absent
   *  rather than opening with a space or leaving a gap mid-row.
   */
  def who(vocation: String, displayName: String, sideIcon: String): String = {
    val name = s"**[$displayName](${Urls.charUrl(displayName)})**"
    List(Emojis.vocEmoji(vocation), name, sideIcon).filter(_.nonEmpty).mkString(" ")
  }

  /** A character's level, italic — the middle cell of most rows. */
  def level(value: Int): String = s"*$value*"

  /** Thousands separators, and no sign: the statistics post shows direction with
   *  the rising and falling experience icons instead, so a `+` here would say the
   *  same thing twice and a `-` would fight the icon. */
  def number(value: Long): String = String.format(Locale.ENGLISH, "%,d", Long.box(math.abs(value)))

  def cells(parts: String*): String = parts.filter(_.nonEmpty).mkString(Dot)
}
