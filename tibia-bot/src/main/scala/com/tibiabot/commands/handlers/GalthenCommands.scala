package com.tibiabot.commands.handlers

import com.tibiabot.BotApp
import com.tibiabot.BotApp.SatchelStamp
import com.tibiabot.presentation.GalthenEmbeds
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.components.buttons.Button

/** Handles `/galthen`: per-user Galthen's Satchel 30-day cooldown tracker. */
object GalthenCommands {

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val tagOption: String = Options.of(event).getOrElse("character", "")
    val satchelTimeOption: Option[List[SatchelStamp]] = BotApp.galthenService.getStamps(event.getUser.getId)
    val embed = new EmbedBuilder()

    satchelTimeOption match {
      case Some(satchelTimeList) if satchelTimeList.isEmpty =>
        embed.setColor(178877)
        if (tagOption.nonEmpty) embed.setFooter(s"Tag: ${tagOption.toLowerCase}")
        embed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you when the 30 day cooldown expires.")
        embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
        event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
          Button.success("galthenSet", "Collected"),
          Button.danger("galthenRemove", "Clear").asDisabled
        ).queue()

      case Some(satchelTimeList) =>
        val tagList = satchelTimeList.collect {
          case satchel if tagOption.equalsIgnoreCase(satchel.tag) =>
            val when = satchel.when.plusDays(30).toEpochSecond.toString()
            s"<:satchel:1030348072577945651> can be collected by **`${satchel.tag}`** <t:$when:R>"
        }

        val fullList = satchelTimeList.collect {
          case satchel =>
            val when = satchel.when.plusDays(30).toEpochSecond.toString()
            val displayTag = if (satchel.tag == "") s"<@${event.getUser.getId}>" else s"**`${satchel.tag}`**"
            s"<:satchel:1030348072577945651> can be collected by $displayTag <t:$when:R>"
        }

        if (tagOption.isEmpty && fullList.nonEmpty) {
          embed.setTitle("Existing Cooldowns:")
          embed.setDescription(GalthenEmbeds.truncate(fullList))
          embed.setColor(13773097)
          embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
          if (fullList.size == 1){
            event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
              Button.success("galthenSet", "Collected").asDisabled,
              Button.danger("galthenRemoveAll", "Clear")
            ).queue()
          } else {
            event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
              Button.secondary("galthenLock", "🔒"),
              Button.danger("galthenRemoveAll", "Clear All").asDisabled
            ).queue()
          }
        } else if (tagOption.nonEmpty && tagList.nonEmpty) { // tag picked up
          embed.setFooter(s"Tag: ${tagOption.toLowerCase}")
          embed.setDescription(tagList.mkString("\n"))
          embed.setColor(9855533)
          event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
            Button.success("galthenSet", "Collected").asDisabled,
            Button.danger("galthenRemove", "Clear")
          ).queue()
        } else {
          embed.setColor(178877)
          if (tagOption.nonEmpty) embed.setFooter(s"Tag: ${tagOption.toLowerCase}")
          embed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you when the 30 day cooldown expires.")
          embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
          event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
            Button.success("galthenSet", "Collected"),
            Button.danger("galthenRemove", "Clear").asDisabled
          ).queue()
        }

      case None =>
        embed.setColor(178877)
        if (tagOption.nonEmpty) embed.setFooter(s"Tag: ${tagOption.toLowerCase}")
        embed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you when the 30 day cooldown expires.")
        embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
        event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
          Button.success("galthenSet", "Collected"),
          Button.danger("galthenRemove", "Clear").asDisabled
        ).queue()
    }
  }
}
