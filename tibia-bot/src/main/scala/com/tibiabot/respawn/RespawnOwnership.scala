package com.tibiabot.respawn

import com.tibiabot.commands.CommandSchemas
import com.tibiabot.domain.RespawnSettings
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.Guild

import java.util.concurrent.ConcurrentHashMap

/** Which bot identity is responsible for a guild's respawn system.
 *
 *  Several identities (Blue, Red, a local DEV bot) can share a guild, each running
 *  its own lifecycle sweep against the *same* `_<guildId>` database. Without this
 *  they all act on every claim — racing to send reminders, start due slots and DM
 *  handover offers — and since the sweep's read and its "mark done" write are not
 *  one transaction, the same nudge can go out twice.
 *
 *  The owner is whoever created the guild's board post, i.e. the bot that built
 *  the forum. That needs no configuration and no migration: it is already true of
 *  every existing guild.
 *
 *  Buttons and modals need no equivalent guard — a component only reaches the
 *  application whose message carries it. */
final class RespawnOwnership(selfUserId: String) extends StrictLogging {

  /** Resolved owners, by guild id. Written once and kept: a board post's
   *  creator cannot change, so re-resolving would only cost lookups. */
  private val owners = new ConcurrentHashMap[String, String]()

  /** Earliest time (epoch millis) to try resolving a guild whose board post
   *  couldn't be read. Without this, a guild with a deleted or deeply archived
   *  board would re-page up to 500 archived threads on every 30-second sweep,
   *  forever. */
  private val nextAttempt = new ConcurrentHashMap[String, java.lang.Long]()

  private val RetryBackoffMillis = 30L * 60 * 1000

  /** The decision itself. An unknown owner reads as "ours": a guild whose
   *  board can't be identified is left running exactly as it did before this
   *  existed, because silently stopping a working guild's hunts from ending is
   *  a worse failure than the duplicate nudge this class prevents. */
  private[respawn] def ownedBy(owner: Option[String], selfUserId: String): Boolean =
    owner.forall(_ == selfUserId)

  /** The board post's creator, if it can be read right now. Both lookups are
   *  cache-only in the normal case — the board is pinned and kept unarchived,
   *  so it stays in JDA's thread cache and this costs nothing per sweep. */
  private def resolveFromBoard(guild: Guild, settings: RespawnSettings): Option[String] =
    RespawnThreads.findForum(guild, settings)
      .flatMap(forum => RespawnThreads.resolveThread(guild, forum, settings.boardThread))
      .map(_.getOwnerId)
      .filter(id => id != null && id.nonEmpty)

  /** Who owns this guild's respawn system: the board post's creator, falling
   *  back to whatever was resolved earlier, then to the hardcoded
   *  command-owner map (which already names the owner of the shared support
   *  Discord — the one guild known to be shared, and so the one where getting
   *  this wrong matters most). None when nothing can say. */
  private def ownerOf(guild: Guild, settings: RespawnSettings): Option[String] = {
    val guildId = guild.getId
    val cached = Option(owners.get(guildId))
    val resolved = cached.orElse {
      val due = Option(nextAttempt.get(guildId)).forall(_.longValue <= System.currentTimeMillis())
      if (!due) None
      else resolveFromBoard(guild, settings) match {
        case Some(owner) =>
          owners.put(guildId, owner)
          nextAttempt.remove(guildId)
          logger.info(s"Respawn board in guild '$guildId' belongs to bot '$owner' (this bot is '$selfUserId')")
          Some(owner)
        case None =>
          nextAttempt.put(guildId, System.currentTimeMillis() + RetryBackoffMillis)
          None
      }
    }
    resolved.orElse(CommandSchemas.restrictedCommandGuildOwners.get(guild.getIdLong))
  }

  /** Should this bot run the respawn lifecycle sweep for this guild? */
  def ownsRespawns(guild: Guild, settings: RespawnSettings): Boolean =
    ownedBy(ownerOf(guild, settings), selfUserId)
}
