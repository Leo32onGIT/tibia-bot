package com.tibiabot
package persistence

import com.typesafe.scalalogging.StrictLogging
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.{RedisClient, RedisURI}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.util.control.NonFatal

/** Lettuce-backed RedisCache. One client + multiplexed connection is shared
 *  across all per-world callers (Lettuce connections are thread-safe). Every
 *  operation recovers cache errors to a miss / no-op so a Redis hiccup can never
 *  take down the bot — the cache is strictly an optimisation. */
final class LettuceRedisCache(host: String, port: Int, password: String)(implicit ec: ExecutionContext)
    extends RedisCache with StrictLogging {

  private val uri = {
    val builder = RedisURI.builder().withHost(host).withPort(port)
    if (password.nonEmpty) builder.withPassword(password.toCharArray)
    builder.build()
  }
  private val client: RedisClient = RedisClient.create(uri)
  private val connection: StatefulRedisConnection[String, String] = client.connect()
  private val commands: RedisAsyncCommands[String, String] = connection.async()

  logger.info(s"Redis cache connected to $host:$port")

  def get(key: String): Future[Option[String]] =
    commands.get(key).asScala.map(Option(_)).recover {
      case NonFatal(e) => logger.warn(s"redis GET failed for '$key': ${e.getMessage}"); None
    }

  def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] =
    commands.psetex(key, ttl.toMillis, value).asScala.map(_ => ()).recover {
      case NonFatal(e) => logger.warn(s"redis PSETEX failed for '$key': ${e.getMessage}"); ()
    }

  def keysMatching(pattern: String): Future[List[String]] =
    commands.keys(pattern).asScala.map(_.asScala.toList).recover {
      case NonFatal(e) => logger.warn(s"redis KEYS failed for pattern '$pattern': ${e.getMessage}"); Nil
    }

  def close(): Unit = {
    connection.close()
    client.shutdown()
  }
}

/** Builds the single shared RedisCache from Config: a real Lettuce client when a
 *  redis host is configured, else the no-op so the bot runs unchanged.
 *
 *  Deliberately a JVM-wide singleton rather than constructor-injected: its only
 *  consumer, CachingTibiaApi, is self-constructed independently at three sites
 *  (BotApp, WorldManager, each per-world TibiaBot), so threading one instance
 *  through them would require de-objecting WorldManager and adding a TibiaApi
 *  param to TibiaBot. Guarantees every per-world cache shares one Redis
 *  connection and the same keys. Lives for the process lifetime; close() exists
 *  for the port contract and tests but is never driven in prod. */
object RedisCacheProvider {
  lazy val cache: RedisCache =
    if (Config.redisEnabled)
      new LettuceRedisCache(Config.redisHost, Config.redisPort, Config.redisPassword)(ExecutionContext.global)
    else
      NoopRedisCache
}
