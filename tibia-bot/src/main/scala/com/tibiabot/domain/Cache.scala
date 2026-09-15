package com.tibiabot.domain

import java.time.ZonedDateTime

case class BoostedCache(boss: String, creature: String, bossChanged: String, creatureChanged: String)
case class DeathsCache(world: String, name: String, time: String)
case class LevelsCache(world: String, name: String, level: String, vocation: String, lastLogin: String, time: String)
case class ListCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, last_login: String, updatedTime: ZonedDateTime)

/** The last character sheet a world's poll read for somebody who was online.
 *
 *  The world stream already fetches a full sheet for every recently-online
 *  character on every poll — that is how deaths are found — and everything on
 *  it but the death list was being dropped for anybody no discord listed by
 *  name. This keeps the two fields that outlive the poll: which guild they are
 *  in, and what vocation they are.
 *
 *  Written so that a reader hours later can still answer both about a character
 *  nothing else holds a record of. The hunted and allied lists keep sheets of
 *  their own for the players somebody named, and the highscore tables know
 *  vocations for the world's top thousand; this is the one source that covers
 *  the ordinary character who was simply there — which, on a PVP day, is most
 *  of the people in the post.
 *
 *  `name` is lowercased, the key every other name in this bot is matched on,
 *  with `displayName` keeping the casing tibia.com showed. */
case class SheetCache(world: String, name: String, displayName: String, guild: String, vocation: String, level: Int, seen: ZonedDateTime)
