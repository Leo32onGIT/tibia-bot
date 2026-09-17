package com.tibiabot.interactions

import com.tibiabot.{BotApp, Config, presentation}
import com.tibiabot.domain.Worlds
import com.tibiabot.statistics.RefreshDecision
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

import scala.jdk.CollectionConverters._

/** The refresh button under a statistics post.
 *
 *  One control and one thing it does: rebuild the experience board over the
 *  last 24 hours and leave the rest of the post where it is. Everything about
 *  *whether* it runs is [[com.tibiabot.statistics.StatisticsRefresh]]'s, and
 *  everything about what the figures are is the service's; this is the part
 *  that knows about Discord.
 *
 *  ==Why a refusal is still an answer==
 *  A press that changes nothing gets a message saying so, ephemeral so the
 *  channel stays a channel of posts. A control that silently does nothing is
 *  read as broken, and the reader presses it again — which is the behaviour the
 *  floor underneath exists to survive rather than to invite.
 */
object StatisticsButtons extends StrictLogging {

  val RefreshId: String = "statisticsRefresh"

  /** Presses this handles. The config gate is deliberately not consulted here:
   *  a post already sitting in a channel keeps its button when the feature is
   *  switched off, and answering it is better than leaving a dead control. */
  def handles(componentId: String): Boolean = componentId == RefreshId

  /** The press rewrites the message it is on, so it defers an edit — the same
   *  shape the respawn and panel buttons use, acknowledged on the event thread
   *  before the work is queued. */
  def handle(event: ButtonInteractionEvent): Unit = {
    val channelId = event.getChannel.getId
    val guildId = Option(event.getGuild).map(_.getId).getOrElse("")
    worldFor(guildId, channelId) match {
      case None =>
        // The channel was repointed or the world removed since this was posted.
        reply(event, s"${Config.noEmoji} This channel is no longer a statistics channel for any world.")
      case Some(world) =>
        if (!Config.Statistics.Refresh.enabled)
          reply(event, s"${Config.noEmoji} Refreshing the statistics post is switched off.")
        else BotApp.refreshStatisticsBoard(guildId, world) match {
          case Right(board) => rewrite(event, board)
          case Left(refusal) => reply(event, explain(refusal))
        }
    }
  }

  /** The world whose statistics post lives in this channel.
   *
   *  By channel rather than by anything on the message, because the channel is
   *  what the configuration is keyed by — and a post that outlived its own
   *  world's setup should refuse rather than refresh somebody else's figures. */
  private def worldFor(guildId: String, channelId: String): Option[Worlds] =
    BotApp.worldsData.getOrElse(guildId, Nil).find(_.statisticsChannel == channelId)

  /** Put the new board in front of what the message already carries.
   *
   *  The embeds after the board are sent back exactly as they arrived, which is
   *  what keeps a refresh from quietly rewriting the war or the bosses — see
   *  [[presentation.StatisticsEmbeds.replaceBoard]]. */
  private def rewrite(event: ButtonInteractionEvent, board: List[net.dv8tion.jda.api.entities.MessageEmbed]): Unit = {
    val existing = event.getMessage.getEmbeds.asScala.toList
    val embeds = presentation.StatisticsEmbeds.replaceBoard(existing, board)
    event.getHook.editOriginalEmbeds(embeds.asJava).queue(
      _ => (),
      (error: Throwable) => {
        logger.warn(s"Statistics: could not rewrite the post in '${event.getChannel.getId}': ${error.getMessage}")
        followUp(event, s"${Config.noEmoji} The figures were read, but the post could not be updated.")
      })
  }

  /** What a refusal says.
   *
   *  Each one names the wait behind it rather than saying no twice: a reader
   *  told "nothing newer" when the truth is "no readings yet" waits for the
   *  wrong thing, and presses again to find out. */
  private def explain(refusal: RefreshDecision): String = refusal match {
    case RefreshDecision.NothingNewer(shown) =>
      s"${Config.yesEmoji} These figures are already the latest reading, from <t:${shown.getEpochSecond}:R>. " +
        "tibia.com rebuilds the highscores about once an hour."
    case RefreshDecision.TooSoon(retryAt) =>
      s"${Config.noEmoji} Just a moment — try again <t:${retryAt.getEpochSecond}:R>."
    case RefreshDecision.NotEnoughReadings =>
      s"${Config.noEmoji} There is not a full day of readings for this world yet. " +
        "It takes 24 hours of hourly highscore readings before this can report a day."
    case RefreshDecision.Unavailable =>
      s"${Config.noEmoji} The figures could not be read just now. Try again in a minute."
    case RefreshDecision.Rebuild(_) =>
      // Not reachable: a rebuild is the answer rather than a refusal. Spelled
      // out rather than left to a MatchError if that ever stops being true.
      s"${Config.noEmoji} An unknown error occurred, please try again."
  }

  /** A refusal, after the edit was deferred. The deferral is already spent, so
   *  this is a follow-up rather than a reply, and the message the button sits on
   *  is left exactly as it was. */
  private def reply(event: ButtonInteractionEvent, message: String): Unit = followUp(event, message)

  private def followUp(event: ButtonInteractionEvent, message: String): Unit =
    event.getHook.sendMessage(message).setEphemeral(true).queue(null, null)
}
