package com.tibiabot.tracking

/** When a channel's online list is better wiped and reposted than edited message
 *  by message.
 *
 *  Discord groups PATCH .../messages/{id} into one bucket shared across many
 *  channels (see discord.conf), so message edits are the scarcest thing this bot
 *  spends and every online-list refresh spends them. Sends and bulk deletes sit
 *  in their own per-channel buckets with room to spare. Replacing a channel's
 *  whole list — one bulk delete, then one send per message — therefore costs more
 *  requests in total but takes them off the route that actually runs out.
 *
 *  It is not free: a repost marks the channel unread and drops readers to the
 *  bottom of it, where an edit is silent. So it fires only where the trade is
 *  clearly worth making — nearly every message would be rewritten anyway, the
 *  list is long enough for the swap to pay, and the lane is congested enough for
 *  the relief to be worth the churn — and no more often than a cooldown that
 *  tightens as the lane worsens.
 *
 *  @param tiers (lane queue depth, cooldown ms) pairs. The deepest tier the lane
 *               has reached decides the cooldown; below every tier, nothing is
 *               reposted at all. Empty disables reposting outright.
 */
final case class OnlineListRepostPolicy(tiers: List[(Int, Long)]) {

  private val ordered = tiers.sortBy { case (depth, _) => depth }

  /** How long a channel must wait between reposts at this lane depth, or None
   *  while the lane is healthy enough that reposting buys nothing worth its cost. */
  def cooldownMs(queueDepth: Int): Option[Long] =
    ordered.filter { case (depth, _) => queueDepth >= depth }.lastOption.map { case (_, cooldown) => cooldown }

  /** Should this channel's list be reposted rather than edited in place?
   *
   *  @param messageCount      messages the freshly rendered list packs into
   *  @param editCount         how many already-posted messages it would rewrite
   *  @param allIdsKnown       every message the bot believes is posted here has an
   *                           id — one still in flight has nothing to delete by
   *  @param queueDepth        the online-list lane's backlog
   *  @param msSinceLastRepost how long since this channel was last reposted
   */
  def shouldRepost(
    messageCount: Int,
    editCount: Int,
    allIdsKnown: Boolean,
    queueDepth: Int,
    msSinceLastRepost: Long
  ): Boolean =
    allIdsKnown &&
      messageCount >= OnlineListRepostPolicy.MinMessages &&
      editCount >= math.ceil(OnlineListRepostPolicy.MinDirtyFraction * messageCount) &&
      cooldownMs(queueDepth).exists(msSinceLastRepost >= _)
}

object OnlineListRepostPolicy {

  /** Shorter lists are not worth reposting: the swap trades `editCount` edits for
   *  one bulk delete plus `messageCount` sends, so it only pays where a list runs
   *  to several messages. */
  val MinMessages = 3

  /** Share of a channel's messages that must already need rewriting. Below it a
   *  repost would also rewrite messages nothing had changed in, spending sends on
   *  work the edit path was not going to do. */
  val MinDirtyFraction = 0.8

  /** Never repost, whatever the lane is doing. */
  val disabled: OnlineListRepostPolicy = OnlineListRepostPolicy(Nil)

  /** The configured policy, or [[disabled]] when reposting is switched off. */
  def tiered(enabled: Boolean, tiers: (Int, Long)*): OnlineListRepostPolicy =
    if (enabled) OnlineListRepostPolicy(tiers.toList) else disabled
}
