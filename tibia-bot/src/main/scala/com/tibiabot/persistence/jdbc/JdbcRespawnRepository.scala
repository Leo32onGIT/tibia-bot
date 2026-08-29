package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSchedule, RespawnSettings, RespawnUserPrefs, Stamina}
import com.tibiabot.persistence.{ConnectionProvider, KnownMember, RespawnRepository, ScheduleOccurrence, SeedSync}

import java.sql.{Connection, ResultSet, Timestamp}
import java.time.{ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of RespawnRepository against a guild's own database.
 *
 *  The schema is checked on a guild's first use in this process, and not again
 *  — see `ensureSchema`. `SchemaInitializer.initGuild` only creates tables
 *  when it creates the database, so guilds that existed before this feature
 *  would otherwise never get them; this is the same create-on-read approach
 *  `JdbcGalthenRepository` uses for `satchel`, just not repeated per query.
 *
 *  Timestamps are `TIMESTAMPTZ`, not the plain `TIMESTAMP` the older tables
 *  use. Everything here is a deadline that has to survive a container timezone
 *  change or a daylight-saving shift without silently moving a claim's end
 *  time, and these are new tables so there's no migration cost to getting it
 *  right.
 */
final class JdbcRespawnRepository(connectionProvider: ConnectionProvider) extends RespawnRepository {

  private def connect(guildId: String): () => Connection = () => connectionProvider.guild(guildId)

  /** Guilds whose schema this process has already brought up to date.
   *
   *  [[ensureTables]] is thirty-two statements — six tables, six indexes and
   *  twenty columns added if missing — and it ran before *every* read and write.
   *  Measured against a live database that is around 13ms a call, against 1.6ms
   *  for all seven queries a dashboard board actually needs: better than nine
   *  tenths of the time went on establishing that tables which already exist
   *  still exist. It is paid once per guild now.
   *
   *  Once per *process*, not once ever, which is what makes this safe to do at
   *  all: a build that adds a column is a new process, so it re-runs on first
   *  touch and the column appears exactly as before.
   */
  private val schemaReady = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

  /** Runs the schema check for a guild the first time it is touched.
   *
   *  `computeIfAbsent` rather than a check-then-set: it holds the key while the
   *  work runs, so a second caller arriving on a guild whose tables are still
   *  being created waits for them rather than querying a table that does not
   *  exist yet. A throw records nothing, so the next caller tries again instead
   *  of inheriting a database that was never set up.
   *
   *  On its own connection, deliberately. Run inside a caller's transaction it
   *  would be rolled back with it — Postgres DDL being transactional — and this
   *  would have remembered doing work that had been undone, leaving every later
   *  query on that guild to fail against tables that are no longer there.
   */
  private def ensureSchema(guildId: String): Unit = {
    schemaReady.computeIfAbsent(guildId, _ => {
      JdbcSupport.withConnection(connect(guildId))(ensureTables)
      java.lang.Boolean.TRUE
    })
    ()
  }

  private def withGuild[A](guildId: String)(use: Connection => A): A = {
    ensureSchema(guildId)
    JdbcSupport.withConnection(connect(guildId))(use)
  }

  private def withGuildTransaction[A](guildId: String)(use: Connection => A): A = {
    ensureSchema(guildId)
    JdbcSupport.withTransaction(connect(guildId))(use)
  }

  /** As [[withGuildTransaction]], on a connection that is nobody else's — see
   *  [[withRespawnLock]], its only caller. */
  private def withExclusiveTransaction[A](guildId: String)(use: Connection => A): A = {
    ensureSchema(guildId)
    JdbcSupport.withTransaction(() => connectionProvider.guildUnpooled(guildId))(use)
  }

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
          |warn_minutes INT NOT NULL DEFAULT 10,
          |handover_minutes INT NOT NULL DEFAULT 10,
          |auto_claim BOOLEAN NOT NULL DEFAULT TRUE
          |);""".stripMargin)

      // What the pinned board post was last drawn from. Not a setting a guild
      // tunes, which is why it is not in `respawn_settings` alongside things
      // like the claim length: it is this process's note of what a message in
      // Discord currently shows, and the only thing that reads it is the check
      // that decides whether to redraw.
      statement.executeUpdate(
        """CREATE TABLE IF NOT EXISTS respawn_board_state (
          |id INT PRIMARY KEY,
          |digest VARCHAR(64) NOT NULL DEFAULT ''
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
          |added_by VARCHAR(255) NOT NULL DEFAULT '',
          |creature_pinned BOOLEAN NOT NULL DEFAULT FALSE
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
          |kind VARCHAR(16) NOT NULL DEFAULT 'adhoc',
          |limbo_until TIMESTAMPTZ,
          |offer_expires_at TIMESTAMPTZ,
          |outcome VARCHAR(24),
          |ended_at TIMESTAMPTZ
          |);""".stripMargin)

      // Columns added after the tables first shipped. `IF NOT EXISTS` makes each
      // a no-op once applied, so this doubles as the migration for guilds that
      // already have the tables — `CREATE TABLE IF NOT EXISTS` above would
      // silently skip them and leave the new columns missing.
      statement.executeUpdate(
        "ALTER TABLE respawn_settings ADD COLUMN IF NOT EXISTS handover_minutes INT NOT NULL DEFAULT 10;")
      // DEFAULT TRUE rather than FALSE, so a guild that has been running since
      // before autoclaim existed gets it switched on by the migration. The
      // confirm-or-lose rule it replaces was being answered by everybody every
      // time, so arriving already on is the behaviour people were asking for —
      // and Config -> Autoclaim turns it back off for a guild that wants the old
      // rule.
      statement.executeUpdate(
        "ALTER TABLE respawn_settings ADD COLUMN IF NOT EXISTS auto_claim BOOLEAN NOT NULL DEFAULT TRUE;")
      // Added and removed inside one deploy: the bot always uses server time, so
      // a per-guild zone was surface with nothing behind it. Dropped rather than
      // left as a column nothing reads.
      statement.executeUpdate("ALTER TABLE respawn_settings DROP COLUMN IF EXISTS timezone;")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS limbo_until TIMESTAMPTZ;")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS offer_expires_at TIMESTAMPTZ;")
      statement.executeUpdate(
        "ALTER TABLE respawns ADD COLUMN IF NOT EXISTS creature_pinned BOOLEAN NOT NULL DEFAULT FALSE;")
      // Nullable with no default on purpose: NULL is "follow the server", which
      // is what almost every row is and what lets a guild retune its own ceiling
      // and have every un-singled-out spawn move with it. A DEFAULT here would
      // freeze today's server value onto every existing row.
      statement.executeUpdate(
        "ALTER TABLE respawns ADD COLUMN IF NOT EXISTS max_duration INT;")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS outcome VARCHAR(24);")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS ended_at TIMESTAMPTZ;")
      // The Log panel reads one spawn's finished claims newest-first.
      statement.executeUpdate(
        "CREATE INDEX IF NOT EXISTS respawn_claims_history ON respawn_claims (respawn_id, ended_at DESC);")
      // The board's Log reads the same trail across every spawn, so it has no
      // respawn_id to narrow on and the index above cannot serve it. This one
      // also covers the summary line's count over a recent window.
      statement.executeUpdate(
        "CREATE INDEX IF NOT EXISTS respawn_claims_guild_history ON respawn_claims (status, ended_at DESC);")

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
        """CREATE TABLE IF NOT EXISTS respawn_schedules (
          |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          |respawn_id BIGINT NOT NULL,
          |user_id VARCHAR(255) NOT NULL,
          |user_name VARCHAR(255) NOT NULL DEFAULT '',
          |character_name VARCHAR(255) NOT NULL DEFAULT '',
          |anchor_at TIMESTAMPTZ NOT NULL,
          |period_minutes INT NOT NULL DEFAULT 1440,
          |duration_minutes INT NOT NULL,
          |active BOOLEAN NOT NULL DEFAULT TRUE,
          |created_at TIMESTAMPTZ NOT NULL
          |);""".stripMargin)
      statement.executeUpdate(
        "CREATE INDEX IF NOT EXISTS respawn_schedules_by_respawn ON respawn_schedules (respawn_id, active);")
      // Weekday mask, Monday the low bit. Defaulting to all seven is what makes
      // every booking that predates weekdays keep behaving as the daily slot it
      // was, rather than quietly becoming a one-off.
      statement.executeUpdate(
        "ALTER TABLE respawn_schedules ADD COLUMN IF NOT EXISTS days_of_week SMALLINT NOT NULL DEFAULT 127;")
      // What the owner is called in the guild, kept beside the account name so
      // a row can name somebody the way their server does without a lookup —
      // and can still name somebody who has since left.
      statement.executeUpdate(
        "ALTER TABLE respawn_schedules ADD COLUMN IF NOT EXISTS nickname VARCHAR(255) NOT NULL DEFAULT '';")

      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS schedule_id BIGINT;")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS asked_at TIMESTAMPTZ;")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS request_deadline TIMESTAMPTZ;")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS requester_user_id VARCHAR(255);")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS requester_user_name VARCHAR(255);")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS requester_nickname VARCHAR(255);")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS nickname VARCHAR(255) NOT NULL DEFAULT '';")

      // Give a name back to every row that has none, from the newest one the
      // same account has left anywhere else in this guild.
      //
      // Two kinds of row need it. Everything booked before the column existed
      // defaulted to blank, and a *schedule* that predates it keeps minting
      // fresh blank occurrences for as long as it runs — so healing the rules
      // matters more than healing the claims. And a handful of paths still
      // write a claim with no second name because the one that made it never
      // had one to hand.
      //
      // Deliberately not run once and marked done: it is re-run per guild on
      // each start precisely so it keeps mopping up the second kind. It is
      // cheap and it shrinks — every pass leaves fewer rows for the next one to
      // match — and a row whose owner has never been seen under a name simply
      // stays as it is, which is no worse than before.
      val knownNicknames =
        """WITH known AS (
          |  SELECT DISTINCT ON (user_id) user_id, nickname FROM (
          |    SELECT user_id, nickname, claimed_at AS seen_at FROM respawn_claims WHERE nickname <> ''
          |    UNION ALL
          |    SELECT user_id, nickname, created_at AS seen_at FROM respawn_schedules WHERE nickname <> ''
          |  ) seen ORDER BY user_id, seen_at DESC
          |)""".stripMargin
      statement.executeUpdate(
        knownNicknames +
          """
            |UPDATE respawn_schedules s SET nickname = known.nickname
            |FROM known WHERE s.user_id = known.user_id AND s.nickname = '';""".stripMargin)
      statement.executeUpdate(
        knownNicknames +
          """
            |UPDATE respawn_claims c SET nickname = known.nickname
            |FROM known WHERE c.user_id = known.user_id AND c.nickname = '';""".stripMargin)
      // The window the asker wants, when they asked by trying to book over this
      // slot. Null for a Request-button ask, where the slot itself is what they
      // are asking for.
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS requested_starts_at TIMESTAMPTZ;")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS requested_duration_minutes INT;")
      // Confirmation. `confirmed_at` is the owner saying they are hunting this
      // slot; `confirm_by` is the deadline a booking that started on its own has
      // to say it by, and doubles as the marker for "this began as a booking".
      //
      // Both null on every existing row, deliberately and with no backfill: a
      // hunt already running when this ships has no deadline, so the sweep that
      // gives up on unconfirmed bookings cannot touch it.
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ;")
      statement.executeUpdate(
        "ALTER TABLE respawn_claims ADD COLUMN IF NOT EXISTS confirm_by TIMESTAMPTZ;")
      // One holder per spawn, enforced by the database.
      //
      // The service checks "is anyone on it" and then inserts, and those are two
      // statements: two people pressing Claim on a free spawn both pass the check
      // and both insert. The row lock below serialises the writes but not the
      // decision, so it cannot help. Every path that makes a claim active goes
      // through this index instead — the insert, a handover promotion, and a
      // booked slot starting.
      //
      // Any duplicates a live database already collected are closed first, oldest
      // kept, or creating the index would fail and take the guild's whole
      // migration with it.
      statement.executeUpdate(
        """UPDATE respawn_claims SET status = 'finished', outcome = 'taken-over', ended_at = NOW()
          |WHERE status = 'active' AND id NOT IN (
          |  SELECT MIN(id) FROM respawn_claims WHERE status = 'active' GROUP BY respawn_id
          |);""".stripMargin)
      statement.executeUpdate(
        """CREATE UNIQUE INDEX IF NOT EXISTS respawn_claims_one_holder
          |ON respawn_claims (respawn_id)
          |WHERE status = 'active';""".stripMargin)

      // The materialiser's uniqueness rule, enforced by the database rather than
      // by a read-then-write: two sweeps racing would otherwise both find a slot
      // unbooked and both book it.
      statement.executeUpdate(
        """CREATE UNIQUE INDEX IF NOT EXISTS respawn_claims_occurrence
          |ON respawn_claims (schedule_id, starts_at)
          |WHERE schedule_id IS NOT NULL;""".stripMargin)

      // NULL means "follow the guild default", which is why these are nullable
      // rather than zero-defaulted: 0 is a meaningful value for warn_minutes
      // (reminders off) and would be indistinguishable from unset.
      statement.executeUpdate(
        """CREATE TABLE IF NOT EXISTS respawn_user_prefs (
          |user_id VARCHAR(255) PRIMARY KEY,
          |default_duration INT,
          |warn_minutes INT
          |);""".stripMargin)

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
      addedBy = Option(result.getString("added_by")).getOrElse(""),
      // getInt yields 0 for SQL NULL, which would read as a ceiling of zero
      // minutes rather than as "no override" — so the null is asked about
      // explicitly rather than inferred from the value.
      maxDurationMinutes = {
        val value = result.getInt("max_duration")
        if (result.wasNull()) None else Some(value)
      }
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
      kind = Option(result.getString("kind")).getOrElse(RespawnClaim.KindAdHoc),
      limboUntil = optionalZoned(result, "limbo_until"),
      offerExpiresAt = optionalZoned(result, "offer_expires_at"),
      outcome = Option(result.getString("outcome")).filter(_.nonEmpty),
      endedAt = optionalZoned(result, "ended_at"),
      scheduleId = { val id = result.getLong("schedule_id"); if (result.wasNull()) None else Some(id) },
      askedAt = optionalZoned(result, "asked_at"),
      requestDeadline = optionalZoned(result, "request_deadline"),
      requesterUserId = Option(result.getString("requester_user_id")).filter(_.nonEmpty),
      requesterUserName = Option(result.getString("requester_user_name")).filter(_.nonEmpty),
      requesterNickname = Option(result.getString("requester_nickname")).filter(_.nonEmpty),
      nickname = Option(result.getString("nickname")).getOrElse(""),
      requestedStartsAt = optionalZoned(result, "requested_starts_at"),
      requestedDurationMinutes = {
        val minutes = result.getInt("requested_duration_minutes")
        if (result.wasNull()) None else Some(minutes)
      },
      confirmedAt = optionalZoned(result, "confirmed_at"),
      confirmBy = optionalZoned(result, "confirm_by")
    )

  private def collectClaims(result: ResultSet): List[RespawnClaim] = {
    val claims = ListBuffer[RespawnClaim]()
    while (result.next()) claims += readClaim(result)
    claims.toList
  }

  // --- settings -----------------------------------------------------------

  /** A guild with no database at all has certainly not configured respawns, so
   *  that reads as None rather than propagating.
   *
   *  This is the one entry point reached for guilds the bot has never been set
   *  up in — the periodic sweep asks every guild for its settings — and those
   *  guilds have no `_<guildId>` database, so connecting throws. Without this
   *  the sweep would log a stack trace for each of them on every cycle.
   *  Postgres reports a missing database as SQLState 3D000
   *  (`invalid_catalog_name`); any other failure is still a real error and is
   *  left to propagate. */
  def settings(guildId: String): Option[RespawnSettings] =
    try settingsQuery(guildId)
    catch {
      case error: java.sql.SQLException if error.getSQLState == "3D000" => None
    }

  private def settingsQuery(guildId: String): Option[RespawnSettings] = withGuild(guildId) { conn =>
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
        warnMinutes = result.getInt("warn_minutes"),
        handoverMinutes = result.getInt("handover_minutes"),
        autoClaim = result.getBoolean("auto_claim")
      )) else None
    } finally statement.close()
  }

  def saveSettings(guildId: String, settings: RespawnSettings): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """INSERT INTO respawn_settings
        |(id, forum_channel, board_thread, default_duration, max_duration, queue_limit, stamina_minutes,
        | warn_minutes, handover_minutes, auto_claim)
        |VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |forum_channel = EXCLUDED.forum_channel,
        |board_thread = EXCLUDED.board_thread,
        |default_duration = EXCLUDED.default_duration,
        |max_duration = EXCLUDED.max_duration,
        |queue_limit = EXCLUDED.queue_limit,
        |stamina_minutes = EXCLUDED.stamina_minutes,
        |warn_minutes = EXCLUDED.warn_minutes,
        |handover_minutes = EXCLUDED.handover_minutes,
        |auto_claim = EXCLUDED.auto_claim;""".stripMargin)
    try {
      statement.setString(1, settings.forumChannel)
      statement.setString(2, settings.boardThread)
      statement.setInt(3, settings.defaultDurationMinutes)
      statement.setInt(4, settings.maxDurationMinutes)
      statement.setInt(5, settings.queueLimit)
      statement.setInt(6, settings.staminaMinutes)
      statement.setInt(7, settings.warnMinutes)
      statement.setInt(8, settings.handoverMinutes)
      statement.setBoolean(9, settings.autoClaim)
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

  def knownMembers(guildId: String, limit: Int): List[KnownMember] = withGuild(guildId) { conn =>
    // Claims and schedules together: somebody whose only involvement is a
    // standing weekly booking has no claim row at all until it materialises, and
    // leaving them out would make them unpickable.
    //
    // DISTINCT ON keeps one row per account — the newest, which is where the
    // current spelling of both names is. Ordered by when the row was written and
    // deliberately not by its id: the two tables have independent identity
    // sequences, so a schedule's id and a claim's id are not comparable and the
    // greater of the two says nothing about which came last.
    val statement = conn.prepareStatement(
      """SELECT DISTINCT ON (user_id) user_id, user_name, nickname FROM (
        |  SELECT user_id, user_name, nickname, claimed_at AS seen_at FROM respawn_claims
        |  UNION ALL
        |  SELECT user_id, user_name, nickname, created_at AS seen_at FROM respawn_schedules
        |) AS everyone
        |WHERE user_id <> ''
        |ORDER BY user_id, seen_at DESC
        |LIMIT ?;""".stripMargin)
    try {
      statement.setInt(1, limit)
      val result = statement.executeQuery()
      val people = ListBuffer[KnownMember]()
      while (result.next()) {
        people += KnownMember(
          userId = result.getString("user_id"),
          userName = Option(result.getString("user_name")).getOrElse(""),
          nickname = Option(result.getString("nickname")).getOrElse(""))
      }
      // Sorted for a person rather than for the database: the picker shows them
      // as a list to read, and `DISTINCT ON` forces an ordering by id that means
      // nothing to anybody.
      people.toList.sortBy(person =>
        (if (person.nickname.nonEmpty) person.nickname else person.userName).toLowerCase)
    } finally statement.close()
  }

  def boardDigest(guildId: String): Option[String] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("SELECT digest FROM respawn_board_state WHERE id = 1;")
    try {
      val result = statement.executeQuery()
      // An empty digest is treated as none rather than as a fingerprint nothing
      // will ever match — the column's default, meaning the row exists but has
      // never been written.
      if (result.next()) Option(result.getString("digest")).filter(_.nonEmpty) else None
    } finally statement.close()
  }

  def setBoardDigest(guildId: String, digest: String): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """INSERT INTO respawn_board_state (id, digest) VALUES (1, ?)
        |ON CONFLICT (id) DO UPDATE SET digest = EXCLUDED.digest;""".stripMargin)
    try {
      statement.setString(1, digest)
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
    // a caller can set one attribute without restating the rest.
    val statement = conn.prepareStatement(
      """UPDATE respawns SET
        |name = COALESCE(?, name),
        |creature = COALESCE(?, creature),
        |world = COALESCE(?, world),
        |mapper_link = COALESCE(?, mapper_link),
        |creature_pinned = creature_pinned OR ? IS NOT NULL
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
      // Pins the row when this call set a creature, so the boot-time seed sync
      // leaves a hand-picked monster alone from then on.
      setOptional(5, creature)
      statement.setLong(6, respawnId)
      statement.executeUpdate()
    } finally statement.close()
  }

  /** Set or clear one spawn's own claim ceiling.
   *
   *  Its own statement rather than another COALESCE argument on `updateRespawn`,
   *  because clearing is a real operation here: COALESCE cannot tell "leave this
   *  alone" from "set it back to NULL", and both are things a moderator asks for.
   */
  def setRespawnMaxDuration(guildId: String, respawnId: Long, minutes: Option[Int]): Unit =
    withGuild(guildId) { conn =>
      val statement = conn.prepareStatement("UPDATE respawns SET max_duration = ? WHERE id = ?;")
      try {
        minutes match {
          case Some(value) => statement.setInt(1, value)
          case None        => statement.setNull(1, java.sql.Types.INTEGER)
        }
        statement.setLong(2, respawnId)
        statement.executeUpdate()
      } finally statement.close()
    }

  def removeRespawn(guildId: String, respawnId: Long): Unit = withGuildTransaction(guildId) { conn =>
    val claims = conn.prepareStatement("DELETE FROM respawn_claims WHERE respawn_id = ?;")
    try { claims.setLong(1, respawnId); claims.executeUpdate() } finally claims.close()
    // Schedules go too, or they would keep booking slots on a spawn that no
    // longer exists — the materialiser reads them, not the catalogue.
    val schedules = conn.prepareStatement("DELETE FROM respawn_schedules WHERE respawn_id = ?;")
    try { schedules.setLong(1, respawnId); schedules.executeUpdate() } finally schedules.close()
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

  def syncSeed(guildId: String, spawns: List[(String, String, String, String)]): SeedSync = {
    val added = importSeed(guildId, spawns)
    val updated = updateSeedRows(guildId, spawns)

    // Diffed in Scala rather than with a 280-placeholder NOT IN: the catalogue
    // is small, and the comparison is easier to read here than in SQL.
    val wanted = spawns.map(_._1.trim.toLowerCase).toSet
    val orphans = listRespawns(guildId)
      .filter(_.source == Respawn.SourceSeed)
      .filterNot(respawn => wanted.contains(respawn.code.trim.toLowerCase))
    // Unconditionally, claims and bookings included — see the port's note on
    // why holding a dropped code open until it happens to be idle is the worse
    // of the two trades. The rows are returned so the caller can take their
    // forum posts down; this layer has no Discord to do it with.
    orphans.foreach(respawn => removeRespawn(guildId, respawn.id))

    SeedSync(added, updated, orphans)
  }

  /** Correct the name and city of seed rows the bundled file has changed.
   *
   *  `creature` is deliberately not touched here — syncSeedCreatures owns it,
   *  runs on boot, and honours the `creature_pinned` flag a guild sets to keep
   *  its own choice. Two writers of one column would fight. */
  private def updateSeedRows(guildId: String, spawns: List[(String, String, String, String)]): Int =
    withGuildTransaction(guildId) { conn =>
      val statement = conn.prepareStatement(
        """UPDATE respawns SET name = ?, region = ?
          |WHERE LOWER(code) = LOWER(?)
          |  AND source = 'seed'
          |  AND (name <> ? OR region <> ?);""".stripMargin)
      try {
        spawns.foreach { case (code, region, name, _) =>
          statement.setString(1, name)
          statement.setString(2, region)
          statement.setString(3, code)
          // Same trick as syncSeedCreatures: comparing makes this a no-op once a
          // guild is in step, so the count is what actually changed.
          statement.setString(4, name)
          statement.setString(5, region)
          statement.addBatch()
        }
        statement.executeBatch().sum
      } finally statement.close()
    }

  def syncSeedCreatures(guildId: String, creaturesByCode: List[(String, String)]): Int =
    withGuildTransaction(guildId) { conn =>
      val statement = conn.prepareStatement(
        """UPDATE respawns SET creature = ?
          |WHERE LOWER(code) = LOWER(?)
          |  AND source = 'seed'
          |  AND creature_pinned = FALSE
          |  AND creature <> ?;""".stripMargin)
      try {
        creaturesByCode.foreach { case (code, creature) =>
          statement.setString(1, creature)
          statement.setString(2, code)
          // `creature <> ?` keeps this a no-op once the guild is in step, so the
          // returned count is "what actually changed" rather than "rows I looked
          // at" — which is what makes it safe to log on every boot.
          statement.setString(3, creature)
          statement.addBatch()
        }
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

  def allQueuedClaims(guildId: String): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.createStatement()
    try collectClaims(statement.executeQuery(
      "SELECT * FROM respawn_claims WHERE status = 'queued' ORDER BY respawn_id, queue_position;"))
    finally statement.close()
  }

  def allReservations(guildId: String, now: ZonedDateTime): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """SELECT * FROM respawn_claims
        |WHERE status = 'reserved' AND starts_at > ?
        |ORDER BY respawn_id, starts_at;""".stripMargin)
    try {
      statement.setTimestamp(1, Timestamp.from(now.toInstant))
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def lastActivityByRespawn(guildId: String): List[(Long, ZonedDateTime)] = withGuild(guildId) { conn =>
    val statement = conn.createStatement()
    try {
      // GREATEST over the three, because a spawn's last activity is whichever
      // happened most recently: a claim that ended, one still running, or a
      // booked window that has since come round. COALESCE inside so a NULL
      // column — a claim that never ended, a row from before ended_at existed —
      // doesn't swallow the whole result, which is what GREATEST does with a
      // NULL argument.
      //
      // A slot that has *not* come round yet is deliberately not counted. Its
      // starts_at is in the future, and a future timestamp read as "when this
      // last happened" sorts a spawn above everything that really did just
      // happen — so booking a spawn for tomorrow pinned it to the top of the
      // dashboard until tomorrow, over spawns being hunted right now. The
      // comparison also covers starts_at being NULL, which is not > now and so
      // falls to claimed_at exactly as it did before.
      val result = statement.executeQuery(
        """SELECT respawn_id,
          |       MAX(GREATEST(COALESCE(ended_at, claimed_at),
          |                    CASE WHEN starts_at <= now() THEN starts_at ELSE claimed_at END,
          |                    claimed_at)) AS last_seen
          |FROM respawn_claims
          |GROUP BY respawn_id;""".stripMargin)
      val rows = scala.collection.mutable.ListBuffer.empty[(Long, ZonedDateTime)]
      while (result.next()) {
        val stamp = result.getTimestamp("last_seen")
        if (stamp != null)
          rows += result.getLong("respawn_id") -> stamp.toInstant.atZone(java.time.ZoneOffset.UTC)
      }
      rows.toList
    } finally statement.close()
  }

  def openClaimsForUser(guildId: String, userId: String): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """SELECT * FROM respawn_claims
        |WHERE user_id = ? AND status IN ('active', 'queued', 'offered')
        |ORDER BY status, ends_at NULLS LAST, queue_position;""".stripMargin)
    try {
      statement.setString(1, userId)
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def expiredClaims(guildId: String, now: ZonedDateTime): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """SELECT * FROM respawn_claims
        |WHERE status = 'active'
        |  AND (limbo_until <= ? OR (limbo_until IS NULL AND ends_at <= ?))
        |ORDER BY ends_at;""".stripMargin)
    try {
      statement.setTimestamp(1, Timestamp.from(now.toInstant))
      statement.setTimestamp(2, Timestamp.from(now.toInstant))
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def unwarnedActiveClaims(guildId: String, now: ZonedDateTime): List[RespawnClaim] =
    withGuild(guildId) { conn =>
      // No lead-time window here — it varies per member, so the caller decides
      // which of these are actually due. Claims already handing over are
      // excluded: their time is up, so a reminder would be pointless.
      val statement = conn.prepareStatement(
        """SELECT * FROM respawn_claims
          |WHERE status = 'active' AND warned = FALSE AND ends_at > ? AND limbo_until IS NULL
          |ORDER BY ends_at;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(now.toInstant))
        collectClaims(statement.executeQuery())
      } finally statement.close()
    }

  def insertActiveClaim(guildId: String, respawnId: Long, userId: String, userName: String, nickname: String,
                        characterName: String, startsAt: ZonedDateTime, endsAt: ZonedDateTime,
                        durationMinutes: Int, kind: String): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      lockRespawn(conn, respawnId)
      // Re-checked here rather than trusted from the caller's earlier read, under
      // the lock taken above: whoever loses the race gets no row back and is told
      // the spawn was taken, instead of a second claim on somebody else's hunt.
      val statement = conn.prepareStatement(
        """INSERT INTO respawn_claims
          |(respawn_id, user_id, user_name, character_name, status, queue_position,
          | claimed_at, starts_at, ends_at, duration_minutes, warned, kind, nickname)
          |SELECT ?, ?, ?, ?, 'active', 0, ?, ?, ?, ?, FALSE, ?, ?
          |WHERE NOT EXISTS (
          |  SELECT 1 FROM respawn_claims WHERE respawn_id = ? AND status = 'active'
          |)
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
        statement.setString(10, nickname)
        statement.setLong(11, respawnId)
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def enqueueClaim(guildId: String, respawnId: Long, userId: String, userName: String, nickname: String,
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
            | claimed_at, duration_minutes, warned, kind, nickname)
            |VALUES (?, ?, ?, ?, 'queued', ?, ?, ?, FALSE, ?, ?)
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
          statement.setString(9, nickname)
          val result = statement.executeQuery()
          result.next()
          Some(readClaim(result))
        } finally statement.close()
      }
    }

  def promoteClaim(guildId: String, claimId: Long, startsAt: ZonedDateTime): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      // `status = 'offered'` in the WHERE clause is the concurrency guard: if the
      // offer lapsed or was declined between the caller reserving their stamina
      // and this update, nothing is written and the caller refunds instead of
      // activating a claim nobody accepted.
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
          |    ends_at = CAST(? AS TIMESTAMPTZ) + make_interval(mins => duration_minutes),
          |    offer_expires_at = NULL, limbo_until = NULL
          |WHERE id = ? AND status = 'offered'
          |RETURNING *;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(startsAt.toInstant))
        statement.setTimestamp(2, Timestamp.from(startsAt.toInstant))
        statement.setLong(3, claimId)
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def findClaimById(guildId: String, claimId: Long): Option[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("SELECT * FROM respawn_claims WHERE id = ?;")
    try {
      statement.setLong(1, claimId)
      val result = statement.executeQuery()
      if (result.next()) Some(readClaim(result)) else None
    } finally statement.close()
  }

  def offerClaim(guildId: String, claimId: Long, offerExpiresAt: ZonedDateTime): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims
          |SET status = 'offered', offer_expires_at = ?
          |WHERE id = ? AND status = 'queued'
          |RETURNING *;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(offerExpiresAt.toInstant))
        statement.setLong(2, claimId)
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def offeredClaim(guildId: String, respawnId: Long): Option[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "SELECT * FROM respawn_claims WHERE respawn_id = ? AND status = 'offered' ORDER BY id LIMIT 1;")
    try {
      statement.setLong(1, respawnId)
      val result = statement.executeQuery()
      if (result.next()) Some(readClaim(result)) else None
    } finally statement.close()
  }

  def expiredOffers(guildId: String, now: ZonedDateTime): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "SELECT * FROM respawn_claims WHERE status = 'offered' AND offer_expires_at <= ? ORDER BY offer_expires_at;")
    try {
      statement.setTimestamp(1, Timestamp.from(now.toInstant))
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def setLimbo(guildId: String, claimId: Long, limboUntil: ZonedDateTime): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("UPDATE respawn_claims SET limbo_until = ? WHERE id = ?;")
    try {
      statement.setTimestamp(1, Timestamp.from(limboUntil.toInstant))
      statement.setLong(2, claimId)
      statement.executeUpdate()
    } finally statement.close()
  }

  def cancelQueued(guildId: String, respawnId: Long, userIds: Set[String], outcome: String): Unit =
    if (userIds.nonEmpty) withGuildTransaction(guildId) { conn =>
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims SET status = 'cancelled', outcome = ?, ended_at = NOW()
          |WHERE respawn_id = ? AND status = 'queued' AND user_id = ?;""".stripMargin)
      try {
        userIds.foreach { userId =>
          statement.setString(1, outcome)
          statement.setLong(2, respawnId)
          statement.setString(3, userId)
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

  private def setStatus(guildId: String, claimId: Long, status: String, outcome: String): Unit =
    withGuild(guildId) { conn =>
      // ended_at comes from the database rather than being threaded through every
      // caller: it is an audit timestamp where a second either way is irrelevant,
      // and NOW() keeps it off the signature of the dozen places a claim ends.
      // The status guard makes this idempotent — a claim that already ended keeps
      // its original outcome rather than being relabelled by a late second call.
      // It has to list every *live* state, `reserved` included: a booked slot is
      // cancelled when its owner gives it up, when the bot missed its window, and
      // when it arrives to find the spawn taken. Leaving it out made all three
      // silently do nothing.
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims
          |SET status = ?, outcome = ?, ended_at = NOW()
          |WHERE id = ? AND status IN ('active', 'queued', 'offered', 'reserved');""".stripMargin)
      try {
        statement.setString(1, status)
        statement.setString(2, outcome)
        statement.setLong(3, claimId)
        statement.executeUpdate()
      } finally statement.close()
    }

  def finishClaim(guildId: String, claimId: Long, outcome: String): Unit =
    setStatus(guildId, claimId, RespawnClaim.StatusFinished, outcome)

  def cancelClaim(guildId: String, claimId: Long, outcome: String): Unit =
    setStatus(guildId, claimId, RespawnClaim.StatusCancelled, outcome)

  def claimHistory(guildId: String, respawnId: Option[Long], userId: Option[String],
                   limit: Int, offset: Int): List[RespawnClaim] =
    withGuild(guildId) { conn =>
      // One spawn, one member, or the whole guild — same ordering whichever it
      // is. The predicates are built rather than parameterised because a column
      // cannot be bound; the values below still are. Both filters can be present
      // at once, which no caller does today but which costs nothing to allow.
      val filters =
        respawnId.map(_ => "respawn_id = ?").toList ::: userId.map(_ => "user_id = ?").toList
      val where = (filters :+ "status IN ('finished', 'cancelled')").mkString(" AND ")
      val statement = conn.prepareStatement(
        s"""SELECT * FROM respawn_claims
           |WHERE $where
           |ORDER BY ended_at DESC NULLS LAST, id DESC
           |LIMIT ? OFFSET ?;""".stripMargin)
      try {
        var at = 1
        respawnId.foreach { id => statement.setLong(at, id); at += 1 }
        userId.foreach { id => statement.setString(at, id); at += 1 }
        statement.setInt(at, limit)
        statement.setInt(at + 1, offset)
        collectClaims(statement.executeQuery())
      } finally statement.close()
    }

  def claimsBetween(guildId: String, respawnId: Long,
                    from: ZonedDateTime, to: ZonedDateTime): List[RespawnClaim] =
    withGuild(guildId) { conn =>
      // COALESCE, because a claim that was given up, taken over or force-ended
      // stopped before its deadline and it is the real end that decides whether
      // it falls in the window — and, in the caller, how long the block is.
      val statement = conn.prepareStatement(
        """SELECT * FROM respawn_claims
           |WHERE respawn_id = ?
           |  AND status IN ('finished', 'cancelled')
           |  AND starts_at IS NOT NULL
           |  AND starts_at < ?
           |  AND COALESCE(ended_at, ends_at, starts_at) > ?
           |ORDER BY starts_at;""".stripMargin)
      try {
        statement.setLong(1, respawnId)
        statement.setTimestamp(2, Timestamp.from(to.toInstant))
        statement.setTimestamp(3, Timestamp.from(from.toInstant))
        collectClaims(statement.executeQuery())
      } finally statement.close()
    }

  def reassignClaim(guildId: String, claimId: Long, userId: String, userName: String,
                    nickname: String): Option[RespawnClaim] = withGuildTransaction(guildId) { conn =>
    // The nickname moves with the account name. Leaving it behind — which this
    // did — kept the outgoing holder's, so the row read as the new owner under
    // the old one's guild name.
    val statement = conn.prepareStatement(
      """UPDATE respawn_claims SET user_id = ?, user_name = ?, nickname = ?, character_name = ''
        |WHERE id = ? AND status = 'active'
        |RETURNING *;""".stripMargin)
    try {
      statement.setString(1, userId)
      statement.setString(2, userName)
      statement.setString(3, nickname)
      statement.setLong(4, claimId)
      val result = statement.executeQuery()
      if (result.next()) Some(readClaim(result)) else None
    } finally statement.close()
  }

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

  def setClaimDuration(guildId: String, claimId: Long, durationMinutes: Int,
                       newEndsAt: Option[ZonedDateTime]): Unit = withGuild(guildId) { conn =>
    // warned resets for the same reason extendClaim resets it: a claim whose
    // deadline moved should get a fresh reminder near the new one.
    val statement = conn.prepareStatement(
      """UPDATE respawn_claims
        |SET duration_minutes = ?, ends_at = COALESCE(?, ends_at), warned = FALSE
        |WHERE id = ?;""".stripMargin)
    try {
      statement.setInt(1, durationMinutes)
      newEndsAt match {
        case Some(when) => statement.setTimestamp(2, Timestamp.from(when.toInstant))
        // COALESCE leaves the column alone, which for a queued claim means it
        // stays NULL rather than being handed a deadline it shouldn't have.
        case None       => statement.setNull(2, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
      }
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

  def clearStamina(guildId: String): Int = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("DELETE FROM respawn_stamina;")
    try statement.executeUpdate() finally statement.close()
  }

  def setStaminaUsed(guildId: String, userId: String, usedMinutes: Int, resetAt: ZonedDateTime): Unit =
    withGuild(guildId) { conn => writeStamina(conn, userId, math.max(0, usedMinutes), resetAt) }

  // --- schedules ----------------------------------------------------------

  private def readSchedule(result: ResultSet): RespawnSchedule =
    RespawnSchedule(
      id = result.getLong("id"),
      respawnId = result.getLong("respawn_id"),
      userId = result.getString("user_id"),
      userName = Option(result.getString("user_name")).getOrElse(""),
      characterName = Option(result.getString("character_name")).getOrElse(""),
      anchorAt = toZoned(result.getTimestamp("anchor_at")),
      periodMinutes = result.getInt("period_minutes"),
      durationMinutes = result.getInt("duration_minutes"),
      active = result.getBoolean("active"),
      createdAt = toZoned(result.getTimestamp("created_at")),
      daysOfWeek = result.getInt("days_of_week"),
      nickname = Option(result.getString("nickname")).getOrElse("")
    )

  private def collectSchedules(result: ResultSet): List[RespawnSchedule] = {
    val out = ListBuffer[RespawnSchedule]()
    while (result.next()) out += readSchedule(result)
    out.toList
  }

  def addSchedule(guildId: String, respawnId: Long, userId: String, userName: String, nickname: String,
                  characterName: String, anchorAt: ZonedDateTime, periodMinutes: Int,
                  durationMinutes: Int,
                  daysOfWeek: Int = RespawnSchedule.EveryDay): RespawnSchedule = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """INSERT INTO respawn_schedules
        |(respawn_id, user_id, user_name, character_name, anchor_at, period_minutes,
        | duration_minutes, days_of_week, active, created_at, nickname)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, NOW(), ?)
        |RETURNING *;""".stripMargin)
    try {
      statement.setLong(1, respawnId)
      statement.setString(2, userId)
      statement.setString(3, userName)
      statement.setString(4, characterName)
      statement.setTimestamp(5, Timestamp.from(anchorAt.toInstant))
      statement.setInt(6, periodMinutes)
      statement.setInt(7, durationMinutes)
      statement.setInt(8, daysOfWeek)
      statement.setString(9, nickname)
      val result = statement.executeQuery()
      result.next()
      readSchedule(result)
    } finally statement.close()
  }

  def findSchedule(guildId: String, scheduleId: Long): Option[RespawnSchedule] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("SELECT * FROM respawn_schedules WHERE id = ?;")
    try {
      statement.setLong(1, scheduleId)
      val result = statement.executeQuery()
      if (result.next()) Some(readSchedule(result)) else None
    } finally statement.close()
  }

  def activeSchedules(guildId: String): List[RespawnSchedule] = withGuild(guildId) { conn =>
    val statement = conn.createStatement()
    try collectSchedules(statement.executeQuery("SELECT * FROM respawn_schedules WHERE active ORDER BY id;"))
    finally statement.close()
  }

  def schedulesForRespawn(guildId: String, respawnId: Long): List[RespawnSchedule] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "SELECT * FROM respawn_schedules WHERE respawn_id = ? AND active ORDER BY anchor_at;")
    try {
      statement.setLong(1, respawnId)
      collectSchedules(statement.executeQuery())
    } finally statement.close()
  }

  def schedulesForUser(guildId: String, userId: String): List[RespawnSchedule] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "SELECT * FROM respawn_schedules WHERE user_id = ? AND active ORDER BY anchor_at;")
    try {
      statement.setString(1, userId)
      collectSchedules(statement.executeQuery())
    } finally statement.close()
  }

  def deactivateSchedule(guildId: String, scheduleId: Long): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement("UPDATE respawn_schedules SET active = FALSE WHERE id = ?;")
    try {
      statement.setLong(1, scheduleId)
      statement.executeUpdate()
    } finally statement.close()
  }

  // --- reserved occurrences -----------------------------------------------

  def reserveOccurrence(guildId: String, scheduleId: Long, respawnId: Long, userId: String,
                        userName: String, nickname: String, characterName: String, startsAt: ZonedDateTime,
                        durationMinutes: Int): Option[RespawnClaim] = withGuild(guildId) { conn =>
    // ON CONFLICT against the partial unique index, so booking the same slot
    // twice is a no-op rather than a duplicate — the materialiser runs on every
    // sweep and must be idempotent.
    val statement = conn.prepareStatement(
      """INSERT INTO respawn_claims
        |(respawn_id, user_id, user_name, character_name, status, queue_position,
        | claimed_at, starts_at, ends_at, duration_minutes, warned, kind, schedule_id, nickname)
        |VALUES (?, ?, ?, ?, 'reserved', 0, NOW(), ?,
        |        CAST(? AS TIMESTAMPTZ) + make_interval(mins => ?), ?, FALSE, 'scheduled', ?, ?)
        |ON CONFLICT DO NOTHING
        |RETURNING *;""".stripMargin)
    try {
      statement.setLong(1, respawnId)
      statement.setString(2, userId)
      statement.setString(3, userName)
      statement.setString(4, characterName)
      statement.setTimestamp(5, Timestamp.from(startsAt.toInstant))
      statement.setTimestamp(6, Timestamp.from(startsAt.toInstant))
      statement.setInt(7, durationMinutes)
      statement.setInt(8, durationMinutes)
      statement.setLong(9, scheduleId)
      statement.setString(10, nickname)
      val result = statement.executeQuery()
      if (result.next()) Some(readClaim(result)) else None
    } finally statement.close()
  }

  def reservationsFor(guildId: String, respawnId: Long, now: ZonedDateTime): List[RespawnClaim] =
    withGuild(guildId) { conn =>
      val statement = conn.prepareStatement(
        """SELECT * FROM respawn_claims
          |WHERE respawn_id = ? AND status = 'reserved' AND starts_at > ?
          |ORDER BY starts_at;""".stripMargin)
      try {
        statement.setLong(1, respawnId)
        statement.setTimestamp(2, Timestamp.from(now.toInstant))
        collectClaims(statement.executeQuery())
      } finally statement.close()
    }

  def settledOccurrences(guildId: String, from: ZonedDateTime,
                         to: Option[ZonedDateTime], respawnId: Option[Long]): List[ScheduleOccurrence] =
    withGuild(guildId) { conn =>
      // Anything but reserved or active. Those two are the day still standing —
      // booked, or being hunted — and every other status is the rule having
      // stopped speaking for it, whichever way that happened.
      val statement = conn.prepareStatement(
        """SELECT schedule_id, starts_at FROM respawn_claims
          |WHERE schedule_id IS NOT NULL AND status NOT IN ('reserved', 'active')
          |  AND starts_at >= ?
          |  AND (CAST(? AS TIMESTAMPTZ) IS NULL OR starts_at <= CAST(? AS TIMESTAMPTZ))
          |  AND (CAST(? AS BIGINT) IS NULL OR respawn_id = CAST(? AS BIGINT));""".stripMargin)
      try {
        val until = to.map(t => Timestamp.from(t.toInstant)).orNull
        statement.setTimestamp(1, Timestamp.from(from.toInstant))
        statement.setTimestamp(2, until)
        statement.setTimestamp(3, until)
        respawnId match {
          case Some(id) => statement.setLong(4, id); statement.setLong(5, id)
          case None     => statement.setNull(4, java.sql.Types.BIGINT); statement.setNull(5, java.sql.Types.BIGINT)
        }
        val result = statement.executeQuery()
        val out = ListBuffer[ScheduleOccurrence]()
        while (result.next())
          out += ScheduleOccurrence(result.getLong("schedule_id"), toZoned(result.getTimestamp("starts_at")))
        out.toList
      } finally statement.close()
    }

  def skipOccurrence(guildId: String, scheduleId: Long, respawnId: Long, userId: String,
                     userName: String, nickname: String, characterName: String,
                     startsAt: ZonedDateTime, durationMinutes: Int, outcome: String): Boolean =
    withGuild(guildId) { conn =>
      // Born cancelled. The row exists only to say the day is settled, which is
      // what stops the rule predicting it again and what frees the time for
      // somebody else to book — ON CONFLICT against the same partial unique
      // index the materialiser relies on, so a day that already has a row is
      // left exactly as it is rather than overwritten.
      val statement = conn.prepareStatement(
        """INSERT INTO respawn_claims
          |(respawn_id, user_id, user_name, character_name, status, queue_position,
          | claimed_at, starts_at, ends_at, duration_minutes, warned, kind, schedule_id, nickname,
          | outcome, ended_at)
          |VALUES (?, ?, ?, ?, 'cancelled', 0, NOW(), ?,
          |        CAST(? AS TIMESTAMPTZ) + make_interval(mins => ?), ?, FALSE, 'scheduled', ?, ?,
          |        ?, NOW())
          |ON CONFLICT DO NOTHING
          |RETURNING id;""".stripMargin)
      try {
        statement.setLong(1, respawnId)
        statement.setString(2, userId)
        statement.setString(3, userName)
        statement.setString(4, characterName)
        statement.setTimestamp(5, Timestamp.from(startsAt.toInstant))
        statement.setTimestamp(6, Timestamp.from(startsAt.toInstant))
        statement.setInt(7, durationMinutes)
        statement.setInt(8, durationMinutes)
        statement.setLong(9, scheduleId)
        statement.setString(10, nickname)
        statement.setString(11, outcome)
        statement.executeQuery().next()
      } finally statement.close()
    }

  def reassignReservation(guildId: String, claimId: Long, toUserId: String,
                          toUserName: String, toNickname: String): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      // schedule_id goes to NULL and the character with it: this day is now a
      // booking of its own, in somebody else's name, and neither the rule it
      // came from nor the character the old owner meant to bring still applies.
      // Clearing schedule_id also leaves the rule's own day settled by the
      // cancelled row skipOccurrence writes alongside this, rather than by this
      // row wearing two owners at once.
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims
          |SET user_id = ?, user_name = ?, nickname = ?, character_name = '', kind = 'adhoc',
          |    schedule_id = NULL, asked_at = NULL, request_deadline = NULL,
          |    requester_user_id = NULL, requester_user_name = NULL, requester_nickname = NULL,
          |    requested_starts_at = NULL, requested_duration_minutes = NULL,
          |    confirmed_at = NULL
          |WHERE id = ? AND status = 'reserved'
          |RETURNING *;""".stripMargin)
      try {
        statement.setString(1, toUserId)
        statement.setString(2, toUserName)
        statement.setString(3, toNickname)
        statement.setLong(4, claimId)
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def slotAt(guildId: String, respawnId: Long, startsAt: ZonedDateTime): Option[RespawnClaim] =
    withGuild(guildId) { conn =>
      val statement = conn.prepareStatement(
        """SELECT * FROM respawn_claims
          |WHERE respawn_id = ? AND starts_at = ? AND status IN ('reserved', 'active')
          |ORDER BY status LIMIT 1;""".stripMargin)
      try {
        statement.setLong(1, respawnId)
        statement.setTimestamp(2, Timestamp.from(startsAt.toInstant))
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def withRespawnLock[A](guildId: String, respawnId: Long)(body: => A): A =
    withExclusiveTransaction(guildId) { conn =>
      lockRespawn(conn, respawnId)
      // `body` opens connections of its own rather than joining this
      // transaction, which is enough: whoever else wants this spawn is stopped
      // at the lock above until this commits, so the reads inside cannot be
      // interleaved with another decision about the same spawn.
      //
      // This one connection comes from outside the pool — see
      // ConnectionProvider.guildUnpooled. It is held for the whole of `body`
      // while `body` asks for more, and enough claims landing together would
      // otherwise have every pooled connection held by a lock holder waiting
      // for a pooled connection.
      body
    }

  def dueReservations(guildId: String, now: ZonedDateTime): List[RespawnClaim] = withGuild(guildId) { conn =>
    // A slot with an unanswered request waits, even once its start has come and
    // gone: its owner has until a few minutes into the hunt to say they are
    // there, and starting it for them in the meantime would answer for them.
    // Nothing is lost by waiting — the sweep resolves the request first, and a
    // slot that then starts runs its full length from whenever that is.
    val statement = conn.prepareStatement(
      """SELECT * FROM respawn_claims
        |WHERE status = 'reserved' AND starts_at <= ? AND requester_user_id IS NULL
        |ORDER BY starts_at;""".stripMargin)
    try {
      statement.setTimestamp(1, Timestamp.from(now.toInstant))
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def missedReservations(guildId: String, now: ZonedDateTime): List[RespawnClaim] = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "SELECT * FROM respawn_claims WHERE status = 'reserved' AND ends_at <= ? ORDER BY starts_at;")
    try {
      statement.setTimestamp(1, Timestamp.from(now.toInstant))
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def startReservation(guildId: String, claimId: Long, startsAt: ZonedDateTime,
                       endsAt: ZonedDateTime, confirmBy: ZonedDateTime,
                       confirmedAt: Option[ZonedDateTime]): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      // `confirm_by` is stamped whether or not the slot ends up confirmed — it
      // records that this claim began as a booking, which stays true either way.
      // What keeps a confirmed one safe from the sweep is `confirmed_at`.
      //
      // COALESCE, so `confirmedAt` can only ever *add* a confirmation: passing
      // None leaves an existing one alone (somebody who pressed Confirm on the
      // reminder stays confirmed), and passing a time on an already-confirmed
      // slot keeps the moment they actually answered rather than restamping it.
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims
          |SET status = 'active', starts_at = ?, ends_at = ?, warned = FALSE, confirm_by = ?,
          |    confirmed_at = COALESCE(confirmed_at, ?)
          |WHERE id = ? AND status = 'reserved'
          |RETURNING *;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(startsAt.toInstant))
        statement.setTimestamp(2, Timestamp.from(endsAt.toInstant))
        statement.setTimestamp(3, Timestamp.from(confirmBy.toInstant))
        confirmedAt match {
          case Some(at) => statement.setTimestamp(4, Timestamp.from(at.toInstant))
          case None     => statement.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        }
        statement.setLong(5, claimId)
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def confirmClaim(guildId: String, claimId: Long, at: ZonedDateTime): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      // `confirmed_at IS NULL` makes a second press a no-op rather than a
      // restamp, so a double-click answers "already confirmed" instead of
      // quietly moving the record of when they turned up.
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims
          |SET confirmed_at = ?
          |WHERE id = ? AND status IN ('reserved', 'active') AND confirmed_at IS NULL
          |RETURNING *;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(at.toInstant))
        statement.setLong(2, claimId)
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def unconfirmedClaims(guildId: String, now: ZonedDateTime): List[RespawnClaim] = withGuild(guildId) { conn =>
    // Limbo is excluded: such a claim's time is already up and a handover is
    // deciding it, so giving up on it again would advance that twice.
    val statement = conn.prepareStatement(
      """SELECT * FROM respawn_claims
        |WHERE status = 'active' AND confirmed_at IS NULL AND confirm_by IS NOT NULL
        |  AND confirm_by <= ? AND limbo_until IS NULL
        |ORDER BY confirm_by;""".stripMargin)
    try {
      statement.setTimestamp(1, Timestamp.from(now.toInstant))
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def confirmPendingClaims(guildId: String, at: ZonedDateTime): Int = withGuild(guildId) { conn =>
    // Same shape as `unconfirmedClaims` without its deadline test: the point is
    // to catch the ones whose deadline has *not* arrived yet as well, since
    // those are the hunts a guild switching autoclaim on is trying to save.
    // Limbo is excluded for the same reason it is there — such a claim's time is
    // already up and a handover is deciding it.
    val statement = conn.prepareStatement(
      """UPDATE respawn_claims
        |SET confirmed_at = ?
        |WHERE status = 'active' AND confirmed_at IS NULL AND confirm_by IS NOT NULL
        |  AND limbo_until IS NULL;""".stripMargin)
    try {
      statement.setTimestamp(1, Timestamp.from(at.toInstant))
      statement.executeUpdate()
    } finally statement.close()
  }

  def reserveFor(guildId: String, respawnId: Long, userId: String, userName: String, nickname: String,
                 startsAt: ZonedDateTime, durationMinutes: Int): RespawnClaim = withGuild(guildId) { conn =>
    // No schedule_id: this is a one-off booking handed to whoever asked, not an
    // occurrence of anybody's standing rule. It still activates through the same
    // due-slot path, which keys off the status rather than the schedule.
    val statement = conn.prepareStatement(
      """INSERT INTO respawn_claims
        |(respawn_id, user_id, user_name, character_name, status, queue_position,
        | claimed_at, starts_at, ends_at, duration_minutes, warned, kind, nickname)
        |VALUES (?, ?, ?, '', 'reserved', 0, NOW(), ?,
        |        CAST(? AS TIMESTAMPTZ) + make_interval(mins => ?), ?, FALSE, 'adhoc', ?)
        |RETURNING *;""".stripMargin)
    try {
      statement.setLong(1, respawnId)
      statement.setString(2, userId)
      statement.setString(3, userName)
      statement.setTimestamp(4, Timestamp.from(startsAt.toInstant))
      statement.setTimestamp(5, Timestamp.from(startsAt.toInstant))
      statement.setInt(6, durationMinutes)
      statement.setInt(7, durationMinutes)
      statement.setString(8, nickname)
      val result = statement.executeQuery()
      result.next()
      readClaim(result)
    } finally statement.close()
  }

  def requestOccurrence(guildId: String, claimId: Long, requesterUserId: String,
                        requesterUserName: String, requesterNickname: String, askedAt: ZonedDateTime,
                        deadline: ZonedDateTime,
                        wanted: Option[(ZonedDateTime, Int)]): Option[RespawnClaim] =
    withGuildTransaction(guildId) { conn =>
      // `asked_at IS NULL` is the whole rule: the owner is asked once per slot,
      // and two people booking over it at the same moment cannot both get in.
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims
          |SET asked_at = ?, request_deadline = ?, requester_user_id = ?, requester_user_name = ?,
          |    requester_nickname = ?, requested_starts_at = ?, requested_duration_minutes = ?
          |WHERE id = ? AND status = 'reserved' AND asked_at IS NULL
          |RETURNING *;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(askedAt.toInstant))
        statement.setTimestamp(2, Timestamp.from(deadline.toInstant))
        statement.setString(3, requesterUserId)
        statement.setString(4, requesterUserName)
        statement.setString(5, requesterNickname)
        wanted match {
          case Some((start, minutes)) =>
            statement.setTimestamp(6, Timestamp.from(start.toInstant))
            statement.setInt(7, minutes)
          case None =>
            statement.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            statement.setNull(7, java.sql.Types.INTEGER)
        }
        statement.setLong(8, claimId)
        val result = statement.executeQuery()
        if (result.next()) Some(readClaim(result)) else None
      } finally statement.close()
    }

  def slotsNeedingReminder(guildId: String, now: ZonedDateTime, leadMinutes: Int): List[RespawnClaim] =
    withGuild(guildId) { conn =>
      val statement = conn.prepareStatement(
        """SELECT * FROM respawn_claims
          |WHERE status = 'reserved' AND warned = FALSE AND starts_at > ? AND starts_at <= ?
          |ORDER BY starts_at;""".stripMargin)
      try {
        statement.setTimestamp(1, Timestamp.from(now.toInstant))
        statement.setTimestamp(2, Timestamp.from(now.plusMinutes(leadMinutes.toLong).toInstant))
        collectClaims(statement.executeQuery())
      } finally statement.close()
    }

  def allSchedules(guildId: String): List[RespawnSchedule] = withGuild(guildId) { conn =>
    val statement = conn.createStatement()
    try collectSchedules(statement.executeQuery(
      "SELECT * FROM respawn_schedules WHERE active ORDER BY respawn_id, anchor_at;"))
    finally statement.close()
  }

  def keepOccurrence(guildId: String, claimId: Long): Option[RespawnClaim] = withGuild(guildId) { conn =>
    // asked_at deliberately survives: the answer stands for this slot, so
    // nobody may ask about it again.
    val statement = conn.prepareStatement(
      """UPDATE respawn_claims
        |SET request_deadline = NULL, requester_user_id = NULL, requester_user_name = NULL,
        |    requester_nickname = NULL,
        |    requested_starts_at = NULL, requested_duration_minutes = NULL
        |WHERE id = ? AND status = 'reserved'
        |RETURNING *;""".stripMargin)
    try {
      statement.setLong(1, claimId)
      val result = statement.executeQuery()
      if (result.next()) Some(readClaim(result)) else None
    } finally statement.close()
  }

  def expiredRequests(guildId: String, now: ZonedDateTime): List[RespawnClaim] = withGuild(guildId) { conn =>
    // `confirmed_at IS NULL` is belt and braces. Confirming already clears the
    // request (see RespawnService.settleRequestOn), so a confirmed slot should
    // never have one outstanding — but passing on a slot whose owner has said
    // they are coming is the one outcome here that cannot be undone.
    val statement = conn.prepareStatement(
      """SELECT * FROM respawn_claims
        |WHERE status = 'reserved' AND requester_user_id IS NOT NULL AND request_deadline <= ?
        |  AND confirmed_at IS NULL
        |ORDER BY request_deadline;""".stripMargin)
    try {
      statement.setTimestamp(1, Timestamp.from(now.toInstant))
      collectClaims(statement.executeQuery())
    } finally statement.close()
  }

  def cancelReservationsOf(guildId: String, scheduleId: Long, outcome: String): Unit =
    withGuild(guildId) { conn =>
      val statement = conn.prepareStatement(
        """UPDATE respawn_claims SET status = 'cancelled', outcome = ?, ended_at = NOW()
          |WHERE schedule_id = ? AND status = 'reserved';""".stripMargin)
      try {
        statement.setString(1, outcome)
        statement.setLong(2, scheduleId)
        statement.executeUpdate()
      } finally statement.close()
    }

  // --- member preferences -------------------------------------------------

  def userPrefs(guildId: String, userId: String): RespawnUserPrefs = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      "SELECT default_duration, warn_minutes FROM respawn_user_prefs WHERE user_id = ?;")
    try {
      statement.setString(1, userId)
      val result = statement.executeQuery()
      if (!result.next()) RespawnUserPrefs.none(userId)
      else {
        // getInt returns 0 for SQL NULL, so wasNull is the only way to tell
        // "reminders off" from "never chose".
        val duration = result.getInt("default_duration")
        val durationSet = !result.wasNull()
        val warn = result.getInt("warn_minutes")
        val warnSet = !result.wasNull()
        RespawnUserPrefs(userId,
          if (durationSet) Some(duration) else None,
          if (warnSet) Some(warn) else None)
      }
    } finally statement.close()
  }

  def saveUserPrefs(guildId: String, prefs: RespawnUserPrefs): Unit = withGuild(guildId) { conn =>
    val statement = conn.prepareStatement(
      """INSERT INTO respawn_user_prefs (user_id, default_duration, warn_minutes)
        |VALUES (?, ?, ?)
        |ON CONFLICT (user_id) DO UPDATE SET
        |default_duration = EXCLUDED.default_duration,
        |warn_minutes = EXCLUDED.warn_minutes;""".stripMargin)
    try {
      statement.setString(1, prefs.userId)
      def setOptionalInt(index: Int, value: Option[Int]): Unit = value match {
        case Some(number) => statement.setInt(index, number)
        case None         => statement.setNull(index, java.sql.Types.INTEGER)
      }
      setOptionalInt(2, prefs.defaultDurationMinutes)
      setOptionalInt(3, prefs.warnMinutes)
      statement.executeUpdate()
    } finally statement.close()
  }

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
      // The note of what the pinned board post shows goes with the post: the
      // forum is being torn down, and a digest left behind would tell a guild
      // that set the bot up again that its brand-new board was already correct.
      statement.executeUpdate("DELETE FROM respawn_board_state;")
      // respawn_user_prefs is left alone alongside respawn_stamina: both belong
      // to the member rather than to this particular setup, and someone who
      // chose a 3h default claim shouldn't silently lose it because an admin
      // removed and re-added a world.
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
