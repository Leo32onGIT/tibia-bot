package com.tibiabot.presentation

import scala.collection.mutable.ListBuffer

/** Packs already-rendered lines into chunks that fit a message or an embed.
 *
 *  It once also paginated the /allies and /hunted lists into embeds; those are
 *  laid out as a card now — see panels.ListPanel — and this is what is left: the
 *  chunking the /admin panel's server list, the highscore announcements and the
 *  level-up flush each build their own messages from. */
object ListEmbeds {

  /** Accumulate lines (newline-joined) into description chunks of at most `limit`
   *  chars. The first chunk keeps the leading newline from the empty seed; each
   *  subsequent one begins with the line that overflowed the previous. Always
   *  returns at least one chunk. */
  def pack(values: List[String], limit: Int): List[String] = {
    val fields = ListBuffer.empty[String]
    var field = ""
    values.foreach { v =>
      val currentField = field + "\n" + v
      if (currentField.length <= limit) field = currentField
      else {
        fields += field
        field = v
      }
    }
    fields += field
    fields.toList
  }
}
