package com.tibiabot.galthen

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.domain.SatchelStamp
import com.tibiabot.domain.time.SatchelCooldown
import com.tibiabot.persistence.GalthenRepository
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.{Message, User}
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.exceptions.{ErrorHandler, ErrorResponseException}
import net.dv8tion.jda.api.requests.ErrorResponse

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters._
import com.tibiabot.presentation.Names

/**
 * Galthen's Satchel cooldown tracking: CRUD over the satchel table plus the
 * daily expiry DM. CRUD delegates to the repository, [[cleanExpired]] runs the
 * notify-then-delete job.
 */
final class GalthenService(
  repository: GalthenRepository,
  discordGateway: DiscordGateway,
  botId: String
) extends StrictLogging {

  /** How many undeliverable expiry DMs in a row before this bot stops tracking
   *  satchels for someone. Three rather than one, for the same reason the
   *  boosted list waits: a user can close their DMs for a day, and a transient
   *  Discord error must never cost someone the stamps they set. */
  private val maxDeliveryFailures = 3

  def getStamps(userId: String): Option[List[SatchelStamp]] = repository.getStamps(userId)
  def del(user: String, tag: String): Unit = repository.del(user, tag)
  def delAll(user: String): Unit = repository.delAll(user)

  /** Setting a stamp is proof this bot shares a guild with the user, so it takes
   *  over their satchel DMs — the same claim a delivered DM makes. */
  def add(user: String, when: ZonedDateTime, tag: String): Unit = {
    repository.add(user, when, tag)
    claimForThisBot(user)
  }

  private def claimForThisBot(userId: String): Unit =
    try repository.claim(userId, botId)
    catch { case _: Throwable => () } // routing only; never fail the command over it

  /** A DM reached this user: take ownership of their stamps and clear the
   *  failure count. */
  private def dmDelivered(userId: String): Unit =
    try repository.claim(userId, botId)
    catch { case ex: Throwable => logger.warn(s"Failed to record satchel-DM delivery for user: '$userId'", ex) }

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
        logger.info(s"Removed satchel tracking for user '$userId': undeliverable $failures expiry DMs running")
      }
    } catch {
      case ex: Throwable => logger.warn(s"Failed to record satchel-DM failure for user: '$userId'", ex)
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

  /** DM each user whose 30-day satchel cooldown has expired, then delete those rows. */
  def cleanExpired(): Unit = {
    val cutoff = ZonedDateTime.now().minus(SatchelCooldown.durationDays, ChronoUnit.DAYS)

    try {
      repository.expiredStamps(cutoff, botId).foreach { stamp =>
        val user: User = discordGateway.retrieveUser(stamp.user)
        val cooldown = stamp.when.toInstant.plus(SatchelCooldown.durationDays, ChronoUnit.DAYS).getEpochSecond.toString()

        if (user != null) {
          try {
            val embed = new EmbedBuilder()
            if (stamp.tag.nonEmpty) embed.setFooter(s"Tag: ${stamp.tag.toLowerCase}")
            val displayTag = if (stamp.tag.nonEmpty) s"**`${stamp.tag}`**" else Names.user(user.getName)
            embed.setColor(178877)
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
            embed.setDescription(s"<:satchel:1030348072577945651> cooldown for $displayTag expired <t:$cooldown:R>\n\nMark it as **Collected** and I will message you when the 30 day cooldown expires.")

            val onFailure = undeliverable(stamp.user)
            user.openPrivateChannel().queue((privateChannel: PrivateChannel) => {
              privateChannel.sendMessageEmbeds(embed.build()).addComponents(ActionRow.of(
                Button.success("galthenRemind", "Collected"),
                Button.secondary("galthenClear", "Dismiss")
              )).queue(
                (_: Message) => dmDelivered(stamp.user),
                onFailure
              )
            }, onFailure)
          } catch {
            case ex: Exception => logger.warn(s"Failed to send Galthen expiry DM to user: '${stamp.user}'", ex)
          }
        }
      }

      repository.deleteExpired(cutoff, botId)
    } catch {
      case ex: Throwable => logger.warn("Failed to run the Galthen satchel expiry sweep", ex)
    }
  }
}
