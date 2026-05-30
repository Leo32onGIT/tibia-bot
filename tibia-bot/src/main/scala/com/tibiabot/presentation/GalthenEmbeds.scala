package com.tibiabot.presentation

/** Helpers for the Galthen's Satchel cooldown embeds.
 *
 *  NOTE: only the description-truncation logic is shared verbatim across the
 *  four Galthen call sites in BotListener, so that is what is extracted here.
 *  The embed construction itself has DIVERGED between call sites (different
 *  satchel emoji — `Config.satchelEmoji` vs a hardcoded id — tag formatting,
 *  colours, and "message you when..." text), so unifying it is deliberately
 *  deferred to avoid changing behaviour.
 */
object GalthenEmbeds {

  /** Join `lines` with newlines and cap the result at `limit` characters,
   *  cutting back to the last whole line so an entry is never split mid-way.
   *  Mirrors the repeated 4050-character truncation block in BotListener. */
  def truncate(lines: Seq[String], limit: Int = 4050): String = {
    val joined = lines.mkString("\n")
    if (joined.length > limit) {
      val truncated = joined.substring(0, limit)
      val lastNewLine = truncated.lastIndexOf("\n")
      if (lastNewLine >= 0) truncated.substring(0, lastNewLine) else truncated
    } else joined
  }
}
