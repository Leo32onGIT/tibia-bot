package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSettings, Stamina}
import com.tibiabot.persistence.{ConnectionProvider, RespawnRepository}

import java.sql.{Connection, ResultSet, Timestamp}
import java.time.{ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of RespawnRepository against a guild's own database.
 *
 *  Every entry point calls [[ensureTables]] first. `SchemaInitializer.initGuild`
 *  only creates tables when it creates the database, so guilds that existed
 *  before this feature would otherwise never get them — the same
 *  create-on-read approach `JdbcGalthenRepository` uses for `satchel`.
 *
 *  Timestamps are `TIMESTAMPTZ`, not the plain `TIMESTAMP` the older tables
 *  use. Everything here is a deadline that has to survive a container timezone
 *  change or a daylight-saving shift without silently moving a claim's end
 *  time, and these are new tables so there's no migration cost to getting it
 *  right.
 */
final class JdbcRespawnRepository(connectionProvider: ConnectionProvider) extends RespawnRepository {

  private def connect(guildId: String): () => Connection = () => connectionProvider.guild(guildId)

  private def withGuild[A](guildId: String)(use: Connection => A): A =
    JdbcSupport.withConnection(connect(guildId)) { conn => ensureTables(conn); use(conn) }

  private def withGuildTransaction[A](guildId: String)(use: Connection => A): A =
    JdbcSupport.withTransaction(connect(guildId)) { conn => ensureTables(conn); use(conn) }

  private def ensureTables(conn: Connection): Unit = {
    val statement = conn.createStatement()
    try {
      statement.executeUpdate(
        """CREATE TABLE IF NOT EXISTS respawn_settings (
          |id INT PRIMARY KEY,
          |forum_channel VARCHAR(255) NOT NULL DEFAULT '0',
          |board_thread VARCHAR(255) NOT NULL DEFAULT '0',
          |default_duration INT NOT NULL DEFAULT 120,
          |max_duration INT NOT NULL DEFAULT 240,
          |queue_limit INT NOT NULL DEFAULT 20,
          |stamina_minutes INT NOT NULL DEFAULT 240,
          |warn_minutes INT NOT NULL DEFAULT 10
          |);""".stripMargin)

      statement.executeUpdate(
        """CREATE TABLE IF NOT EXISTS respawns (
          |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          |code VARCHAR(32) NOT NULL UNIQUE,
          |name VARCHAR(255) NOT NULL,
          |creature VARCHAR(255) NOT NULL DEFAULT '',
          |region VARCHAR(255) NOT NULL DEFAULT '',
          |world VARCHAR(255) NOT NULL DEFAULT '',
          |mapper_link VARCHAR(512) NOT NULL DEFAULT '',
          |thread_id VARCHAR(255) NOT NULL DEFAULT '',
          |source VARCHAR(16) NOT NULL DEFAULT 'custom',
          |added_by VARCHAR(255) NOT NULL DEFAULT ''
          |);""".stripMargin)

      statement.executeUpdate(
        """CREATE TABLE IF NOT EXISTS respawn_claims (
          |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          |respawn_id BIGINT NOT NULL,
          |user_id VARCHAR(255) NOT NULL,
          |user_name VARCHAR(255) NOT NULL DEFAULT '',
          |character_name VARCHAR(255) NOT NULL DEFAULT '',
          |status VARCHAR(16) NOT NULL,
          |queue_position INT NOT NULL DEFAULT 0,
          |claimed_at TIMESTAMPTZ NOT NULL,
          |starts_at TIMESTAMPTZ,
          |ends_at TIMESTAMPTZ,
          |duration_minutes INT NOT NULL,
          |warned BOOLEAN NOT NULL DEFAULT FALSE,
          |kind VARCHAR(16) NOT NULL DEFAULT 'adhoc'
          |);""".stripMargin)

      // The sweep scans by (status, ends_at) every 30 seconds and every claim
      // read is "this spawn's active row / this spawn's queue" — both hot
      // enough to be worth indexing from day one.
      statement.executeUpdate(
        "CREATE INDEX IF NOT EXISTS respawn_claims_by_respawn ON respawn_claims (respawn_id, status);")
      statement.executeUpdate(
        "CREATE INDEX IF NOT EXISTS respawn_claims_by_deadline ON respawn_claims (status, ends_at);")
      statement.executeUpdate(
        "CREATE INDEX IF NOT EXISTS respawn_claims_by_user ON respawn_claims (user_id, status);")

      statement.executeUpdate(
        """CREATE TABLE IF NOT EXISTS respawn_stamina (
          |user_id VARCHAR(255) PRIMARY KEY,
          |used_minutes INT NOT NULL DEFAULT 0,
          |reset_at TIMESTAMPTZ NOT NULL
          |);""".stripMargin)
    } finally statement.close()
  }

  private def toZoned(timestamp: Timestamp): ZonedDateTime =
    timestamp.toInstant.atZone(ZoneOffset.UTC)

  private def optionalZoned(result: ResultSet, column: String): Option[ZonedDateTime] =
    Option(result.getTimestamp(column)).map(toZoned)

  private def readRespawn(result: ResultSet): Respawn =
    Respawn(
      id = result.getLong("id"),
      code = result.getString("code"),
      name = result.getString("name"),
      creature = Option(result.getString("creature")).getOrElse(""),
      region = Option(result.getString("region")).getOrElse(""),
      world = Option(result.getString("world")).getOrElse(""),
      mapperLink = Option(result.getString("mapper_link")).getOrElse(""),
      threadId = Option(result.getString("thread_id")).getOrElse(""),
      source = Option(result.getString("source")).getOrElse(Respawn.SourceCustom),
      addedBy = Option(result.getString("added_by")).getOrElse("")
    )

  private def readClaim(result: ResultSet): RespawnClaim =
    RespawnClaim(
      id = result.getLong("id"),
      respawnId = result.getLong("respawn_id"),
      userId = result.getString("user_id"),
      userName = Option(result.getString("user_name")).getOrElse(""),
      characterName = Option(result.getString("character_name")).getOrElse(""),
      status = result.getString("status"),
      queuePosition = result.getInt("queue_position"),
      claimedAt = toZoned(result.getTimestamp("claimed_at")),
      startsAt = optionalZoned(result, "starts_at"),
      endsAt = optionalZoned(result, "ends_at"),
      durationMinutes = result.getInt("duration_minutes"),
      warned = result.getBoolean("warned"),
      kind = Option(result.getString("kind")).getOrElse(RespawnClaim.KindAdHoc)
    )

  private def collectClaims(result: ResultSet): List[RespawnClaim] = {
    val claims = ListBuffer[RespawnClaim]()
    while (result.next()) claims += readClaim(result)
    claims.toList
  }

  // --- settings -----------------------------------------------------------

  def settings(guildId: String): Option[RespawnSettings] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("SELECT * FROM respawn_settings WHERE id = 1;")
    try {
      val result = statement.executeQuery()
      if (result.next()) Some(RespawnSettings(
        forumChannel = result.getString("forum_channel"),
        boardThread = result.getString("board_thread"),
        defaultDurationMinutes = result.getInt("default_duration"),
        maxDurationMinutes = result.getInt("max_duration"),
        queueLimit = result.getInt("queue_limit"),
        staminaMinutes = result.getInt("stamina_minutes"),
        warnMinutes = result.getInt("warn_minutes")
      )) else None
    } finally statement.close()
  }

  def saveSettings(guildId: String, settings: RespawnSettings): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """INSERT INTO respawn_settings
        |(id, forum_channel, board_thread, default_duration, max_duration, queue_limit, stamina_minutes, warn_minutes)
        |VALUES (1, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |forum_channel = EXCLUDED.forum_channel,
        |board_thread = EXCLUDED.board_thread,
        |default_duration = EXCLUDED.default_duration,
        |max_duration = EXCLUDED.max_duration,
        |queue_limit = EXCLUDED.queue_limit,
        |stamina_minutes = EXCLUDED.stamina_minutes,
        |warn_minutes = EXCLUDED.warn_minutes;""".stripMargin)
    try {
      statement.setString(1, settings.forumChannel)
      statement.setString(2, settings.boardThread)
      statement.setInt(3, settings.defaultDurationMinutes)
      statement.setInt(4, settings.maxDurationMinutes)
      statement.setInt(5, settings.queueLimit)
      statement.setInt(6, settings.staminaMinutes)
      statement.setInt(7, settings.warnMinutes)
      statement.executeUpdate()
    } finally statement.close()
  }

  def updateChannels(guildId: String, forumChannel: String, boardThread: String): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "UPDATE respawn_settings SET forum_channel = ?, board_thread = ? WHERE id = 1;")
    try {
      statement.setString(1, forumChannel)
      statement.setString(2, boardThread)
      statement.executeUpdate()
    } finally statement.close()
  }

  // --- catalogue ----------------------------------------------------------

  def listRespawns(guildId: String): List[Respawn] = withGuild(guildId) { conn =>
    val statement = conn.createStatement()
    try {
      val result = statement.executeQuery("SELECT * FROM respawns;")
      val respawns = ListBuffer[Respawn]()
      while (result.next()) respawns += readRespawn(result)
      respawns.toList
    } finally statement.close()
  }

  def findByCode(guildId: String, code: String): Option[Respawn] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("SELECT * FROM respawns WHERE LOWER(code) = LOWER(?);")
    try {
      statement.setString(1, code)
      val result = statement.executeQuery()
      if (result.next()) Some(readRespawn(result)) else None
    } finally statement.close()
  }

  def findById(guildId: String, respawnId: Long): Option[Respawn] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("SELECT * FROM respawns WHERE id = ?;")
    try {
      statement.setLong(1, respawnId)
      val result = statement.executeQuery()
      if (result.next()) Some(readRespawn(result)) else None
    } finally statement.close()
  }

  def addRespawn(guildId: String, code: String, name: String, creature: String, region: String,
                 world: String, mapperLink: String, source: String, addedBy: String): Respawn =
    withGuild(guildId) { conn =>
      // DO NOTHING rather than DO UPDATE: a duplicate code means the caller is
      // re-adding something that already exists, and silently overwriting a
      // guild's own edits with the new arguments would lose their data.
      val insert = conn.prepareStatement(
        """INSERT INTO respawns (code, name, creature, region, world, mapper_link, source, added_by)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT (code) DO NOTHING;""".stripMargin)
      try {
        insert.setString(1, code)
        insert.setString(2, name)
        insert.setString(3, creature)
        insert.setString(4, region)
        insert.setString(5, world)
        insert.setString(6, mapperLink)
        insert.setString(7, source)
        insert.setString(8, addedBy)
        insert.executeUpdate()
      } finally insert.close()

      val select = conn.prepareStatement("SELECT * FROM respawns WHERE LOWER(code) = LOWER(?);")
      try {
        select.setString(1, code)
        val result = select.executeQuery()
        if (result.next()) readRespawn(result)
        else throw new IllegalStateException(s"respawn '$code' vanished immediately after insert")
      } finally select.close()
    }

  def updateRespawn(guildId: String, respawnId: Long, name: Option[String], creature: Option[String],
                    world: Option[String], mapperLink: Option[String]): Unit = withGuild(guildId) { conn =>
    // COALESCE(?, column) leaves a field alone when its argument is None, so
    // `/respawn admin edit` can set one attribute without restating the rest.
    val statement = conn.prepareStatement(
      """UPDATE respawns SET
        |name = COALESCE(?, name),
        |creature = COALESCE(?, creature),
        |world = COALESCE(?, world),
        |mapper_link = COALESCE(?, mapper_link)
        |WHERE id = ?;""".stripMargin)
    try {
      def setOptional(index: Int, value: Option[String]): Unit =
        value match {
          case Some(text) => statement.setString(index, text)
          case None       => statement.setNull(index, java.sql.Types.VARCHAR)
        }
      setOptional(1, name)
      setOptional(2, creature)
      setOptional(3, world)
      setOptional(4, mapperLink)
      statement.setLong(5, respawnId)
      statement.executeUpdate()
    } finally statement.close()
  }

  def removeRespawn(guildId: String, respawnId: Long): Unit = withGuildTransaction(guildId) { conn =>
    val claims = conn.prepareStatement("DELETE FROM respawn_claims WHERE respawn_id = ?;")
    try { claims.setLong(1, respawnId); claims.executeUpdate() } finally claims.close()
    val respawn = conn.prepareStatement("DELETE FROM respawns WHERE id = ?;")
    try { respawn.setLong(1, respawnId); respawn.executeUpdate() } finally respawn.close()
  }

  def setThreadId(guildId: String, respawnId: Long, threadId: String): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("UPDATE respawns SET thread_id = ? WHERE id = ?;")
    try {
      statement.setString(1, threadId)
      statement.setLong(2, respawnId)
      statement.executeUpdate()
    } finally statement.close()
  }

  def importSeed(guildId: String, spawns: List[(String, String, String, String)]): Int =
    withGuildTransaction(guildId) { conn =>
      val statement = conn.prepareStatement(
        """INSERT INTO respawns (code, name, creature, region, source, added_by)
          |VALUES (?, ?, ?, ?, 'seed', 'seed')
          |ON CONFLICT (code) DO NOTHING;""".stripMargin)
      try {
        spawns.foreach { case (code, region, name, creature) =>
          statement.setString(1, code)
          statement.setString(2, name)
          statement.setString(3, creature)
          statement.setString(4, region)
          statement.addBatch()
        }
        // Each element is 1 when the row was inserted and 0 when the code was
        // already present, so the sum is exactly "how many are new".
        statement.executeBatch().sum
      } finally statement.close()
    }

  // --- claims -------------------------------------------------------------

  def activeClaim(guildId: String, respawnId: Long): Option[RespawnClaim] = withGuild(guildId) { conn =>
    activeClaimOn(conn, respawnId)
  }

  private def activeClaimOn(conn: Connection, respawnId: Long): Option[RespawnClaim] = {
    val statement = conn.prepareStatement(
      "SELECT * FROM respawn_claims WHERE respawn_id = ? AND status = 'active' ORDER BY id LIMIT 1;")
    try {
      statement.setLong(1, respawnId)
      val result = statement.executeQuery()
      if (result.next()) Some(readClaim(result)) else None
    } finally statement.close()
  }

  def queueFor(guildId: String, respawnId: Long): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "SELECT * FROM respawn_claims WHERE respawn_id = ? AND status = 'queued' ORDER BY queue_position;")
    try {
      statement.setLong(1, respawnId)
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def allActiveClaims(guildId: String): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.createStatement()
    try collectClaims(statement.executeQuery(
      "SELECT * FROM respawn_claims WHERE status = 'active' ORDER BY ends_at;"))
    finally statement.close()
  }

  def openClaimsForUser(guildId: String, userId: String): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """SELECT * FROM respawn_claims
        |WHERE user_id = ? AND status IN ('active', 'queued')
        |ORDER BY status, ends_at NULLS LAST, queue_position;""".stripMargin)
    try {
      statement.setString(1, userId)
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def expiredClaims(guildId: String, now: ZonedDateTime): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "SELECT * FROM respawn_claims WHERE status = 'active' AND ends_at <= ? ORDER BY ends_at;")
    try {
      statement.setTimestamp(1, Timestamp.from(now.toInstant))
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def claimsNeedingWarning(guildId: String, now: ZonedDateTime, withinMinutes: Int): List[RespawnClaim] =
    withGuild(guildId) { conn =>
      val statement = conn.prepareStatement(
        """SELECT * FROM respawn_claims
          |WHERE status = 'active' AND warned = FALSE AND ends_at > ? AND ends_at <= ?
          |ORDER BY ends_at;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(now.toInstant))
        statement.setTimestamp(2, Timestamp.from(now.plusMinutes(withinMinutes.toLong).toInstant))
        collectClaims(statement.executeQuery())
      } finally statement.close()
    }

  def insertActiveClaim(guildId: String, respawnId: Long, userId: String, userName: String,
                        characterName: String, startsAt: ZonedDateTime, endsAt: ZonedDateTime,
                        durationMinutes: Int, kind: String): RespawnClaim =
    withGuildTransaction(guildId) { conn =>
      lockRespawn(conn, respawnId)
      val statement = conn.prepareStatement(
        """INSERT INTO respawn_claims
          |(respawn_id, user_id, user_name, character_name, status, queue_position,
          | claimed_at, starts_at, ends_at, duration_minutes, warned, kind)
          |VALUES (?, ?, ?, ?, 'active', 0, ?, ?, ?, ?, FALSE, ?)
          |RETURNING *;""".stripMargin)
      try {
        statement.setLong(1, respawnId)
        statement.setString(2, userId)
        statement.setString(3, userName)
        statement.setString(4, characterName)
        statement.setTimestamp(5, Timestamp.from(startsAt.toInstant))
        statement.setTimestamp(6, Timestamp.from(startsAt.toInstant))
        statement.setTimestamp(7, Timestamp.from(endsAt.toInstant))
        statement.setInt(8, durationMinutes)
        statement.setString(9, kind)
        val result = statement.executeQuery()
        result.next()
        readClaim(result)
      } finally statement.close()
    }

  def enqueueClaim(guildId: String, respawnId: Long, userId: String, userName: String,
                   characterName: String, durationMinutes: Int, queueLimit: Int,
                   kind: String): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      // Serialised on the respawn row: without it two people clicking Next at
      // the same moment both read the same queue length and both take the same
      // position.
      lockRespawn(conn, respawnId)

      val existing = {
        val statement = conn.prepareStatement(
          "SELECT COUNT(*) FROM respawn_claims WHERE respawn_id = ? AND user_id = ? AND status IN ('active','queued');")
        try {
          statement.setLong(1, respawnId)
          statement.setString(2, userId)
          val result = statement.executeQuery()
          result.next()
          result.getInt(1)
        } finally statement.close()
      }

      val queued = {
        val statement = conn.prepareStatement(
          "SELECT COALESCE(MAX(queue_position), 0), COUNT(*) FROM respawn_claims WHERE respawn_id = ? AND status = 'queued';")
        try {
          statement.setLong(1, respawnId)
          val result = statement.executeQuery()
          result.next()
          (result.getInt(1), result.getInt(2))
        } finally statement.close()
      }
      val (highestPosition, queueSize) = queued

      if (existing > 0 || queueSize >= queueLimit) None
      else {
        val statement = conn.prepareStatement(
          """INSERT INTO respawn_claims
            |(respawn_id, user_id, user_name, character_name, status, queue_position,
            | claimed_at, duration_minutes, warned, kind)
            |VALUES (?, ?, ?, ?, 'queued', ?, ?, ?, FALSE, ?)
            |RETURNING *;""".stripMargin)
        try {
          statement.setLong(1, respawnId)
          statement.setString(2, userId)
          statement.setString(3, userName)
          statement.setString(4, characterName)
          statement.setInt(5, highestPosition + 1)
          statement.setTimestamp(6, Timestamp.from(ZonedDateTime.now().toInstant))
          statement.setInt(7, durationMinutes)
          statement.setString(8, kind)
          val result = statement.executeQuery()
          result.next()
          Some(readClaim(result))
        } finally statement.close()
      }
    }

  def promoteClaim(guildId: String, claimId: Long, startsAt: ZonedDateTime): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      // `status = 'queued'` in the WHERE clause is the concurrency guard: if the
      // claimant left the queue between the caller reserving their stamina and
      // this update, nothing is written and the caller refunds instead of
      // activating a claim that no longer exists.
      //
      // ends_at is derived in SQL from the row's own duration so the deadline
      // can't be computed from a stale in-memory copy of it.
      //
      // The CAST is required, not decoration: in `? + make_interval(...)`
      // Postgres has nothing to infer the placeholder's type from except the
      // interval on the right, so it types the parameter as an interval and
      // rejects the assignment to a timestamptz column.
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims
          |SET status = 'active', queue_position = 0, starts_at = ?, warned = FALSE,
          |    ends_at = CAST(? AS TIMESTAMPTZ) + make_interval(mins => duration_minutes)
          |WHERE id = ? AND status = 'queued'
          |RETURNING *;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(startsAt.toInstant))
        statement.setTimestamp(2, Timestamp.from(startsAt.toInstant))
        statement.setLong(3, claimId)
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def cancelQueued(guildId: String, respawnId: Long, userIds: Set[String]): Unit =
    if (userIds.nonEmpty) withGuildTransaction(guildId) { conn =>
      val statement = conn.prepareStatement(
        "UPDATE respawn_claims SET status = 'cancelled' WHERE respawn_id = ? AND status = 'queued' AND user_id = ?;")
      try {
        userIds.foreach { userId =>
          statement.setLong(1, respawnId)
          statement.setString(2, userId)
          statement.addBatch()
        }
        statement.executeBatch()
      } finally statement.close()
    }

  /** Take a row lock on the respawn so concurrent claim/queue mutations on the
   *  same spawn serialise. Only meaningful inside a transaction. */
  private def lockRespawn(conn: Connection, respawnId: Long): Unit = {
    val statement = conn.prepareStatement("SELECT id FROM respawns WHERE id = ? FOR UPDATE;")
    try {
      statement.setLong(1, respawnId)
      statement.executeQuery()
    } finally statement.close()
  }

  private def setStatus(guildId: String, claimId: Long, status: String): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("UPDATE respawn_claims SET status = ? WHERE id = ?;")
    try {
      statement.setString(1, status)
      statement.setLong(2, claimId)
      statement.executeUpdate()
    } finally statement.close()
  }

  def finishClaim(guildId: String, claimId: Long): Unit =
    setStatus(guildId, claimId, RespawnClaim.StatusFinished)

  def cancelClaim(guildId: String, claimId: Long): Unit =
    setStatus(guildId, claimId, RespawnClaim.StatusCancelled)

  def markWarned(guildId: String, claimId: Long): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("UPDATE respawn_claims SET warned = TRUE WHERE id = ?;")
    try {
      statement.setLong(1, claimId)
      statement.executeUpdate()
    } finally statement.close()
  }

  def extendClaim(guildId: String, claimId: Long, newEndsAt: ZonedDateTime, newDurationMinutes: Int): Unit =
    withGuild(guildId) { conn =>
      // warned resets so a claim extended past its warning gets a fresh one
      // near the new deadline instead of ending unannounced.
      val statement = conn.prepareStatement(
        "UPDATE respawn_claims SET ends_at = ?, duration_minutes = ?, warned = FALSE WHERE id = ?;")
      try {
        statement.setTimestamp(1, Timestamp.from(newEndsAt.toInstant))
        statement.setInt(2, newDurationMinutes)
        statement.setLong(3, claimId)
        statement.executeUpdate()
      } finally statement.close()
    }

  // --- stamina ------------------------------------------------------------

  def stamina(guildId: String, userId: String, budgetMinutes: Int, resetAt: ZonedDateTime): Stamina =
    withGuild(guildId) { conn => readStamina(conn, userId, budgetMinutes, resetAt) }

  /** Read a user's tank, treating a row stamped with an older server-save
   *  boundary as empty. Resetting lazily on read (rather than sweeping every
   *  user at 10:00) means there's no daily job to miss, and a bot that was down
   *  over the boundary still comes back with correct tanks. */
  private def readStamina(conn: Connection, userId: String, budgetMinutes: Int,
                          resetAt: ZonedDateTime): Stamina = {
    val statement = conn.prepareStatement("SELECT used_minutes, reset_at FROM respawn_stamina WHERE user_id = ?;")
    try {
      statement.setString(1, userId)
      val result = statement.executeQuery()
      val used =
        if (!result.next()) 0
        else {
          val storedReset = toZoned(result.getTimestamp("reset_at"))
          if (storedReset.isBefore(resetAt)) 0 else result.getInt("used_minutes")
        }
      Stamina(userId, used, budgetMinutes, resetAt)
    } finally statement.close()
  }

  def reserveStamina(guildId: String, userId: String, minutes: Int, budgetMinutes: Int,
                     resetAt: ZonedDateTime): Boolean =
    withGuildTransaction(guildId) { conn =>
      val current = readStamina(conn, userId, budgetMinutes, resetAt)
      if (!current.canAfford(minutes)) false
      else {
        writeStamina(conn, userId, current.usedMinutes + minutes, resetAt)
        true
      }
    }

  def refundStamina(guildId: String, userId: String, minutes: Int, resetAt: ZonedDateTime): Unit =
    withGuildTransaction(guildId) { conn =>
      // budget is irrelevant to a refund — only the stored used_minutes moves,
      // and a stale row reads as 0 so a refund across the boundary can't push
      // the new day's tank negative.
      val current = readStamina(conn, userId, 0, resetAt)
      writeStamina(conn, userId, math.max(0, current.usedMinutes - minutes), resetAt)
    }

  def setStaminaUsed(guildId: String, userId: String, usedMinutes: Int, resetAt: ZonedDateTime): Unit =
    withGuild(guildId) { conn => writeStamina(conn, userId, math.max(0, usedMinutes), resetAt) }

  // --- teardown -----------------------------------------------------------

  def dropGuildData(guildId: String): Unit = withGuildTransaction(guildId) { conn =>
    // One transaction so a failure part-way can't leave a catalogue whose
    // claims are gone, or settings pointing at a retired channel.
    // The tables themselves stay — ensureTables recreates them on demand
    // anyway, and DELETE keeps this cheap and reversible-looking in the logs.
    val statement = conn.createStatement()
    try {
      statement.executeUpdate("DELETE FROM respawn_claims;")
      statement.executeUpdate("DELETE FROM respawns;")
      statement.executeUpdate("DELETE FROM respawn_settings;")
      // Stamina is left alone: it is per user and per server-save day, resets
      // itself on the next read, and is the one thing that would be wrong to
      // hand back if the same people set the bot up again the same day.
    } finally statement.close()
  }

  private def writeStamina(conn: Connection, userId: String, usedMinutes: Int, resetAt: ZonedDateTime): Unit = {
    val statement = conn.prepareStatement(
      """INSERT INTO respawn_stamina (user_id, used_minutes, reset_at)
        |VALUES (?, ?, ?)
        |ON CONFLICT (user_id) DO UPDATE SET
        |used_minutes = EXCLUDED.used_minutes,
        |reset_at = EXCLUDED.reset_at;""".stripMargin)
    try {
      statement.setString(1, userId)
      statement.setInt(2, usedMinutes)
      statement.setTimestamp(3, Timestamp.from(resetAt.toInstant))
      statement.executeUpdate()
    } finally statement.close()
  }
}
