package com.tibiabot.highscores

import com.tibiabot.domain.HighscoreEvent
import com.tibiabot.presentation.ListEmbeds
import com.tibiabot.tibiadata.HighscoreList
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel

import scala.concurrent.{ExecutionContext, Future}

/** Puts a batch of advances into the Levels channels that want them.
 *
 *  Discord-side, so the decisions it makes are not: which advances a server
 *  sees is [[HighscoreAnnouncement]]'s answer, and this only resolves the guild
 *  those decisions need, packs the lines, and hands them to the sender.
 *
 *  Lines are packed into as few messages as Discord's length limit allows and
 *  sent on the same paced lane the level-up flush uses — a snapshot can turn
 *  over a hundred advances on a busy world, and that must not become a hundred
 *  messages. */
final class HighscoreAnnouncer(
    audience: String => List[HighscoreTarget],
    resolveGuilds: (String, List[HighscoreEvent]) => Future[Map[String, String]],
    channelFor: (String, String) => Option[TextChannel],
    send: (TextChannel, String) => Unit,
    onPosted: (String, Int, String) => Unit
)(implicit ec: ExecutionContext) extends StrictLogging {

  def announce(world: String, list: HighscoreList, advances: List[HighscoreEvent]): Unit = {
    // Experience never reaches here — HighscoreDiff yields no advances for a
    // list that does not post — but the channel is shared with level-ups, so
    // this stays explicit rather than relying on that from a distance.
    if (advances.nonEmpty && list.postsAdvances) {
      val targets = audience(world)
      if (targets.nonEmpty) {
        // Only worth a request if some server's answer could actually change.
        val guilds =
          if (targets.exists(_.needsGuild)) resolveGuilds(world, advances)
          else Future.successful(Map.empty[String, String])

        guilds.recover { case error =>
          logger.warn(s"Highscores: could not resolve guilds for '$world', posting as neutral: ${error.getMessage}")
          Map.empty[String, String]
        }.foreach { resolved =>
          targets.foreach(post(_, world, list, advances, name => resolved.getOrElse(name, "")))
        }
      }
    }
  }

  private def post(
      target: HighscoreTarget,
      world: String,
      list: HighscoreList,
      advances: List[HighscoreEvent],
      guildOf: String => String
  ): Unit = {
    val lines = HighscoreAnnouncement.linesFor(target, list.category, advances, guildOf)
    if (lines.nonEmpty) {
      channelFor(target.guildId, target.channelId) match {
        case Some(channel) =>
          ListEmbeds.pack(lines, 1900).foreach(chunk => send(channel, chunk.stripPrefix("\n")))
          onPosted(world, lines.size, target.guildLabel)
        case None =>
          logger.debug(s"Highscores: no writable levels channel for guild '${target.guildId}' on '$world'")
      }
    }
  }
}
