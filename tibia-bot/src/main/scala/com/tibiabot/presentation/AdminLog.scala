package com.tibiabot.presentation

import com.tibiabot.Config
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel

/** Posts an audit embed to a guild's command-log channel, if it exists and is
 *  writable. Centralises the block repeated by every command that audits itself
 *  (title/colour are fixed per kind; description/thumbnail vary).
 *  Extracted verbatim from BotApp.postAdminLog. */
object AdminLog {

  /** Something somebody ran. Brand-coloured, headed as a command. */
  def post(adminChannel: TextChannel, description: String, thumbnail: String): Unit =
    send(adminChannel, ":gear: a command was run:", description, thumbnail, Embeds.BrandColor)

  /** Something the bot decided on its own — no command behind it.
   *
   *  Its own heading and the bot's yellow, because a channel that is otherwise a
   *  log of what people did should not quietly imply somebody did this one. The
   *  caller supplies the heading, since what the bot did is the thing worth
   *  reading first.
   */
  def automatic(adminChannel: TextChannel, title: String, description: String, thumbnail: String): Unit =
    send(adminChannel, title, description, thumbnail, Embeds.AutomaticColor)

  private def send(adminChannel: TextChannel, title: String, description: String,
                   thumbnail: String, color: Int): Unit =
    if (adminChannel != null && (adminChannel.canTalk() || !Config.prod)) {
      val adminEmbed = new EmbedBuilder()
      adminEmbed.setTitle(title)
      adminEmbed.setDescription(description)
      adminEmbed.setThumbnail(thumbnail)
      adminEmbed.setColor(color)
      adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
    }
}
