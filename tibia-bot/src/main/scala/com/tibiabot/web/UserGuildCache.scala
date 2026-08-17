package com.tibiabot.web

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

/** Which Discord guilds a signed-in visitor belongs to, remembered from their
 *  login so the dashboard doesn't have to ask Discord again on every request.
 *
 *  Ids only, deliberately. Discord's `/users/@me/guilds` also reports the
 *  caller's permission bitfield in each guild, and it is tempting to keep it —
 *  but the moderator role is invisible to OAuth, so permissions have to be
 *  resolved through JDA regardless. Storing them here would create a second,
 *  staler answer to a question that already has an authoritative one, and
 *  sooner or later something would read the wrong one. What is kept therefore
 *  carries no authority whatsoever: it can only ever *narrow* the handful of
 *  guilds worth resolving properly, never grant anything.
 *
 *  That is also what makes the TTL a UX decision rather than a security one. A
 *  stale entry can leave a guild off somebody's picker until they sign in
 *  again; it cannot let them act anywhere, because
 *  [[DashboardAccess.permits]] runs against freshly resolved access.
 *
 *  A miss is not a failure — the caller sends the visitor back through login,
 *  which is transparent when their Discord session is live, since the consent
 *  has already been given. That is why nothing here persists across a restart
 *  and why the OAuth token itself is never stored: holding user access tokens
 *  is a liability this only avoids by not needing them twice.
 */
final class UserGuildCache(ttl: FiniteDuration, now: () => Long = () => System.currentTimeMillis()) {

  import UserGuildCache.Entry

  private val entries = new ConcurrentHashMap[String, Entry]()

  def put(userId: String, guildIds: Set[String]): Unit =
    entries.put(userId, Entry(guildIds, now() + ttl.toMillis))

  /** None once the entry has aged out, so a caller cannot tell a stale answer
   *  from a fresh one by accident. */
  def get(userId: String): Option[Set[String]] =
    Option(entries.get(userId)).filter(_.expiresAt > now()).map(_.guildIds)

  /** Dropped on sign-out, so a shared machine doesn't hand the next person a
   *  hint about which servers the last one was in. */
  def invalidate(userId: String): Unit = entries.remove(userId)

  /** Clears everything already past its TTL. Nothing evicts on its own — the
   *  map is only written on login — so without this a long-running process
   *  would keep an entry per person who ever signed in. */
  def prune(): Unit = {
    val cutoff = now()
    entries.entrySet().asScala.filter(_.getValue.expiresAt <= cutoff)
      .foreach(e => entries.remove(e.getKey, e.getValue))
  }

  private[web] def size: Int = entries.size
}

object UserGuildCache {
  /** Outside the class so it carries no reference back to its enclosing
   *  instance — an inner case class cannot have that reference checked at run
   *  time, which the compiler warns about. */
  private final case class Entry(guildIds: Set[String], expiresAt: Long)
}
