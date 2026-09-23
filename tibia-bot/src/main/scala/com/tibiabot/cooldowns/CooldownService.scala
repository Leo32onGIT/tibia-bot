package com.tibiabot.cooldowns

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.domain.{CooldownKind, CooldownStamp}
import com.tibiabot.persistence.CooldownRepository
import com.tibiabot.presentation.CooldownEmbeds
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.{Message, User}
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.exceptions.{ErrorHandler, ErrorResponseException}
import net.dv8tion.jda.api.requests.ErrorResponse

import java.time.ZonedDateTime
import scala.jdk.CollectionConverters._

/**
 * Collectible cooldown tracking: CRUD over the satchel table plus the daily
 * expiry DM. CRUD delegates to the repository, [[cleanExpired]] runs the
 * notify-then-delete job for every [[com.tibiabot.domain.CooldownKind]].
 */
final class CooldownService(
  repository: CooldownRepository,
  discordGateway: DiscordGateway,
  botId: String
) extends StrictLogging {

  /** How many undeliverable expiry DMs in a row before this bot stops tracking
   *  cooldowns for someone. Three rather than one, for the same reason the
   *  boosted list waits: a user can close their DMs for a day, and a transient
   *  Discord error must never cost someone the stamps they set. */
  private val maxDeliveryFailures = 3

  def getStamps(userId: String, kind: CooldownKind): Option[List[CooldownStamp]] =
    repository.getStamps(userId, kind)
  def del(user: String, kind: CooldownKind, tag: String): Unit = repository.del(user, kind, tag)
  def delAll(user: String, kind: CooldownKind): Unit = repository.delAll(user, kind)

  /** Setting a stamp is proof this bot shares a guild with the user, so it takes
   *  over their cooldown DMs — the same claim a delivered DM makes. */
  def add(user: String, kind: CooldownKind, when: ZonedDateTime, tag: String): Unit = {
    repository.add(user, kind, when, tag)
    claimForThisBot(user)
  }

  private def claimForThisBot(userId: String): Unit =
    try repository.claim(userId, botId)
    catch { case _: Throwable => () } // routing only; never fail the command over it

  /** A DM reached this user: take ownership of their stamps and clear the
   *  failure count. */
  private def dmDelivered(userId: String): Unit =
    try repository.claim(userId, botId)
    catch { case ex: Throwable => logger.warn(s"Failed to record cooldown-DM delivery for user: '$userId'", ex) }

  /** A DM to this user failed. Drops the stamps this bot owns for them once the
   *  failures stack up, so it stops chasing an inbox it can't reach.
   *
   *  Only stamps this bot owns are dropped: several bots can share one satchel
   *  table, and "no mutual guilds" from one of them usually means another is the
   *  one that reaches this user, not that the user is gone. */
  private def dmFailed(userId: String): Unit =
    try {
      val failures = repository.recordDeliveryFailure(userId, botId)
      if (failures >= maxDeliveryFailures) {
        repository.forget(userId, botId)
        logger.info(s"Removed cooldown tracking for user '$userId': undeliverable $failures expiry DMs running")
      }
    } catch {
      case ex: Throwable => logger.warn(s"Failed to record cooldown-DM failure for user: '$userId'", ex)
    }

  /** Handles both steps of a DM the same way: Discord answers 50278/50007 to the
   *  channel open as readily as to the send, and a failure on the open that
   *  nothing catches is both an ERROR in the log for an ordinary closed inbox
   *  and a failure that never reaches the count above. */
  private def undeliverable(userId: String): ErrorHandler =
    new ErrorHandler().handle(
      List(ErrorResponse.NO_MUTUAL_GUILDS, ErrorResponse.CANNOT_SEND_TO_USER).asJava,
      new java.util.function.Consumer[ErrorResponseException] {
        def accept(ex: ErrorResponseException): Unit = dmFailed(userId)
      }
    )

  /** DM everyone whose cooldown has run out, then delete those rows.
   *
   *  A kind at a time, because the cutoff is now minus that kind's own duration
   *  and the two differ — 30 days for the satchel, 14 for the dragon head. */
  def cleanExpired(): Unit = CooldownKind.all.foreach(sweep)

  private def sweep(kind: CooldownKind): Unit = {
    val cutoff = ZonedDateTime.now().minusDays(kind.durationDays)

    try {
      repository.expiredStamps(kind, cutoff, botId).foreach { stamp =>
        val user: User = discordGateway.retrieveUser(stamp.user)

        if (user != null) {
          try {
            val embed = CooldownEmbeds.expired(stamp, user.getName)
            val onFailure = undeliverable(stamp.user)
            user.openPrivateChannel().queue((privateChannel: PrivateChannel) => {
              privateChannel.sendMessageEmbeds(embed).addComponents(ActionRow.of(
                Button.success(CooldownIds.button(kind, CooldownIds.Action.Remind), CooldownEmbeds.doneLabel(kind))
                  .withEmoji(Emoji.fromFormatted(CooldownEmbeds.emoji(kind))),
                Button.secondary(CooldownIds.button(kind, CooldownIds.Action.Dismiss), "Dismiss")
              )).queue(
                (_: Message) => dmDelivered(stamp.user),
                onFailure
              )
            }, onFailure)
          } catch {
            case ex: Exception => logger.warn(s"Failed to send ${kind.label} expiry DM to user: '${stamp.user}'", ex)
          }
        }
      }

      repository.deleteExpired(kind, cutoff, botId)
    } catch {
      case ex: Throwable => logger.warn(s"Failed to run the ${kind.label} expiry sweep", ex)
    }
  }
}
