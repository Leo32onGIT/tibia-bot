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

  def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] =
    // SET .. NX PX, which is one round trip and genuinely atomic. Lettuce
    // answers null when NX declined, so a null is "somebody else has it".
    commands.set(key, value, io.lettuce.core.SetArgs.Builder.nx().px(ttl.toMillis))
      .asScala.map(_ != null).recover {
        case NonFatal(e) =>
          // Losing on error is the safe direction: the command simply is not
          // run by this process, rather than run twice.
          logger.warn(s"redis SET NX failed for '$key': ${e.getMessage}"); false
      }

  def delete(key: String): Future[Unit] =
    commands.del(key).asScala.map(_ => ()).recover {
      case NonFatal(e) => logger.warn(s"redis DEL failed for '$key': ${e.getMessage}"); ()
    }

  def keysMatching(pattern: String): Future[List[String]] =
    commands.keys(pattern).asScala.map(_.asScala.toList).recover {
      case NonFatal(e) => logger.warn(s"redis KEYS failed for pattern '$pattern': ${e.getMessage}"); Nil
    }

  /** Pub/sub needs a connection of its own: once a Redis connection is
   *  subscribed it will not carry ordinary commands, so sharing the multiplexed
   *  one above would take every GET and SETEX in the process down with the
   *  first subscription. Opened lazily, so a deployment that never subscribes
   *  never pays for it. */
  private lazy val pubSub: io.lettuce.core.pubsub.StatefulRedisPubSubConnection[String, String] =
    client.connectPubSub()

  override def publish(channel: String, message: String): Future[Long] =
    commands.publish(channel, message).asScala.map(_.longValue()).recover {
      case NonFatal(e) =>
        // Zero is the honest answer here as well as the safe one: we do not
        // know that anybody received this, and the caller treats "nobody" as a
        // reason to stop waiting rather than as an error.
        logger.warn(s"redis PUBLISH failed for '$channel': ${e.getMessage}"); 0L
    }

  override def subscribe(channel: String)(onMessage: String => Unit): Future[Unit] = {
    pubSub.addListener(new io.lettuce.core.pubsub.RedisPubSubAdapter[String, String] {
      override def message(onChannel: String, body: String): Unit =
        // One connection carries every subscription this process makes, so a
        // listener hears all of them and has to pick out its own.
        if (onChannel == channel)
          // A listener that throws would be swallowed by Lettuce and take the
          // rest of the delivery with it, so nothing is allowed out of here.
          try onMessage(body) catch {
            case NonFatal(e) => logger.warn(s"Dropped a message on '$channel': ${e.getMessage}")
          }
    })
    // Deliberately not recovered to a success: a caller that believes it is
    // listening and is not would have the whole fleet waiting out deadlines on
    // it. Lettuce re-subscribes its channels itself after a reconnect, so this
    // is set up once and stays up.
    pubSub.async().subscribe(channel).asScala.map(_ => ())
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
