package com.tibiabot.interactions

import com.tibiabot.observer.LinkOutcome
import com.tibiabot.presentation.{Embeds, ObserverEmbeds}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent

/** The form behind the `/observer` **Add** button: takes a world and the 5-char Tibia
 *  Observer token, links the token for this member, and ensures that world's raids
 *  channel.
 *
 *  Routed apart from [[ModalHandler]] like the notification forms: it answers with an
 *  ephemeral of its own rather than rewriting the panel it was opened from. Already
 *  deferred (ephemeral) by BotListener. */
object ObserverModals extends StrictLogging {

  val ModalId = "observer add modal"
  val WorldField = "observer world"
  val TokenField = "observer add"

  def handles(modalId: String): Boolean = modalId == ModalId

  def handle(event: ModalInteractionEvent): Unit =
    Option(event.getGuild) match {
      case None =>
        reply(event, s"${Config.noEmoji} That form only works inside a server.")
      case Some(_) if !Config.Observer.storageEnabled =>
        reply(event, s"${Config.noEmoji} Tibia Observer isn't set up on this bot yet.")
      case Some(guild) =>
        val requestedWorld = value(event, WorldField)
        BotApp.worldsTrackedBy(guild.getId).find(_.equalsIgnoreCase(requestedWorld)) match {
          case None =>
            reply(event, s"${Config.noEmoji} This server isn't tracking **$requestedWorld** — an admin can `/setup $requestedWorld` first.")
          case Some(world) =>
            normalise(value(event, TokenField)) match {
              case None =>
                reply(event, s"${Config.noEmoji} That doesn't look like a valid token. Copy the code shown under " +
                  "*Account Management → Tibia Observer → Connect* on tibia.com.")
              case Some(token) =>
                try {
                  BotApp.observerService.link(guild.getId, event.getUser.getId, token) match {
                    case LinkOutcome.Ok(stored, _) =>
                      event.getHook
                        .sendMessageEmbeds(ObserverEmbeds.panel(Some(stored)))
                        .setComponents(ObserverEmbeds.controls(Some(stored)))
                        .setEphemeral(true)
                        .queue(_ => (), _ => ())
                      // Ensure this world's raids channel now they're linked, seeding
                      // its dedup on first creation so it starts with raids going forward.
                      if (BotApp.channelService.ensureRaidsChannel(guild, world))
                        BotApp.observerRaidPoller.seedPosted(guild.getId, world)
                    case LinkOutcome.InvalidToken =>
                      reply(event, s"${Config.noEmoji} That token was rejected. It's **case-sensitive** and " +
                        "**single-use** — generate a fresh one and paste it exactly as shown on tibia.com.")
                    case LinkOutcome.Failed(_) =>
                      reply(event, s"${Config.noEmoji} Couldn't reach the Observer service just now — please try again shortly.")
                  }
                } catch {
                  case ex: Throwable =>
                    logger.error(s"Failed to store Observer token for '${event.getUser.getId}' in guild '${guild.getId}'", ex)
                    reply(event, s"${Config.noEmoji} Couldn't save that token — please try again.")
                }
            }
        }
    }

  private def value(event: ModalInteractionEvent, field: String): String =
    Option(event.getValue(field)).map(_.getAsString.trim).getOrElse("")

  /** The website tokens are short alphanumeric codes (e.g. `umzzk`) and are
   *  CASE-SENSITIVE and single-use — so only trim, never change case. Kept a
   *  little loose on length since it isn't guaranteed; the API is the real check. */
  private def normalise(raw: String): Option[String] = {
    val t = raw.trim
    if (t.matches("^[A-Za-z0-9]{4,10}$")) Some(t) else None
  }

  private def reply(event: ModalInteractionEvent, message: String): Unit =
    event.getHook.sendMessageEmbeds(Embeds.response(message)).setEphemeral(true).queue(_ => (), _ => ())
}
