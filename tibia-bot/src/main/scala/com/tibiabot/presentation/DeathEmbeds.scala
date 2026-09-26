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

  // Death-embed allegiance colours, mirroring the embedColor assignments in the
  // death-processing block: neutral covers the default plus its situational
  // variants; enemy and ally each have a single colour.
  private val neutralDeathColors = Set(3092790, 14869218, 4540237, 14397256)
  private val enemyDeathColor = 36941
  private val allyDeathColor = 13773097

  /** Whether a death embed should be posted, given its allegiance colour and the
   *  channel's per-category show flags ("false" suppresses that category).
   *  Colours outside the three allegiance groups (e.g. the purple "notable"
   *  case) are always shown. */
  def shouldShow(embedColor: Int, showNeutralDeaths: String, showAlliesDeaths: String, showEnemiesDeaths: String): Boolean =
    if (neutralDeathColors.contains(embedColor)) showNeutralDeaths != "false"
    else if (embedColor == enemyDeathColor) showEnemiesDeaths != "false"
    else if (embedColor == allyDeathColor) showAlliesDeaths != "false"
    else true
}
