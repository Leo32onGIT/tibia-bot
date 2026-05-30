package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{BoostedCache, DeathsCache, LevelsCache, ListCache}
import com.tibiabot.persistence.{CacheRepository, ConnectionProvider}

import java.sql.Timestamp
import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of CacheRepository (deaths + levels). Bodies moved
 *  verbatim from BotApp's getDeathsCache/addDeathsCache/removeDeathsCache and
 *  the levels equivalents — no behaviour change. */
final class JdbcCacheRepository(connectionProvider: ConnectionProvider) extends CacheRepository {

  def getDeaths(world: String): List[DeathsCache] = {
    val conn = connectionProvider.cache()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT world,name,time FROM deaths WHERE world = '$world';")

    val results = new ListBuffer[DeathsCache]()
    while (result.next()) {
      val world = Option(result.getString("world")).getOrElse("")
      val name = Option(result.getString("name")).getOrElse("")
      val time = Option(result.getString("time")).getOrElse("")
      results += DeathsCache(world, name, time)
    }

    statement.close()
    conn.close()
    results.toList
  }

  def addDeath(world: String, name: String, time: String): Unit = {
    val conn = connectionProvider.cache()
    val statement = conn.prepareStatement("INSERT INTO deaths(world,name,time) VALUES (?, ?, ?);")
    statement.setString(1, world)
    statement.setString(2, name)
    statement.setString(3, time)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removeExpiredDeaths(now: ZonedDateTime): Unit = {
    val conn = connectionProvider.cache()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT id,time from deaths;")
    val results = new ListBuffer[Long]()
    while (result.next()) {
      val id = Option(result.getLong("id")).getOrElse(0L)
      val timeDb = Option(result.getString("time")).getOrElse("")
      val timeToDate = ZonedDateTime.parse(timeDb)
      if (now.isAfter(timeToDate.plusMinutes(30)) && id != 0L) {
        results += id
      }
    }
    results.foreach { uid =>
      statement.executeUpdate(s"DELETE from deaths where id = $uid;")
    }
    statement.close()
    conn.close()
  }

  def getLevels(world: String): List[LevelsCache] = {
    val conn = connectionProvider.cache()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT world,name,level,vocation,last_login,time FROM levels WHERE world = '$world';")

    val results = new ListBuffer[LevelsCache]()
    while (result.next()) {
      val world = Option(result.getString("world")).getOrElse("")
      val name = Option(result.getString("name")).getOrElse("")
      val level = Option(result.getString("level")).getOrElse("")
      val vocation = Option(result.getString("vocation")).getOrElse("")
      val lastLogin = Option(result.getString("last_login")).getOrElse("")
      val time = Option(result.getString("time")).getOrElse("")
      results += LevelsCache(world, name, level, vocation, lastLogin, time)
    }

    statement.close()
    conn.close()
    results.toList
  }

  def addLevel(world: String, name: String, level: String, vocation: String, lastLogin: String, time: String): Unit = {
    val conn = connectionProvider.cache()
    val statement = conn.prepareStatement("INSERT INTO levels(world,name,level,vocation,last_login,time) VALUES (?, ?, ?, ?, ?, ?);")
    statement.setString(1, world)
    statement.setString(2, name)
    statement.setString(3, level)
    statement.setString(4, vocation)
    statement.setString(5, lastLogin)
    statement.setString(6, time)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removeExpiredLevels(now: ZonedDateTime): Unit = {
    val conn = connectionProvider.cache()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT id,time from levels;")
    val results = new ListBuffer[Long]()
    while (result.next()) {
      val id = Option(result.getLong("id")).getOrElse(0L)
      val timeDb = Option(result.getString("time")).getOrElse("")
      val timeToDate = ZonedDateTime.parse(timeDb)
      if (now.isAfter(timeToDate.plusHours(25)) && id != 0L) {
        results += id
      }
    }
    results.foreach { uid =>
      statement.executeUpdate(s"DELETE from levels where id = $uid;")
    }
    statement.close()
    conn.close()
  }

  def getList(world: String): List[ListCache] = {
    val conn = connectionProvider.cache()
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'list'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE list (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |world VARCHAR(255) NOT NULL,
           |former_worlds VARCHAR(255),
           |name VARCHAR(255) NOT NULL,
           |former_names VARCHAR(1000),
           |level VARCHAR(255) NOT NULL,
           |guild_name VARCHAR(255),
           |vocation VARCHAR(255) NOT NULL,
           |last_login VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL
           |);""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val result = statement.executeQuery(s"SELECT name,former_names,world,former_worlds,guild_name,level,vocation,last_login,time FROM list WHERE world = '$world';")

    val results = new ListBuffer[ListCache]()
    while (result.next()) {

      val guildName = Option(result.getString("guild_name")).getOrElse("")
      val name = Option(result.getString("name")).getOrElse("")
      val formerNames = Option(result.getString("former_names")).getOrElse("")
      val formerNamesList = formerNames.split(",").toList
      val world = Option(result.getString("world")).getOrElse("")
      val formerWorlds = Option(result.getString("former_worlds")).getOrElse("")
      val formerWorldsList = formerWorlds.split(",").toList
      val level = Option(result.getString("level")).getOrElse("")
      val vocation = Option(result.getString("vocation")).getOrElse("")
      val lastLogin = Option(result.getString("last_login")).getOrElse("")
      val updatedTimeTemporal = Option(result.getTimestamp("time").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z"))
      val updatedTime = updatedTimeTemporal.atZone(ZoneOffset.UTC)

      results += ListCache(name, formerNamesList, world, formerWorldsList, guildName, level, vocation, lastLogin, updatedTime)
    }

    statement.close()
    conn.close()
    results.toList
  }

  def addToList(name: String, formerNames: List[String], world: String, formerWorlds: List[String],
                guild: String, level: String, vocation: String, lastLogin: String, updatedTime: ZonedDateTime): Unit = {
    val conn = connectionProvider.cache()
    val selectStatement = conn.prepareStatement("SELECT name FROM list WHERE LOWER(name) = LOWER(?);")
    selectStatement.setString(1, name)
    val resultSet = selectStatement.executeQuery()

    if (resultSet.next()) {
      // Update existing row
      val updateStatement = conn.prepareStatement(
        s"""
           |UPDATE list
           |SET former_names = ?, world = ?, former_worlds = ?, guild_name = ?, level = ?, vocation = ?, last_login = ?, time = ?
           |WHERE LOWER(name) = LOWER(?);
           |""".stripMargin
      )
      updateStatement.setString(1, formerNames.mkString(","))
      updateStatement.setString(2, world.capitalize)
      updateStatement.setString(3, formerWorlds.mkString(","))
      updateStatement.setString(4, guild)
      updateStatement.setString(5, level)
      updateStatement.setString(6, vocation)
      updateStatement.setString(7, lastLogin)
      updateStatement.setTimestamp(8, Timestamp.from(updatedTime.toInstant))
      updateStatement.setString(9, name)
      updateStatement.executeUpdate()
      updateStatement.close()
    } else {
      // Insert new row
      val insertStatement = conn.prepareStatement(
        s"""
           |INSERT INTO list(name, former_names, world, former_worlds, guild_name, level, vocation, last_login, time)
           |VALUES (?,?,?,?,?,?,?,?,?);
           |""".stripMargin
      )
      insertStatement.setString(1, name)
      insertStatement.setString(2, formerNames.mkString(","))
      insertStatement.setString(3, world.capitalize)
      insertStatement.setString(4, formerWorlds.mkString(","))
      insertStatement.setString(5, guild)
      insertStatement.setString(6, level)
      insertStatement.setString(7, vocation)
      insertStatement.setString(8, lastLogin)
      insertStatement.setTimestamp(9, Timestamp.from(updatedTime.toInstant))
      insertStatement.executeUpdate()
      insertStatement.close()
    }

    selectStatement.close()
    conn.close()
  }

  def removeExpiredList(now: ZonedDateTime): Unit = {
    val conn = connectionProvider.cache()

    // Modify the DELETE statement to include a WHERE clause with the condition for time
    val deleteStatement = conn.prepareStatement("DELETE FROM list WHERE time < ?;")
    deleteStatement.setTimestamp(1, Timestamp.from(now.minus(7, ChronoUnit.DAYS).toInstant))
    deleteStatement.executeUpdate()
    deleteStatement.close()
    conn.close()
  }

  def getBoosted(): List[BoostedCache] = {
    val conn = connectionProvider.cache()
    val statement = conn.createStatement()

    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'boosted_info'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE boosted_info (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |boss VARCHAR(255) NOT NULL,
           |bosschanged VARCHAR(255) NOT NULL,
           |creature VARCHAR(255) NOT NULL,
           |creaturechanged VARCHAR(255) NOT NULL
           );""".stripMargin

      statement.executeUpdate(createListTable)
    }

    // Check if the column already exists in the table
    val bossChangedExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'boosted_info' AND COLUMN_NAME = 'bosschanged'")
    val bossChangedExists = bossChangedExistsQuery.next()
    bossChangedExistsQuery.close()

    // Check if the column already exists in the table
    val creatureChangedExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'boosted_info' AND COLUMN_NAME = 'creaturechanged'")
    val creatureChangedExists = creatureChangedExistsQuery.next()
    creatureChangedExistsQuery.close()

    // Add the column if it doesn't exist
    if (!bossChangedExists) {
      statement.execute("ALTER TABLE boosted_info ADD COLUMN bosschanged VARCHAR(255) DEFAULT '0'")
    }

    // Add the column if it doesn't exist
    if (!creatureChangedExists) {
      statement.execute("ALTER TABLE boosted_info ADD COLUMN creaturechanged VARCHAR(255) DEFAULT '0'")
    }

    val result = statement.executeQuery(s"SELECT boss,creature,bosschanged,creaturechanged FROM boosted_info;")
    val results = new ListBuffer[BoostedCache]()
    while (result.next()) {
      val boss = Option(result.getString("boss")).getOrElse("None")
      val creature = Option(result.getString("creature")).getOrElse("None")
      val bossChanged = Option(result.getString("bosschanged")).getOrElse("0")
      val creatureChanged = Option(result.getString("creaturechanged")).getOrElse("0")
      results += BoostedCache(boss, creature, bossChanged, creatureChanged)
    }

    if (results.isEmpty) {
      // If the result list is empty, insert default values
      val insertStatement = conn.prepareStatement("INSERT INTO boosted_info (boss, creature, bosschanged, creaturechanged) VALUES (?, ?, ?, ?);")
      insertStatement.setString(1, "None") // Default value for boss
      insertStatement.setString(2, "None") // Default value for creature
      insertStatement.setString(3, "0")
      insertStatement.setString(4, "0")
      insertStatement.executeUpdate()
      insertStatement.close()

      results += BoostedCache("None", "None", "0", "0")
    }

    statement.close()
    conn.close()
    results.toList
  }

  def updateBoosted(boss: String, creature: String, bossChanged: String, creatureChanged: String): Unit = {
    val conn = connectionProvider.cache()
    val statement = conn.createStatement()

    val result = statement.executeQuery(s"SELECT boss,creature,bosschanged,creaturechanged FROM boosted_info;")

    val results = new ListBuffer[BoostedCache]()
    while (result.next()) {
      val boss = Option(result.getString("boss")).getOrElse("None")
      val creature = Option(result.getString("creature")).getOrElse("None")
      val bossChanged = Option(result.getString("bosschanged")).getOrElse("0")
      val creatureChanged = Option(result.getString("creaturechanged")).getOrElse("0")

      results += BoostedCache(boss, creature, bossChanged, creatureChanged)
    }
    statement.close()

    if (results.isEmpty) {
      // If the result list is empty, insert default values
      val insertStatement = conn.prepareStatement("INSERT INTO boosted_info (boss, creature, bosschanged, creaturechanged) VALUES (?, ?, ?, ?);")
      insertStatement.setString(1, "None") // Default value for boss
      insertStatement.setString(2, "None") // Default value for creature
      insertStatement.setString(3, "0")
      insertStatement.setString(4, "0")
      insertStatement.executeUpdate()
      insertStatement.close()
    }

    // update category if exists
    if (boss != "") {
      val statement = conn.prepareStatement("UPDATE boosted_info SET boss = ?;")
      statement.setString(1, boss)
      statement.executeUpdate()
      statement.close()
    }
    if (creature != "") {
      val statement = conn.prepareStatement("UPDATE boosted_info SET creature = ?;")
      statement.setString(1, creature)
      statement.executeUpdate()
      statement.close()
    }
    if (bossChanged != "") {
      val statement = conn.prepareStatement("UPDATE boosted_info SET bosschanged = ?;")
      statement.setString(1, bossChanged)
      statement.executeUpdate()
      statement.close()
    }
    if (creatureChanged != "") {
      val statement = conn.prepareStatement("UPDATE boosted_info SET creaturechanged = ?;")
      statement.setString(1, creatureChanged)
      statement.executeUpdate()
      statement.close()
    }

    conn.close()
  }
}
