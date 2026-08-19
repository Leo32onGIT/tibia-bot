package com.tibiabot
package persistence

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/** Minimal key/value cache port. Implemented by a Lettuce-backed Redis client in
 *  prod, an in-memory map in tests, and a no-op when Redis is unconfigured.
 *  All operations are best-effort: implementations must never fail the caller's
 *  Future on a cache error (degrade to a miss / no-op instead). */
trait RedisCache {
  def get(key: String): Future[Option[String]]
  def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit]

  /** Set `key` only if nothing holds it, answering whether this caller won.
   *
   *  The one primitive here that is not merely an optimisation: the respawn
   *  command relay uses it to decide which process executes a command, and
   *  "did I win" has to be atomic or the same claim could be performed twice.
   *  Distinct from [[setEx]] for that reason — a get-then-set would have a
   *  window between the two wide enough to lose in.
   *
   *  A cache that cannot answer must say `false` rather than `true`: refusing
   *  to run a command is recoverable, running it twice is not. */
  def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean]

  /** Forget `key` now rather than at its TTL.
   *
   *  For a key that is a piece of work rather than a cached value: once it has
   *  been done, leaving it to expire means everything sweeping for work finds
   *  it again and does it again. [[AccessQueryConsumer]] is the case in point —
   *  an answered question left lying around was re-resolved on every beat until
   *  it expired, at the cost of a Discord REST call each time.
   *
   *  Missing keys are not an error: deleting one twice, or one that was never
   *  there, succeeds quietly. */
  def delete(key: String): Future[Unit]

  /** Discovers keys by prefix pattern (e.g. `tibia:secondary-status:*`) —
   *  used by a shared-world-cycle primary to find however many secondaries
   *  are currently publishing, without needing to know their names in
   *  advance. The matched keyspace here is always small (a handful of
   *  secondary-status entries), so a plain KEYS is fine; not meant for
   *  scanning the whole cache. */
  def keysMatching(pattern: String): Future[List[String]]

  /** Fan `message` out to whoever is listening on `channel` at this instant,
   *  answering how many that was.
   *
   *  Nothing is stored: a message published to nobody is simply gone. That
   *  suits a question with a deadline — see [[com.tibiabot.web.AccessQuery]] —
   *  far better than a key does, because an answer that arrives after the asker
   *  has given up was worthless anyway, and it costs no `KEYS` sweep to notice.
   *
   *  The count is worth having rather than discarding. A publish that reached
   *  nobody is a definite "the bot that runs this guild is not listening", known
   *  in one round trip, where waiting out the timeout to learn the same thing
   *  costs the visitor the whole deadline.
   *
   *  Defaults to reaching nobody, so an implementation without pub/sub degrades
   *  to the key-based path rather than silently dropping questions. */
  def publish(channel: String, message: String): Future[Long] = Future.successful(0L)

  /** Hand every message published to `channel` to `onMessage`, for as long as
   *  this process lives.
   *
   *  Delivery is best effort and unordered with respect to everything else:
   *  a subscriber that is briefly disconnected misses whatever was published
   *  meanwhile, which for a question with a deadline is the same outcome as
   *  being too slow to answer it.
   *
   *  Fails the returned Future when the subscription could not be set up, which
   *  the caller must treat as "cannot answer this way" rather than ignore —
   *  a bot that believes it is listening and is not would have every other bot
   *  waiting out a deadline on it.
   *
   *  Defaults to never delivering anything. */
  def subscribe(channel: String)(onMessage: String => Unit): Future[Unit] = Future.unit

  def close(): Unit
}

/** Disabled fallback: every get misses, every write is dropped. Used when no
 *  redis host is configured so the bot runs unchanged without a Redis container. */
object NoopRedisCache extends RedisCache {
  def get(key: String): Future[Option[String]] = Future.successful(None)
  def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = Future.unit
  /** Never wins: with no Redis there is nothing coordinating anything, and
   *  claiming otherwise would let a relayed command run unguarded. */
  def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] =
    Future.successful(false)
  def delete(key: String): Future[Unit] = Future.unit
  def keysMatching(pattern: String): Future[List[String]] = Future.successful(Nil)
  /** Never reaches anybody, and never hears anything: with no Redis there is no
   *  fleet to talk to. Inherited from the trait, and spelled out here because
   *  the relay reads a zero here as "nobody is listening", which is true. */
  override def publish(channel: String, message: String): Future[Long] = Future.successful(0L)
  override def subscribe(channel: String)(onMessage: String => Unit): Future[Unit] = Future.unit
  def close(): Unit = ()
}
