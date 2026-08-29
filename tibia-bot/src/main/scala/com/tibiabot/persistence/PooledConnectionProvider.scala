package com.tibiabot.persistence

import com.typesafe.scalalogging.StrictLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.{Connection, SQLException}
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.util.control.NonFatal

/** A [[ConnectionProvider]] that keeps its connections instead of throwing them
 *  away.
 *
 *  [[JdbcConnectionProvider]] opens a connection per query and closes it again.
 *  Against a Postgres 16 asking for `scram-sha-256` that is a TCP handshake, an
 *  SSL probe, a four-message SCRAM exchange and a forked backend before a byte
 *  of SQL is sent — measured with pgbench on the development machine at 3.10ms
 *  a query against 0.139ms with the connection already open. Better than nine
 *  tenths of the cost of a small query was the greeting.
 *
 *  It adds up where the reads are small and numerous, which is the whole of the
 *  dashboard: opening one spawn's card was seven of these.
 *
 *  ==A pool per database==
 *  Every guild has a database of its own (`_<guildId>`), so "the pool" is really
 *  one pool per guild, started the first time that guild is asked about. They
 *  are deliberately allowed to hold nothing: `minimumIdle` is zero and an idle
 *  connection is dropped after [[PooledConnectionProvider.IdleTimeout]], so a
 *  quiet guild costs an empty pool object rather than a connection. That is what
 *  keeps a bot in many guilds off Postgres' whole connection budget — the steady
 *  state is about the number of threads actually running queries, which is what
 *  it was before pooling, held for half a minute longer.
 *
 *  The pools share one housekeeping thread rather than starting one each, which
 *  is the difference between a thread per guild and a thread.
 *
 *  ==Pools that stop being used==
 *  A guild nobody has asked about for [[PooledConnectionProvider.PoolIdle]] has
 *  its pool closed and forgotten, so the map cannot grow for the life of the
 *  process. A borrow arriving at the same moment as that sweep can find itself
 *  holding a pool that has just been closed; it asks for a new one and tries
 *  again, which is cheaper to do than to reason about.
 *
 *  ==What is deliberately not pooled==
 *  [[admin]] and [[premium]] are maintenance connections used a handful of times
 *  at startup and on a guild join, where a pool would be machinery for nothing.
 *  [[guildUnpooled]] is unpooled for a reason of its own — see the trait.
 */
