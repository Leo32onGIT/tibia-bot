package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/neutral`: per-world neutral death/level toggles and online-list tag categories. */
object NeutralCommands {

  // Matches a single standard (non-custom) Discord emoji.
  private val emojiPattern =
    "^(?:[\\uD83C\\uDF00-\\uD83D\\uDDFF]|[\\uD83E\\uDD00-\\uD83E\\uDDFF]|[\\uD83D\\uDE00-\\uD83D\\uDE4F]|[\\uD83D\\uDE80-\\uD83D\\uDEFF]|[\\u2600-\\u26FF]\\uFE0F?|[\\u2700-\\u27BF]\\uFE0F?|\\u24C2\\uFE0F?|[\\uD83C\\uDDE6-\\uD83C\\uDDFF]{1,2}|[\\uD83C\\uDD70\\uD83C\\uDD71\\uD83C\\uDD7E\\uD83C\\uDD7F\\uD83C\\uDD8E\\uD83C\\uDD91-\\uD83C\\uDD9A]\\uFE0F?|[\\u0023\\u002A\\u0030-\\u0039]\\uFE0F?\\u20E3|[\\u2194-\\u2199\\u21A9-\\u21AA]\\uFE0F?|[\\u2B05-\\u2B07\\u2B1B\\u2B1C\\u2B50\\u2B55]\\uFE0F?|[\\u2934\\u2935]\\uFE0F?|[\\u3030\\u303D]\\uFE0F?|[\\u3297\\u3299]\\uFE0F?|[\\uD83C\\uDE01\\uD83C\\uDE02\\uD83C\\uDE1A\\uD83C\\uDE2F\\uD83C\\uDE32-\\uD83C\\uDE3A\\uD83C\\uDE50\\uD83C\\uDE51]\\uFE0F?|[\\u203C\\u2049]\\uFE0F?|[\\u25AA\\u25AB\\u25B6\\u25C0\\u25FB-\\u25FE]\\uFE0F?|[\\u00A9\\u00AE]\\uFE0F?|[\\u2122\\u2139]\\uFE0F?|\\uD83C\\uDC04\\uFE0F?|\\uD83C\\uDCCF\\uFE0F?|[\\u231A\\u231B\\u2328\\u23CF\\u23E9-\\u23F3\\u23F8-\\u23FA]\\uFE0F?)$".r

  /** True when the input is exactly one standard (non-custom) Discord emoji. */
  def isValidEmoji(emoji: String): Boolean = emojiPattern.findFirstIn(emoji).isDefined

  /** Strip anything but letters, digits and whitespace from a category label, then trim. */
  def sanitizeLabel(label: String): String = label.replaceAll("[^a-zA-Z0-9\\s]", "").trim

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val subCommand = event.getInteraction.getSubcommandName
    val subcommandGroupName = event.getInteraction.getSubcommandGroup
    val options = Options.of(event)
    val toggleOption: String = options.getOrElse("option", "")
    val worldOption: String = options.getOrElse("world", "")

    if (subcommandGroupName != null) {
      subcommandGroupName match {
        case "tag" =>
          subCommand match {
            case "add" =>
              val typeOption: String = options.getOrElse("type", "")
              val nameOption: String = options.getOrElse("name", "").trim
              val labelOption: String = sanitizeLabel(options.getOrElse("label", ""))
              val emojiOption: String = options.getOrElse("emoji", "").trim
              if (labelOption == "" || emojiOption == ""){
                val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You must supply a **label** and **emoji** when tagging a guild or player.").setColor(3092790).build()
                event.getHook.sendMessageEmbeds(embed).queue()
              } else {
                if (isValidEmoji(emojiOption)) {
                  BotApp.addOnlineListCategory(event, typeOption, nameOption, labelOption, emojiOption, embed => {
                    event.getHook.sendMessageEmbeds(embed).queue()
                  })
                } else {
                  val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} The provided emoji is invalid - use a standard discord emoji.\n:warning: Custom emojis are not supported.").setColor(3092790).build()
                  event.getHook.sendMessageEmbeds(embed).queue()
                }
              }
            case "remove" =>
              val typeOption: String = options.getOrElse("type", "")
              val nameOption: String = options.getOrElse("name", "").trim
              val embed = BotApp.removeOnlineListCategory(event, typeOption, nameOption)
              event.getHook.sendMessageEmbeds(embed).queue()
            case "clear" =>
              val labelOption: String = sanitizeLabel(options.getOrElse("label", ""))
              val embed = BotApp.clearOnlineListCategory(event, labelOption)
              event.getHook.sendMessageEmbeds(embed).queue()
            case "list" =>
              val embeds = BotApp.listOnlineListCategory(event)
              embeds.foreach { embed =>
                event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
              }
          }
        case other =>
          val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommandGroup '$other' for `/neutral`.").setColor(3092790).build()
          event.getHook.sendMessageEmbeds(embed).queue()
      }
    } else {
      subCommand match {
        case "deaths" =>
          if (toggleOption == "show") {
            val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "neutrals", "deaths")
            event.getHook.sendMessageEmbeds(embed).queue()
          } else if (toggleOption == "hide") {
            val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "neutrals", "deaths")
            event.getHook.sendMessageEmbeds(embed).queue()
          }
        case "levels" =>
          if (toggleOption == "show") {
            val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "neutrals", "levels")
            event.getHook.sendMessageEmbeds(embed).queue()
          } else if (toggleOption == "hide") {
            val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "neutrals", "levels")
            event.getHook.sendMessageEmbeds(embed).queue()
          }
        case other =>
          val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$other' for `/neutral`.").setColor(3092790).build()
          event.getHook.sendMessageEmbeds(embed).queue()
      }
    }
  }
}
