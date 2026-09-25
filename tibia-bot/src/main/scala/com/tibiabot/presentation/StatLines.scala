package com.tibiabot.presentation

import java.util.Locale

/** The row shape every list in the statistics post is built from.
 *
 *  `{vocation} **level** — **[name](url)** {side icon} · {figure}` — the online
 *  list's own convention for a character ([[com.tibiabot.TibiaBot]]), so a name
 *  reads the same in both channels, with the row's figure after it. Shared rather
 *  than written twice because the board and the PVP card differ only in
 *  what the last cell holds.
 *
 *  Config-free, like everything else in this package that the statistics post
 *  touches: the side icon arrives as a string the caller resolved. */
object StatLines {

  /** The separator between cells. One character doing a lot of work, so it is
   *  named once rather than spelled out at every call site. */
  val Dot: String = " · "

  /** Vocation and level, then the linked name and which side they are on.
   *
   *  Any part can be missing — a vocation nothing recorded, a level nobody saw,
   *  or somebody this discord does not track — and the row closes up around
   *  whichever is absent rather than opening with a space or leaving a gap
   *  mid-row. Without a level the dash goes too: it only ever separates the
   *  level from the name.
   */
  def who(vocation: String, displayName: String, sideIcon: String, level: Option[Int]): String = {
    val name = s"**[$displayName](${Urls.charUrl(displayName)})**"
    List(Emojis.vocEmoji(vocation), level.fold("")(l => s"**$l** —"), name, sideIcon)
      .filter(_.nonEmpty).mkString(" ")
  }

  /** Thousands separators, and no sign: the statistics post shows direction with
   *  the rising and falling experience icons instead, so a `+` here would say the
   *  same thing twice and a `-` would fight the icon. */
  def number(value: Long): String = String.format(Locale.ENGLISH, "%,d", Long.box(math.abs(value)))

  def cells(parts: String*): String = parts.filter(_.nonEmpty).mkString(Dot)
}
