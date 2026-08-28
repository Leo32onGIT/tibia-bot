package com.tibiabot.web

import com.tibiabot.persistence.{NoopRedisCache, RedisCache}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
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
 *  has already been given.
 *
 *  Kept in `store` as well as in memory, where there is one, because a miss is
 *  still a bounce and a restart used to cause one for everybody at once. The
 *  session cookie is signed rather than stored and so outlives a restart by
 *  design; the list behind it did not, which left people perfectly signed in
 *  and resolving to nothing until they logged in again. What is written is what
 *  is held here — guild ids, and the deadline they already had — so a restart
 *  restores the entry somebody would have had rather than a fresh one, and
 *  nothing lives a day longer for having been written down.
 *
 *  The OAuth token is still never stored, here or anywhere: holding user access
 *  tokens is a liability this avoids by not needing them twice, which is a
 *  separate matter from remembering an answer that grants nothing.
 */
final class UserGuildCache(ttl: FiniteDuration, now: () => Long = () => System.currentTimeMillis(),
                           /** Where the entries outlive this process. Absent by
                            *  default and wherever Redis is unconfigured, which
                            *  is the old behaviour exactly: memory only, and a
                            *  restart is a login. */
                           store: RedisCache = NoopRedisCache)(implicit ec: ExecutionContext) {

  import UserGuildCache.Entry

  private val entries = new ConcurrentHashMap[String, Entry]()

  private def key(userId: String): String = s"tibia:user-guilds:$userId"

  def put(userId: String, guildIds: Set[String]): Unit = {
    val entry = Entry(guildIds, now() + ttl.toMillis)
    entries.put(userId, entry)
    // Not waited on. A login Redis is too slow or too broken to record is a
    // login that behaves exactly the way every login did before this was
    // written.
    store.setEx(key(userId), Entry.encode(entry), ttl)
    ()
  }

  /** Reads this visitor back out of the store, if they are not in memory and
   *  the store has them. Called on the way into an authenticated route, so the
   *  synchronous [[get]] behind it can stay synchronous.
   *
   *  Costs nothing on a hit, which is every request after the first. A miss the
   *  store cannot answer either is left as a miss: what a caller does with one
   *  is send them to sign in, which is the right end for somebody whose entry
   *  has genuinely expired.
   */
  def warm(userId: String): Future[Unit] =
    if (get(userId).isDefined) Future.unit
    else store.get(key(userId)).map {
      case Some(raw) =>
        // The deadline travels with the ids rather than being restamped here.
        // Restamping would hand somebody a fresh week of a stale list every
        // time the process restarted, which is the one way this could come to
        // show a guild long after they left it.
        Entry.decode(raw).filter(_.expiresAt > now()).foreach(entries.put(userId, _))
      case None => ()
    }.recover { case _ => () }

  /** None once the entry has aged out, so a caller cannot tell a stale answer
   *  from a fresh one by accident. */
  def get(userId: String): Option[Set[String]] =
    Option(entries.get(userId)).filter(_.expiresAt > now()).map(_.guildIds)

  /** Dropped on sign-out, so a shared machine doesn't hand the next person a
   *  hint about which servers the last one was in. Dropped from the store as
   *  well, or the next request would warm it straight back in. */
  def invalidate(userId: String): Unit = {
    entries.remove(userId)
    store.delete(key(userId))
    ()
  }

  /** Clears everything already past its TTL. Nothing evicts on its own — the
   *  map is only written on a login or a warm — so without this a long-running
   *  process would keep an entry per person who ever signed in. The store needs
   *  no such sweep: Redis expires its own keys. */
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

  private object Entry {
    /** `deadline|id,id,id`, with an empty tail for somebody in no guilds —
     *  which is a real answer here, and one that has to come back as itself
     *  rather than as an absence. Ids are snowflakes and the deadline is a
     *  number, so neither can contain a separator. */
    def encode(entry: Entry): String = s"${entry.expiresAt}|${entry.guildIds.mkString(",")}"

    /** None for anything that is not what [[encode]] wrote. A value this cannot
     *  read came from some other version of this code, and reading a miss out
     *  of it costs one sign-in where guessing could cost a wrong answer. */
    def decode(raw: String): Option[Entry] = raw.split("\\|", 2) match {
      case Array(deadline, ids) =>
        scala.util.Try(deadline.toLong).toOption
          .map(at => Entry(ids.split(",").filter(_.nonEmpty).toSet, at))
      case _ => None
    }
  }
}
