package com.tibiabot.persistence

import com.typesafe.scalalogging.StrictLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.{Connection, SQLException}
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.jdk.CollectionConverters._
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
 *  guild nobody touches costs an empty pool object rather than a connection.
 *
 *  The pools share one housekeeping thread rather than starting one each, which
 *  is the difference between a thread per guild and a thread.
 *
 *  ==A ceiling on how many pools may hold connections==
 *  "A guild nobody touches" is the part that did not survive contact with the
 *  bot, and [[PooledConnectionProvider.MaxPools]] is the answer to it. Several
 *  things here walk the whole fleet — the guild roster republishes every thirty
 *  seconds, the respawn sweep runs on its own thirty-second beat, and startup
 *  reads every guild's world list — so no guild is ever idle for the thirty
 *  seconds it would take to give its connection back. One connection per guild,
 *  held for the life of the process, is what that adds up to; on a bot in a few
 *  hundred guilds it is Postgres' entire default budget of a hundred, and the
 *  first thing to notice was a second bot against the same server being unable
 *  to open a connection at all.
 *
 *  So a borrow that would leave more than `MaxPools` pools alive closes the ones
 *  nobody has asked for in longest first. What that costs a fleet walk is the
 *  handshake it was paying before pooling existed — three milliseconds a guild,
 *  once every thirty seconds — and what it keeps is the case pooling was for:
 *  a guild being used right now stays at the warm end of that ordering for as
 *  long as it is being used. Only pools with nothing checked out are closed,
 *  since Hikari's shutdown aborts connections that are still in a query.
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
  maxPools: Int = PooledConnectionProvider.MaxPools,
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
   *
   *  A pool that cannot be started at all is not remembered — `computeIfAbsent`
   *  records nothing when its function throws — so a guild whose database is
   *  created later is simply asked again.
   */
  private def borrow(url: String, maxSize: Int): Connection = {
    var attempt = 0
    var connection: Connection = null
    while (connection eq null) {
      val pool = unwrapped(pools.computeIfAbsent(url, u => new Pool(new HikariDataSource(configFor(u, maxSize)))))
      pool.lastUsed = System.currentTimeMillis()
      // Here rather than on the sweep's schedule because this is the moment the
      // ceiling can be crossed, and a fleet walk crosses it a few hundred times
      // in the second or so it takes to run — long before any timer would come
      // round to notice.
      if (pools.size > maxPools) trim(keep = pool)
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

  /** Hikari reports a database it cannot reach by wrapping the driver's own
   *  exception in a `PoolInitializationException`, which is a RuntimeException.
   *  The driver's is what callers are written against — `JdbcRespawnRepository.settings`
   *  reads a missing database off SQLState 3D000 and answers None, which is how
   *  the sweep asks every guild for its settings without a stack trace for each
   *  guild the bot was never set up in — so the wrapper is taken back off.
   *
   *  Only the wrapper. A failure that is not a SQLException underneath is not
   *  something this understands, and is left exactly as it arrived. */
  private def unwrapped(pool: => Pool): Pool =
    try pool
    catch {
      case e: com.zaxxer.hikari.pool.HikariPool.PoolInitializationException =>
        e.getCause match {
          case sql: SQLException => throw sql
          case _                 => throw e
        }
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
    if (pool ne null) shutdown(url, pool)
  }

  /** As [[discard]], but only when `pool` is still the one registered under
   *  `url` — so a pool started in the meantime is not closed in its place. */
  private def discardIf(url: String, pool: Pool): Unit =
    if (pools.remove(url, pool)) shutdown(url, pool)

  private def shutdown(url: String, pool: Pool): Unit = {
    pool.closed = true
    try pool.dataSource.close()
    catch { case NonFatal(e) => logger.warn(s"Closing the connection pool for '$url' failed: ${e.getMessage}") }
  }

  /** Whether a pool can be closed without taking a connection off somebody
   *  mid-query: Hikari's shutdown aborts whatever is still checked out.
   *
   *  A pool that cannot answer — closing already, or never started — reads as
   *  busy and is left alone, since the next borrow asks again anyway. */
  private def nothingCheckedOut(pool: Pool): Boolean =
    !pool.closed && (try {
      val bean = pool.dataSource.getHikariPoolMXBean
      (bean ne null) && bean.getActiveConnections == 0
    } catch { case NonFatal(_) => false })

  /** Bring the number of live pools back to [[maxPools]], closing the ones
   *  nobody has asked for in longest.
   *
   *  `keep` is the pool the borrow that called this is about to draw from. It is
   *  the most recently used of all of them and so sorts last, but it is also the
   *  one case where being closed is not merely wasteful but a borrow that has to
   *  start over — so it is excluded outright rather than by argument.
   *
   *  Every pool being busy is a legitimate answer to which nothing is closed:
   *  the ceiling is on connections held for nothing, and a connection in a query
   *  is not that. The next borrow tries again.
   */
  private def trim(keep: Pool): Unit = {
    var excess = pools.size - maxPools
    if (excess > 0) {
      val coldest = pools.entrySet().asScala.toVector
        .filter(entry => (entry.getValue ne keep) && nothingCheckedOut(entry.getValue))
        .sortBy(_.getValue.lastUsed)
      val candidates = coldest.iterator
      while (excess > 0 && candidates.hasNext) {
        val entry = candidates.next()
        discardIf(entry.getKey, entry.getValue)
        excess -= 1
      }
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

  /** Whether the thread every pool keeps house on is still running. Only a test
   *  asks: Hikari tears down its housekeeping when a pool fails to start, and
   *  the whole point of handing it one of ours is that a single unreachable
   *  guild must not stop every other pool from evicting its idle connections. */
  private[persistence] def housekeepingAlive: Boolean = !housekeeping.isShutdown

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

  /** How many pools may be alive at once, and so the ceiling on connections
   *  this process holds while doing nothing with them.
   *
   *  Sized for the guilds actually in use at a moment rather than the guilds the
   *  bot is in: a claim, a dashboard open, a command. Everything above that is a
   *  fleet walk passing through, and a fleet walk gets no benefit from a warm
   *  connection — it asks each guild one question every thirty seconds and does
   *  not come back. Deliberately well under Postgres' default `max_connections`
   *  of 100 even with [[MaxForShared]] alongside it and a second bot sharing the
   *  same server, which is the configuration that found this.
   */
  val MaxPools: Int = 16

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
