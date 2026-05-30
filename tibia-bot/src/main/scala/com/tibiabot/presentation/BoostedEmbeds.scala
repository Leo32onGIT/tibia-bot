package com.tibiabot.presentation

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** Pure builder for the boosted-boss/creature embed. Moved verbatim from
 *  BotApp.createBoostedEmbed (callers still pass Config emoji strings as args;
 *  this function itself is Config-free). */
object BoostedEmbeds {
  def create(name: String, emoji: String, wikiUrl: String, thumbnail: String, embedText: String): MessageEmbed = {
    val embed = new EmbedBuilder()
    //embed.setTitle(s"$emoji $name $emoji", wikiUrl)
    embed.setThumbnail(thumbnail)
    embed.setColor(3092790)
    embed.setDescription(embedText)
    embed.build()
  }
}
