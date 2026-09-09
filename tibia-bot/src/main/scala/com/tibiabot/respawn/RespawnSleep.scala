package com.tibiabot.respawn

import com.tibiabot.Config
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.{Channel, ChannelType}
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** Which spawn posts are waiting to be put back to sleep, and when.
 *
 *  [[RespawnService.refreshThread]] archives a post the moment its spawn comes
 *  free, but it does not stay closed: interacting with an archived post re-opens
 *  it, and a free spawn's buttons change no claim state, so they never reach
 *  `refreshThread` and nothing asks for it to close again.
 *
 *  Closing it as the press is handled fails too — the press is what re-opened it,
 *  and an archive racing that would flap the post open and shut through a burst of
 *  clicks. So a touched post is written down here and closed once quiet for
 *  [[com.tibiabot.Config.Respawn.closeDelay]], making a whole visit cost one
 *  archive rather than one per click.
 *
 *  Keyed on the thread, which is unique across guilds; the value carries the guild
 *  because the closer needs its settings and claim state.
 *
 *  In memory, so a restart forgets what was pending — which is why
 *  [[RespawnService.reconcileThreads]] is the backstop. Everything here is a map
 *  operation, so `touch` is safe on JDA's event thread; only the sweep drains it. */
object RespawnSleep {

  /** A post waiting to be closed: which guild it belongs to, and the earliest
   *  moment it may be archived. */
  final case class Pending(guildId: String, threadId: String, dueAt: Instant)

  private val pending = new ConcurrentHashMap[String, Pending]()

  /** Note that a post has just been interacted with, pushing its close out to a
   *  full delay from now. Replaces any pending entry, which is what makes this a
   *  debounce rather than a queue — a burst of clicks closes once.
   *
   *  `delaySeconds` defaults to the configured window and is a parameter only so
   *  the tests can pin the arithmetic: `Config` reads a required `TOKEN` from
   *  the environment and cannot be loaded from a spec at all. A default argument
   *  is evaluated at the call site, so passing one keeps `Config` out of the
   *  test entirely rather than merely unused. */
  def touch(guildId: String, threadId: String, now: Instant = Instant.now(),
            delaySeconds: Long = Config.Respawn.closeDelay.toSeconds): Unit =
    if (guildId.nonEmpty && threadId.nonEmpty) {
      pending.put(threadId, Pending(guildId, threadId, now.plusSeconds(delaySeconds)))
      ()
    }

  /** The same, from an interaction that may have come from anywhere.
   *
   *  Only presses inside a forum post are worth writing down, and the check is
   *  two field reads against JDA's cache — cheap enough to sit on the event
   *  thread, where this is called. Which forum is not checked here: that needs
   *  the guild's settings, which is a database read, and the closer has to
   *  re-read the claim state anyway. So this over-collects slightly and
   *  [[RespawnService.closeIdleThreads]] throws out anything that turns out not
   *  to be one of ours.
   *
   *  Whether the respawn system is enabled at all is the caller's business —
   *  BotListener already knows, and checking there keeps entries from
   *  accumulating for a sweep that is never going to run.
   */
  def touched(guild: Guild, channel: Channel): Unit =
    (Option(guild), Option(channel)) match {
      case (Some(g), Some(thread: ThreadChannel)) if thread.getParentChannel.getType == ChannelType.FORUM =>
        touch(g.getId, thread.getId)
      case _ => ()
    }

  /** Everything in this guild that has been quiet long enough, removed from the
   *  map as it is handed over.
   *
   *  The two-argument `remove` is the point: a press that lands between the scan
   *  and the removal has already replaced the entry, so the compare fails, the
   *  new entry stays, and the post is left open for the caller who is still
   *  clicking on it. */
  def due(guildId: String, now: Instant = Instant.now()): List[Pending] = {
    val ready = pending.values.iterator.asScala
      .filter(entry => entry.guildId == guildId && !entry.dueAt.isAfter(now))
      .toList
    ready.filter(entry => pending.remove(entry.threadId, entry))
  }

  /** Whether a post is waiting on a close, so the reconciler can leave alone
   *  what the debounce is already about to handle. */
  def isPending(threadId: String): Boolean = pending.containsKey(threadId)

  def forget(threadId: String): Unit = { pending.remove(threadId); () }

  /** Drop entries nothing is ever going to drain.
   *
   *  Only guilds whose respawn forum this bot owns are swept, and only those
   *  call [[due]] — but a press can still reach a bot that then fails the
   *  ownership check, or lands on a guild whose settings have since been
   *  removed. Without this those entries would sit here for the life of the
   *  process. Anything this far past its due time has missed its chance anyway;
   *  the reconciler is what closes those posts. */
  def evictStale(now: Instant = Instant.now()): Int = {
    val cutoff = now.minusSeconds(StaleAfterSeconds)
    val stale = pending.values.iterator.asScala.filter(_.dueAt.isBefore(cutoff)).toList
    stale.count(entry => pending.remove(entry.threadId, entry))
  }

  /** How far past its due time an entry has to be before [[evictStale]] gives up
   *  on it. Generously longer than any sweep interval, so this only ever catches
   *  entries nothing is draining at all. */
  private val StaleAfterSeconds: Long = 60 * 60

  /** How many posts are waiting on a close, for logging. */
  def size: Int = pending.size

  /** Drop everything. Tests only — the map is process-wide. */
  private[respawn] def clear(): Unit = pending.clear()
}