final class PooledConnectionProvider(
  host: String,
  password: String,
  user: String = "postgres",
  port: Int = 5432,
  maxPerDatabase: Int = PooledConnectionProvider.MaxPerDatabase,
  poolIdleMillis: Long = PooledConnectionProvider.PoolIdle,
  unpooled: ConnectionProvider = null
) extends ConnectionProvider with StrictLogging {

  /** Where the unpooled connections come from. Injectable so a test can watch
   *  what actually reaches the driver; ordinary construction builds the plain
   *  provider from the same host and credentials. */
  private val direct: ConnectionProvider =
    if (unpooled ne null) unpooled else new JdbcConnectionProvider(host, password, user, port)

  private final class Pool(val dataSource: HikariDataSource) {
    @volatile var lastUsed: Long = System.currentTimeMillis()
    @volatile var closed: Boolean = false
  }

  private val pools = new ConcurrentHashMap[String, Pool]()

  private def daemons(name: String): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val thread = new Thread(r, name)
        thread.setDaemon(true)
        thread
      }
    })

  /** Hikari's own periodic work, for every pool at once. */
  private val housekeeping = daemons("jdbc-pool-keeper")

  /** Ours, which closes pools — and so must not be the thread those pools are
   *  keeping house on, since closing one waits for its housekeeping to stop. */
  private val sweeper = daemons("jdbc-pool-sweep")

  // On a period of its own rather than on the idle window it enforces. The two
  // are different questions — how long a pool may be unwanted, and how often we
  // go and look — and tying them together meant a window of nothing at all could
  // not be expressed at all, since a schedule has to have a period.
  sweeper.scheduleWithFixedDelay(() => sweepQuietly(),
    PooledConnectionProvider.SweepPeriod, PooledConnectionProvider.SweepPeriod, TimeUnit.MILLISECONDS)

  def guild(guildId: String): Connection = borrow(JdbcUrls.guild(host, guildId, port), maxPerDatabase)
  override def guildUnpooled(guildId: String): Connection = direct.guild(guildId)
  // Wider than a guild's, because it is not a guild's: `bot_cache` is one
  // database serving the whole bot, and every world being scanned writes its
  // characters, deaths and levels into it at once. A ceiling sized for one
  // guild's dashboard would be the narrowest part of the world cycle.
  def cache(): Connection = borrow(JdbcUrls.cache(host, port), PooledConnectionProvider.MaxForShared)
  def admin(): Connection = direct.admin()
  def premium(): Connection = direct.premium()

  override def evictGuild(guildId: String): Unit = discard(JdbcUrls.guild(host, guildId, port))

  /** A connection from the pool for `url`, starting one if this is the first ask.
   *
   *  The retry is for the sweep: a pool taken from the map a moment before it
   *  was closed hands out an error rather than a connection, and the answer to
   *  that is the pool that replaced it. Bounded, so a database that is genuinely
   *  refusing connections fails as itself rather than spinning.
   */
  private def borrow(url: String, maxSize: Int): Connection = {
    var attempt = 0
    var connection: Connection = null
    while (connection eq null) {
      val pool = pools.computeIfAbsent(url, u => new Pool(new HikariDataSource(configFor(u, maxSize))))
      pool.lastUsed = System.currentTimeMillis()
      if (!pool.closed) connection = pool.dataSource.getConnection()
      else {
        // Swept between the lookup and here. Take it out of the map if it is
        // still the one there, so the next pass starts a live one.
        pools.remove(url, pool)
        attempt += 1
        if (attempt > PooledConnectionProvider.BorrowAttempts)
          throw new SQLException(s"could not obtain a pooled connection to '$url'")
      }
    }
    connection
  }

  /** Hikari's own defaults, minus the ones that assume a pool serving a single
   *  busy application rather than one of many serving a quiet guild. */
  private def configFor(url: String, maxSize: Int): HikariConfig = {
    val config = new HikariConfig()
    config.setJdbcUrl(url)
    config.setUsername(user)
    config.setPassword(password)
    // Named after the database, so a Hikari log line says which guild it is about.
    config.setPoolName(url.substring(url.lastIndexOf('/') + 1))
    config.setMaximumPoolSize(maxSize)
    // Nothing kept warm. Almost every guild is idle at any moment, and a pool
    // per guild holding even one connection each is how a bot in three hundred
    // guilds runs a database out of connections while doing nothing at all.
    config.setMinimumIdle(0)
    config.setIdleTimeout(PooledConnectionProvider.IdleTimeout)
    config.setMaxLifetime(PooledConnectionProvider.MaxLifetime)
    config.setConnectionTimeout(PooledConnectionProvider.ConnectionTimeout)
    config.setValidationTimeout(PooledConnectionProvider.ValidationTimeout)
    // One thread for every pool rather than one each — see the class comment.
    config.setScheduledExecutor(housekeeping)
    config
  }

  /** Close a pool and forget it, whatever state it is in. */
  private def discard(url: String): Unit = {
    val pool = pools.remove(url)
    if (pool ne null) {
      pool.closed = true
      try pool.dataSource.close()
      catch { case NonFatal(e) => logger.warn(s"Closing the connection pool for '$url' failed: ${e.getMessage}") }
    }
  }

  /** Drop pools nobody has asked for in a while. */
  private[persistence] def sweep(): Unit = {
    val cutoff = System.currentTimeMillis() - poolIdleMillis
    pools.forEach((url, pool) => if (pool.lastUsed < cutoff) discard(url))
  }

  /** As [[sweep]], but as a scheduled task: a throw from one of these cancels
   *  the schedule silently, and a pool that would not close is not a reason to
   *  stop sweeping the rest of them for the life of the process. */
  private def sweepQuietly(): Unit =
    try sweep()
    catch { case NonFatal(e) => logger.warn(s"Sweeping idle connection pools failed: ${e.getMessage}") }

  private[persistence] def size: Int = pools.size

  /** Let go of everything. Nothing calls this in the bot — the process ends and
   *  the connections end with it — but a test that makes a provider should be
   *  able to put it down again. */
  def close(): Unit = {
    pools.forEach((url, _) => discard(url))
    sweeper.shutdownNow()
    housekeeping.shutdownNow()
    ()
  }
}

object PooledConnectionProvider {
  /** How many connections one guild's database may have at once.
   *
   *  A ceiling rather than a target: the pool holds none when nothing is
   *  happening. Wide enough for every thread that can be querying one guild
   *  together — the dashboard's read pool is twelve on its own — so a burst
   *  queues on Postgres rather than in front of the pool.
   */
  val MaxPerDatabase: Int = 16

  /** The same, for `bot_cache` — one database behind the whole bot rather than
   *  one guild. Every world's poll writes into it, so what it has to cover is
   *  the world cycle's own width rather than a dashboard's. */
  val MaxForShared: Int = 32

  /** How long a connection nobody wants is kept before being closed. Long
   *  enough to cover a dashboard's ten-second poll and the burst around it,
   *  short enough that a guild going quiet gives its connections back. */
  val IdleTimeout: Long = 30000L

  /** Retired before anything in the middle retires it for us. Well under the
   *  half hour a Postgres or a proxy typically allows. */
  val MaxLifetime: Long = 25L * 60L * 1000L

  /** How long a caller waits for a connection before being told it cannot have
   *  one. Shorter than Hikari's own thirty seconds: a request parked that long
   *  has already failed as far as whoever made it is concerned. */
  val ConnectionTimeout: Long = 15000L

  val ValidationTimeout: Long = 3000L

  /** How long a whole pool is kept after the last time its guild was asked
   *  about. It holds no connections by then; this is only about the map not
   *  growing for the life of the process. */
  val PoolIdle: Long = 10L * 60L * 1000L

  /** How often the sweep runs, which is the most a pool outlives [[PoolIdle]]
   *  by. Empty pools are cheap, so this is deliberately lazy. */
  val SweepPeriod: Long = 60000L

  /** How many times a borrow accepts "that pool has just been closed" before
   *  giving up. One more than the sweep can cause. */
  val BorrowAttempts: Int = 3
}
