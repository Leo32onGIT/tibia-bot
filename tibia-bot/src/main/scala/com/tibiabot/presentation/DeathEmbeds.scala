package com.tibiabot.presentation

import net.dv8tion.jda.api.EmbedBuilder

/** Pure builder for the death-notification embed. The killer/colour/thumbnail
 *  DECISIONS stay in the stream; this just assembles the embed from them.
 *  Title = vocation emoji + linked name (extracted verbatim from
 *  TibiaBot.postToDiscordAndCleanUp). No Config, no JDA gateway. */
object DeathEmbeds {
  def build(charName: String, vocation: String, description: String, thumbnail: String, color: Int): EmbedBuilder = {
    val embed = new EmbedBuilder()
    embed.setTitle(
      s"${Emojis.vocEmoji(vocation)} $charName ${Emojis.vocEmoji(vocation)}",
      Urls.charUrl(charName)
    )
    embed.setDescription(description)
    embed.setThumbnail(thumbnail)
    embed.setColor(color)
    embed
  }
}
