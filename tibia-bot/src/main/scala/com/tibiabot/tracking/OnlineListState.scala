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

/** Delete this channel's whole list and post it again, rather than editing the
 *  messages in place — see [[OnlineListRepostPolicy]] for when that is the better
 *  trade. The sends must not go out until the delete has landed, or a failed
 *  delete leaves the channel holding the list twice over. */
final case class RepostOnlineList(deleteIds: List[String], messages: List[List[String]]) extends OnlineListAction

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
 *                   comparison — see `OnlineListEmbeds.withoutDurations`.
 *  @param policy    when to wipe and repost a channel instead of editing it.
 *                   Defaults to never, which is the behaviour without it.
 *  @param footerMaxStaleMs how long the "Last updated" stamp may be wrong for
 *                   before the last message is rewritten to correct it — see
 *                   [[plan]]. 0 disables it, which is the behaviour without it. */
final class OnlineListState(
  normalise: String => String = OnlineListEmbeds.withoutDurations,
  policy: OnlineListRepostPolicy = OnlineListRepostPolicy.disabled,
  now: () => Long = () => System.currentTimeMillis(),
  footerMaxStaleMs: Long = 0L
) {

  private val lock = new Object()
  private val state = mutable.Map.empty[String, List[OnlineListMessage]]
  // Kept apart from `state` so [[invalidate]] cannot reset it: an error-induced
  // invalidate would otherwise clear the cooldown and let reposts stack up
  // exactly when the channel is already misbehaving.
  private val lastRepostAtMs = mutable.Map.empty[String, Long]
  // When the "Last updated" stamp on this channel's last message was last
  // written. Kept out of `state` for the same reason as the cooldown: an
  // invalidate is a bad moment to promise every channel a fresh footer at once.
  private val lastStampedAtMs = mutable.Map.empty[String, Long]

  /** Has this channel been synced with Discord at least once? */
  def isWarm(channelId: String): Boolean = lock.synchronized { state.contains(channelId) }

  /** Replace this channel's believed state, e.g. after reading its history.
   *
   *  Starts the repost cooldown too. A seed follows either a restart or the
   *  6-hourly purge, and the purge is itself a wipe-and-repost — so in both cases
   *  the channel has just been churned as much as a repost would churn it, and a
   *  fresh cooldown is what that deserves. It also stops every congested channel
   *  reposting at once on the first cycles after a restart. */
  def seed(channelId: String, posted: List[OnlineListMessage]): Unit =
    lock.synchronized {
      state.update(channelId, posted)
      lastRepostAtMs.update(channelId, now())
      // Same argument for the footer: a restart would otherwise find every
      // channel's stamp infinitely old and owe all of them an edit on the first
      // cycle — the one moment the lane can least afford a burst of them.
      lastStampedAtMs.update(channelId, now())
    }

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
   *  pending forever.
   *
   *  The one exception to "only when it changed" is the "Last updated" stamp,
   *  which is a claim about the refresh rather than about the roster and so goes
   *  wrong by standing still — see `footerActions`, and `footerMaxStaleMs` for
   *  turning it off.
   *
   *  When `policy` says this channel is better reposted than edited, the whole
   *  plan collapses to a single [[RepostOnlineList]] instead.
   *
   *  @param queueDepth the online-list lane's backlog, which decides how much
   *                    churn a repost is worth; 0 leaves the incremental path.
   *  @param canDelete  whether the bot may bulk-delete here. Without
   *                    MESSAGE_MANAGE the delete degrades to one request per
   *                    message on the tightest route Discord has, which is worse
   *                    than the edits it set out to avoid. */
  def plan(
    channelId: String,
    messages: List[List[String]],
    queueDepth: Int = 0,
    canDelete: Boolean = false
  ): List[OnlineListAction] = lock.synchronized {
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
    val edits = actions.count(_.isInstanceOf[EditOnlineListMessage])
    val repost = canDelete && policy.shouldRepost(
      messageCount = messages.size,
      editCount = edits,
      allIdsKnown = cached.nonEmpty && cached.forall(_.id.isDefined),
      queueDepth = queueDepth,
      msSinceLastRepost = now() - lastRepostAtMs.getOrElse(channelId, 0L)
    )

    if (repost) {
      // Everything posted here is about to go, so every message is back to
      // awaiting an id — the same state a cold channel commits to.
      state.update(channelId, messages.map(OnlineListMessage(None, _)))
      lastRepostAtMs.update(channelId, now())
      lastStampedAtMs.update(channelId, now())
      List(RepostOnlineList(cached.flatMap(_.id), messages))
    } else {
      // Decided after the repost check, and never counted into `edits`: a
      // rewrite that changes nothing but the stamp is not churn, and letting it
      // push a channel over the dirty-fraction would trade one edit for a whole
      // channel's worth.
      footerActions(channelId, messages, next.toList, actions.toList, cached.size).foreach(actions += _)
      // Left over from a previously longer list. A leftover whose own send is
      // still in flight has no id to delete by — invalidating on send failure is
      // what stops that from stranding a message.
      val extra = cached.drop(messages.size).flatMap(_.id)
      if (extra.nonEmpty) actions += DeleteOnlineListMessages(extra)
      state.update(channelId, next.toList)
      actions.toList
    }
  }

  /** Extra edits owed purely to the "Last updated" stamp, and the commit of when
   *  it was last written.
   *
   *  The stamp goes on the final embed of the final message and nowhere else, so
   *  it is only rewritten when that message is. Two ways that leaves it lying:
   *
   *  1. '''Nothing changed for a long time.''' The list really is current and the
   *     footer says otherwise, which is the case this exists for. Capped by
   *     `footerMaxStaleMs`, so a quiet channel pays one edit per window and a
   *     busy one pays nothing, having rewritten that message anyway. The rewrite
   *     also refreshes the online durations, which are masked out of the change
   *     comparison (see `OnlineListEmbeds.withoutDurations`) and so are otherwise
   *     only as current as the last roster change.
   *  2. '''The list changed length.''' A shorter list leaves the stamp on a
   *     message that has been deleted, so the channel shows none at all until
   *     that message's content happens to change; a longer one leaves the old
   *     last message holding a stamp that is no longer the list's. Both are
   *     corrected here rather than waiting out the cap, since neither is a
   *     question of freshness — the footer is simply in the wrong place.
   *
   *  Both go with `footerMaxStaleMs` when it is off, so that one switch means one
   *  thing: the stamp is left exactly where the change comparison puts it. */
  private def footerActions(
    channelId: String,
    messages: List[List[String]],
    committed: List[OnlineListMessage],
    planned: List[OnlineListAction],
    previousCount: Int
  ): List[OnlineListAction] = if (footerMaxStaleMs <= 0) Nil else {
    val lastIndex = messages.size - 1
    val previousLast = previousCount - 1
    val alreadyWritten = planned.collect {
      case EditOnlineListMessage(index, _, _) => index
      case SendOnlineListMessage(index, _)    => index
    }.toSet

    // A pending slot has no id to edit by, and the send it is waiting on will
    // carry the stamp itself.
    def editable(index: Int): Option[EditOnlineListMessage] =
      if (index < 0 || alreadyWritten.contains(index)) None
      else committed.lift(index).flatMap(_.id).map(EditOnlineListMessage(index, _, messages(index)))

    val stale = footerMaxStaleMs > 0 && now() - lastStampedAtMs.getOrElse(channelId, 0L) >= footerMaxStaleMs
    val moved = previousLast != lastIndex
    val refresh = if (stale || moved) editable(lastIndex) else None
    // Only when the list grew: a message that used to be last and now is not
    // still renders its old stamp.
    val orphan = if (moved && previousLast < lastIndex) editable(previousLast) else None

    if (alreadyWritten.contains(lastIndex) || refresh.isDefined) lastStampedAtMs.update(channelId, now())
    refresh.toList ++ orphan.toList
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
