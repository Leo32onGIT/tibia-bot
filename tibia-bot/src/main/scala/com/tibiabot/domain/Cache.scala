package com.tibiabot.domain

import java.time.ZonedDateTime

case class BoostedCache(boss: String, creature: String, bossChanged: String, creatureChanged: String)
case class DeathsCache(world: String, name: String, time: String)
case class LevelsCache(world: String, name: String, level: String, vocation: String, lastLogin: String, time: String)
case class ListCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, last_login: String, updatedTime: ZonedDateTime)
