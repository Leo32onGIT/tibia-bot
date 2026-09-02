package com.tibiabot.tracking

import com.tibiabot.presentation.OnlineListEmbeds

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** One online-list message the bot believes it has posted in a channel, and the
 *  embed descriptions it last put there — several, since a message carries up to
 *  the message-wide embed text budget rather than a single embed's worth (see
 *  `OnlineListEmbeds.packMessages`). `id` is None while the send that creates the
 *  message is still in flight. */
final case class OnlineListMessage(id: Option[String], descriptions: List[String])

sealed trait OnlineListAction
final case class EditOnlineListMessage(index: Int, messageId: String, descriptions: List[String]) extends OnlineListAction
final case class SendOnlineListMessage(index: Int, descriptions: List[String]) extends OnlineListAction
final case class DeleteOnlineListMessages(messageIds: List[String]) extends OnlineListAction

/** What the bot believes is posted in each online-list channel, and the diff to
 *  bring a channel in line with a freshly rendered list.
 *
 *  The bot posts these messages itself, so it knows their ids and contents. Every
 *  refresh used to re-read up to 100 messages of history per (guild, channel) just
 *  to rediscover them — a large invisible REST cost on the very route Discord
 *  rate-limits hardest here. Held locally, the steady-state refresh reads Discord
 *  not at all; history is re-read only on a cold cache or after [[invalidate]].
 *
 *  Purely an optimisation, safe to drop: an absent channel costs one history read
 *  to rebuild. Absent means "not synced yet", present-but-empty "synced, empty".
 *
 *  Thread-safe: written from a world stream's thread and from JDA callbacks (a
 *  completed send reports its id via [[recordMessageId]]), so [[plan]]'s
 *  decide-and-commit must be atomic.
 *
 *  @param normalise applied to both sides of the "did this message change?"
 *                   comparison — see `OnlineListEmbeds.withoutDurations`. */
final class OnlineListState(normalise: String => String = OnlineListEmbeds.withoutDurations) {

  private val lock = new Object()
  private val state = mutable.Map.empty[String, List[OnlineListMessage]]

  /** Has this channel been synced with Discord at least once? */
  def isWarm(channelId: String): Boolean = lock.synchronized { state.contains(channelId) }

  /** Replace this channel's believed state, e.g. after reading its history. */
  def seed(channelId: String, posted: List[OnlineListMessage]): Unit =
    lock.synchronized { state.update(channelId, posted) }

  /** Forget this channel, forcing the next cycle to rebuild from history. */
  def invalidate(channelId: String): Unit = lock.synchronized { state.remove(channelId); () }

  /** Current believed state, for assertions and diagnostics. */
  def posted(channelId: String): Option[List[OnlineListMessage]] = lock.synchronized { state.get(channelId) }

  /** Diff `messages` (each the embed descriptions for one message) against the
   *  believed state, commit the believed state to what those actions will
   *  produce, and return the actions.
   *
   *  A message is only re-edited when one of its normalised descriptions actually
   *  changed: the caller runs this every ~2 minutes per guild whether or not the
   *  online list moved at all, so without the guard it would rewrite every
   *  message on every check. One changed embed rewrites the whole message, since
   *  an edit replaces a message's embeds wholesale.
   *
   *  A slot whose send is still in flight (`id` empty) is left untouched for
   *  this cycle rather than being posted a second time; the next cycle picks up
   *  whatever changed once the id has landed. A send that never completes must
   *  therefore be reported by invalidating the channel, or that slot stays
   *  pending forever. */
  def plan(channelId: String, messages: List[List[String]]): List[OnlineListAction] = lock.synchronized {
    val cached = state.getOrElse(channelId, Nil)
    val actions = ListBuffer.empty[OnlineListAction]
    val next = ListBuffer.empty[OnlineListMessage]
    messages.zipWithIndex.foreach { case (descriptions, index) =>
      cached.lift(index) match {
        case Some(pending) if pending.id.isEmpty => next += pending
        case Some(existing) if existing.descriptions.map(normalise) == descriptions.map(normalise) => next += existing
        case Some(existing) =>
          actions += EditOnlineListMessage(index, existing.id.get, descriptions)
          next += existing.copy(descriptions = descriptions)
        case None =>
          actions += SendOnlineListMessage(index, descriptions)
          next += OnlineListMessage(None, descriptions)
      }
    }
    // Left over from a previously longer list. A leftover whose own send is
    // still in flight has no id to delete by — invalidating on send failure is
    // what stops that from stranding a message.
    val extra = cached.drop(messages.size).flatMap(_.id)
    if (extra.nonEmpty) actions += DeleteOnlineListMessages(extra)
    state.update(channelId, next.toList)
    actions.toList
  }

  /** Fill in the id of a message that has just been posted. No-op if the slot
   *  was invalidated or already filled while the send was in flight. */
  def recordMessageId(channelId: String, index: Int, messageId: String): Unit = lock.synchronized {
    state.get(channelId).foreach { cached =>
      cached.lift(index).filter(_.id.isEmpty).foreach { slot =>
        state.update(channelId, cached.updated(index, slot.copy(id = Some(messageId))))
      }
    }
  }
}
