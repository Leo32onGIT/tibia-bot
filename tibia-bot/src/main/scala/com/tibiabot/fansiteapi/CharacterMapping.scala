package com.tibiabot.fansiteapi

import com.tibiabot.fansiteapi.response._
import com.tibiabot.tibiadata.response.{
  AccountInformation, Api, Character, CharacterResponse, CharacterSheet, Deaths, Guild, Information, Killers, Status
}

import java.time.Instant

/** Translates a fansite API character payload into the `CharacterResponse` the rest
 *  of the bot already speaks.
 *
 *  Mapping rather than replacing the model keeps the migration small: every
 *  consumer downstream reads exactly the type it reads today, so none of them nor
 *  their tests change. Worth paying for only because the correspondence is
 *  field-for-field — verified against 180 live characters, agreeing on every field
 *  the bot reads.
 *
 *  Two fields carry real translation rather than a rename:
 *
 *  1. '''Vocation.''' TibiaData serves the promoted display name ("Elite Knight");
 *     this API serves a base vocation plus `isPromoted`. [[vocationName]]
 *     reconstructs the display string, so rows written to `bot_cache.levels` and
 *     `bot_cache.list` stay consistent with what is there.
 *
 *  2. '''Killers and assists.''' TibiaData splits them; this API returns one list
 *     tagged with `assist`. Partitioning preserves relative order, which matters:
 *     the death embed takes its thumbnail from `killers.lastOption`.
 *
 *  Fields from sections the client does not request are filled with what TibiaData
 *  reports for a character that has none. Nothing in the bot reads them, so they
 *  satisfy the case class rather than being believed. */
object CharacterMapping {

  /** Marks a mapped sheet's provenance in the `information` block. Nothing
   *  reads `api` — only `timestamp` is consumed, by
   *  [[com.tibiabot.tibiadata.OriginTimestamp]] — but a sheet that says where
   *  it came from is worth more in a log line or a Redis dump than one
   *  impersonating TibiaData. */
  private val FansiteApi = Api(version = 1, release = "fansiteapi.v1", commit = "")

  /** TibiaData's display string for a vocation, given this API's base name and
   *  promotion flag. All six vocations are listed rather than deriving the
   *  promoted form, because the promoted names share no pattern. An unknown
   *  vocation passes through capitalised instead of throwing: a vocation added
   *  to the game before this table is updated should degrade to a slightly
   *  wrong label, not to a character the bot cannot parse at all. */
  def vocationName(vocation: String, promoted: Boolean): String =
    (vocation.toLowerCase, promoted) match {
      case ("knight", false)   => "Knight"
      case ("knight", true)    => "Elite Knight"
      case ("paladin", false)  => "Paladin"
      case ("paladin", true)   => "Royal Paladin"
      case ("sorcerer", false) => "Sorcerer"
      case ("sorcerer", true)  => "Master Sorcerer"
      case ("druid", false)    => "Druid"
      case ("druid", true)     => "Elder Druid"
      case ("monk", false)     => "Monk"
      case ("monk", true)      => "Exalted Monk"
      case ("none", _)         => "None"
      case (other, _)          => other.capitalize
    }

  /** A unix timestamp as the ISO-8601 instant TibiaData serves and the bot
   *  parses with `ZonedDateTime.parse`. Zero means "never" on this API (an
   *  account that has not logged in, a character not scheduled for deletion),
   *  which TibiaData represents by omitting the field. */
  private def instantString(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).toString

  private def optionalInstant(epochSeconds: Long): Option[String] =
    if (epochSeconds <= 0L) None else Some(instantString(epochSeconds))

  /** An empty list reads as absent, matching TibiaData, which omits
   *  `former_names`/`former_worlds` rather than sending `[]`. Callers use
   *  `.getOrElse(Nil)` either way, so this is about round-tripping the same
   *  shape through Redis, not about behaviour. */
  private def nonEmptyList(values: List[String]): Option[List[String]] =
    if (values.isEmpty) None else Some(values)

  private def killer(m: FansiteMurderer): Killers =
    Killers(name = m.name, player = m.playerCharacter, traded = m.tradedMurderer, summon = m.remark.getOrElse(""))

  private def death(d: FansiteDeath): Deaths = {
    val (assists, killers) = d.murderers.partition(_.assist)
    Deaths(
      time = instantString(d.date),
      level = d.level.toDouble,
      killers = killers.map(killer),
      assists = assists.map(killer),
      // TibiaData's `reason` is prose it assembles from the same killer list.
      // Nothing in the bot reads it, so it is left empty rather than
      // reconstructed — a rebuilt sentence would be a guess at another API's
      // phrasing that no reader would ever see.
      reason = ""
    )
  }

  private def character(g: FansiteGameInformation): Character =
    Character(
      name = g.characterName,
      former_names = nonEmptyList(g.formerNames),
      sex = g.sex,
      // "None" is what TibiaData reports for an untitled character, and the
      // title section is one the client does not request.
      title = "None",
      unlocked_titles = 0d,
      vocation = vocationName(g.vocation, g.isPromoted),
      level = g.level.toDouble,
      achievement_points = g.achievementPoints.toDouble,
      world = g.world,
      former_worlds = nonEmptyList(g.formerWorld.toList),
      residence = g.residence,
      married_to = g.spouse,
      houses = None,
      guild = g.guildName.map(name => Guild(name = name, rank = g.guildRank.getOrElse(""))),
      last_login = optionalInstant(g.lastLogin),
      account_status = if (g.isPremium) "Premium Account" else "Free Account"
    )

  /** Map a decoded payload, stamping it with the moment the upstream copy was
   *  built.
   *
   *  `origin` comes from the response's `Last-Modified` header, which this API
   *  pins to the generation time of the cached copy while `Date` advances —
   *  measured holding still for 299s and rolling on the first request after
   *  that. It is therefore the exact analogue of TibiaData's
   *  `information.timestamp`, and supplying it here is what lets
   *  [[com.tibiabot.tibiadata.AgeCachedTibiaApi]] and
   *  [[com.tibiabot.tibiadata.SharedWorldTibiaApi]] schedule this source with
   *  no changes at all.
   *
   *  A response without the header yields None, which both of those classes
   *  already treat as "unknown freshness": the sheet is used but never cached,
   *  so the character is simply re-fetched next cycle. */
  def toCharacterResponse(payload: FansiteCharacterResponse, origin: Option[Instant]): CharacterResponse =
    CharacterResponse(
      character = CharacterSheet(
        character = character(payload.characterGameInformation),
        deaths = payload.characterDeathsData.map(_.deaths.map(death)),
        account_information = None: Option[AccountInformation]
      ),
      information = Information(api = FansiteApi, timestamp = origin.map(_.toString), status = Status(200d))
    )
}
