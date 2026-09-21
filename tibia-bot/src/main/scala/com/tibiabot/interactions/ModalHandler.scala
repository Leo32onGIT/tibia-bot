package com.tibiabot.interactions

import com.tibiabot.{BotApp, Config, domain, presentation}
import com.tibiabot.cooldowns.CooldownIds
import com.tibiabot.domain.CooldownKind
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button

import scala.jdk.CollectionConverters._
import java.time.ZonedDateTime

/** Handles modal submissions (boosted boss-name and cooldown tag inputs).
 *  Moved verbatim from BotListener.onModalInteraction. */
object ModalHandler {
  def handle(event: ModalInteractionEvent): Unit = {
    event.deferEdit().queue()
     val user = event.getUser
     val modalValues = event.getValues.asScala.toList
     modalValues.map { element =>
       val id = element.getCustomId
       val inputName = domain.BossAliases.canonical(element.getAsString.trim.toLowerCase)
       if (id == "boosted add") {
         val newEmbed = BotApp.boostedService.boosted(user.getId, "add", inputName)
         event.getHook().editOriginalEmbeds(newEmbed).setComponents(ActionRow.of(
           Button.success("boosted add", "Add"),
           Button.danger("boosted remove", "Remove"),
           Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
         )).queue()
       } else if (id == "boosted remove") {
         val newEmbed = BotApp.boostedService.boosted(user.getId, "remove", inputName)
         event.getHook().editOriginalEmbeds(newEmbed).setComponents(ActionRow.of(
           Button.success("boosted add", "Add"),
           Button.danger("boosted remove", "Remove"),
           Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
         )).queue()
       } else {
         CooldownIds.parseField(id).foreach { case (kind, adding) =>
           cooldown(event, kind, adding, element.getAsString.trim.toLowerCase)
         }
       }
     }
  }

  /** A cooldown form came back: stamp or clear the tag typed into it, then
   *  rewrite the list it was opened from so the change is visible without a
   *  second press.
   *
   *  Typing your own Discord name means the untagged stamp — the one the list
   *  shows under your own name — rather than a tag that happens to match it. */
  private def cooldown(event: ModalInteractionEvent, kind: CooldownKind, adding: Boolean, typed: String): Unit = {
    val user = event.getUser
    val tag = if (typed.equalsIgnoreCase(user.getName)) "" else typed

    if (adding) BotApp.cooldownService.add(user.getId, kind, ZonedDateTime.now(), tag)
    else BotApp.cooldownService.del(user.getId, kind, tag)

    val tracked = BotApp.cooldownService.getStamps(user.getId, kind).getOrElse(Nil)
    val verb = if (adding) "added" else "Disabled"
    val note = s"${Config.yesEmoji} cooldown tracker for **`$typed`** has been **$verb**."

    event.getHook.editOriginalEmbeds(presentation.CooldownEmbeds.list(kind, tracked, user.getName, note))
      .setComponents(presentation.CooldownEmbeds.controls(kind, tracked.size)).queue()
  }
}
