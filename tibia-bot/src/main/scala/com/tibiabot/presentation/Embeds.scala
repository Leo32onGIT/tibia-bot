package com.tibiabot.presentation

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** Builders for the bot's plain response embeds. */
object Embeds {

  /** The standard embed colour used across the bot. */
  val BrandColor: Int = 3092790

  /** Bright purple — nemesis/notable-creature death embeds, and anything
   *  else that should read as a distinct "something's wrong" signal rather
   *  than routine brand-coloured output (e.g. the Patreon paywall's pause
   *  notices). */
  val NemesisPurple: Int = 11563775

  /** The bot's yellow (the same 14397256 GuildActivity uses for neutral activity
   *  and RespawnEmbeds calls WarnColor) — something the bot did on its own rather
   *  than something somebody asked it to do. The brand colour reads as an answer
   *  to a command; this reads as the bot acting unprompted, which is worth
   *  telling apart at a glance in a channel that is mostly audit lines. */
  val AutomaticColor: Int = 14397256

  /** A minimal response embed: the brand colour and a description, nothing else.
   *  Replaces the repeated `new EmbedBuilder().setColor(3092790)
   *  .setDescription(...).build()` chain used for simple command replies. */
  def response(description: String): MessageEmbed =
    new EmbedBuilder().setColor(BrandColor).setDescription(description).build()
}
