package com.tibiabot.presentation

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

import scala.collection.mutable.ListBuffer

/** Paginates already-rendered lines into Discord embeds for the /allies and
 *  /hunted list output, where the players and guilds sections each ran the same
 *  pack-into-<=4096-char-embeds loop (only the first embed carrying the section
 *  thumbnail). Extracted verbatim from BotApp.listAlliesAndHuntedPlayers. */
object ListEmbeds {

  /** Pack lines (newline-joined) into embeds whose descriptions stay within
   *  `limit` characters, all sharing `color` with only the first carrying
   *  `thumbnail`. Always returns at least one embed (an empty input yields a
   *  single empty-description embed, matching the original). */
  def paginate(values: List[String], thumbnail: String, color: Int, limit: Int = 4096): List[MessageEmbed] =
    pack(values, limit).zipWithIndex.map { case (description, index) =>
      val embed = new EmbedBuilder()
      embed.setDescription(description)
      embed.setColor(color)
      if (index == 0) embed.setThumbnail(thumbnail)
      embed.build()
    }

  /** Group already-built embeds into the messages they can actually be sent in.
   *
   *  Discord bounds a message two ways at once, and paginating by description
   *  length only satisfies one of them: at most ten embeds, and — the one that
   *  bites — at most `MessageEmbed.EMBED_MAX_LENGTH_BOT` characters *summed
   *  across all of them*. Since [[paginate]] fills each description to 4096, two
   *  full pages in one message are already over, and Discord rejects the whole
   *  send with MAX_EMBED_SIZE_EXCEEDED rather than trimming.
   *
   *  Packed greedily, in order, so a list reads in the order it was built.
   *  An embed that would not fit a message even on its own is given one anyway:
   *  Discord will refuse it, but dropping it silently or looping forever are both
   *  worse than one failed send that says so in the log.
   */
  def batches(embeds: List[MessageEmbed],
              maxPerMessage: Int = 10,
              maxLength: Int = MessageEmbed.EMBED_MAX_LENGTH_BOT): List[List[MessageEmbed]] = {
    val out = ListBuffer.empty[List[MessageEmbed]]
    val current = ListBuffer.empty[MessageEmbed]
    var length = 0
    embeds.foreach { embed =>
      val size = embed.getLength
      val wouldOverflow = current.nonEmpty && (length + size > maxLength || current.size >= maxPerMessage)
      if (wouldOverflow) {
        out += current.toList
        current.clear()
        length = 0
      }
      current += embed
      length += size
    }
    if (current.nonEmpty) out += current.toList
    out.toList
  }

  /** Accumulate lines (newline-joined) into description chunks of at most `limit`
   *  chars. The first chunk keeps the leading newline from the empty seed; each
   *  subsequent one begins with the line that overflowed the previous. Always
   *  returns at least one chunk. Shared by [[paginate]] and other chunking call
   *  sites (the /admin guild list, the level-up message flush), which each
   *  build their own embeds/messages from the chunks. */
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
