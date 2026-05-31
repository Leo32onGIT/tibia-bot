package com.tibiabot.presentation

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** Pure builder for the boosted-boss/creature embed: a thumbnail, the fixed
 *  brand colour and a description. The embed has no title by design. */
object BoostedEmbeds {
  def create(thumbnail: String, embedText: String): MessageEmbed = {
    val embed = new EmbedBuilder()
    embed.setThumbnail(thumbnail)
    embed.setColor(Embeds.BrandColor)
    embed.setDescription(embedText)
    embed.build()
  }
}
