package com.tibiabot.presentation

/** Fits message text into Discord's 4096-char embed-description limit.
 *
 *  Used by the /boosted replies, which show a (possibly long) notification list
 *  optionally followed by a one-line command result. When the whole thing would
 *  overflow, the list body is cut at a line boundary, an overflow marker is added,
 *  and the command line is re-appended so it is never lost. Text that already fits
 *  is returned unchanged. Pure; see EmbedTextSpec.
 */
object EmbedText {
  private val overflowMarker = "\n\n*`...cannot display any more results`*"

  def fit(body: String, command: String = ""): String = {
    val tail = if (command.isEmpty) "" else s"\n\n$command"
    if ((body + tail).length >= 4096) {
      val cut = body.lastIndexOf('\n', 4090 - overflowMarker.length - command.length)
      body.substring(0, cut) + overflowMarker + tail
    } else {
      body + tail
    }
  }
}
