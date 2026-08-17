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
  def close(): Unit = ()
}
