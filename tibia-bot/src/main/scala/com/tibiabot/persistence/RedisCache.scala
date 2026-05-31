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
  def close(): Unit
}

/** Disabled fallback: every get misses, every write is dropped. Used when no
 *  redis host is configured so the bot runs unchanged without a Redis container. */
object NoopRedisCache extends RedisCache {
  def get(key: String): Future[Option[String]] = Future.successful(None)
  def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = Future.unit
  def close(): Unit = ()
}
