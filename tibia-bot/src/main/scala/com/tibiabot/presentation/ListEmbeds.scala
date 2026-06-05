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

  /** Accumulate lines (newline-joined) into description chunks of at most `limit`
   *  chars. The first chunk keeps the leading newline from the empty seed; each
   *  subsequent one begins with the line that overflowed the previous. Always
   *  returns at least one chunk. Shared by [[paginate]] and the /admin guild
   *  list, which build embeds from the chunks differently. */
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
