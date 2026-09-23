package com.tibiabot.interactions

import com.tibiabot.observer.LinkOutcome
import com.tibiabot.presentation.{Embeds, ObserverEmbeds}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent

/** The form behind the `/observer` **Add** button: takes the 5-char Tibia Observer
 *  token, links it for this member, and ensures a raids channel for every world the
 *  server tracks — the world context comes from the server's own setup, so the
 *  member never names a world.
 *
 *  Routed apart from [[ModalHandler]] like the notification forms: it answers with an
 *  ephemeral of its own rather than rewriting the panel it was opened from. Already
 *  deferred (ephemeral) by BotListener. */
object ObserverModals extends StrictLogging {

  val ModalId = "observer add modal"
  val TokenField = "observer add"

  def handles(modalId: String): Boolean = modalId == ModalId

  def handle(event: ModalInteractionEvent): Unit =
    Option(event.getGuild) match {
      case None =>
        reply(event, s"${Config.noEmoji} That form only works inside a server.")
      case Some(_) if !Config.Observer.storageEnabled =>
        reply(event, s"${Config.noEmoji} Tibia Observer isn't set up on this bot yet.")
      case Some(guild) =>
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
                  ensureRaidsChannels(guild)
                case LinkOutcome.InvalidToken =>
                  reply(event, s"${Config.noEmoji} That token was rejected — it's single-use, so generate a " +
                    "fresh one on tibia.com and paste it straight in.")
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

  /** Ensure a raids channel for every world the guild tracks, seeding each one's
   *  dedup on first creation so it starts with raids going forward. */
  private def ensureRaidsChannels(guild: net.dv8tion.jda.api.entities.Guild): Unit =
    BotApp.worldsTrackedBy(guild.getId).foreach { world =>
      if (BotApp.channelService.ensureRaidsChannel(guild, world))
        BotApp.observerRaidPoller.seedPosted(guild.getId, world)
    }

  private def value(event: ModalInteractionEvent, field: String): String =
    Option(event.getValue(field)).map(_.getAsString.trim).getOrElse("")

  /** The website tokens are short alphanumeric codes (e.g. `FNP68`). Only trim and
   *  validate shape here; the sidecar lower-cases before the API sees it. */
  private def normalise(raw: String): Option[String] = {
    val t = raw.trim
    if (t.matches("^[A-Za-z0-9]{4,10}$")) Some(t) else None
  }

  private def reply(event: ModalInteractionEvent, message: String): Unit =
    event.getHook.sendMessageEmbeds(Embeds.response(message)).setEphemeral(true).queue(_ => (), _ => ())
}
