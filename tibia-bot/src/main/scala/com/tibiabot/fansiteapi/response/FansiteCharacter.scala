package com.tibiabot.fansiteapi.response

/** The slice of CipSoft's fansite API character payload the bot actually reads.
 *
 *  Deliberately narrower than the published schema. The client always asks for
 *  `?include=characterDeathsData`, so `characterAdminInformation`,
 *  `characterAccountInformation` and `accountCharacters` come back null and are
 *  not modelled at all — spray-json ignores object members no case class field
 *  names, so adding a section later is a matter of declaring it here rather
 *  than of unblocking a parse failure.
 *
 *  Field names mirror the wire format exactly (camelCase, unlike TibiaData's
 *  snake_case) so the decoders stay derivable; the translation into the bot's
 *  own vocabulary happens in one place, [[com.tibiabot.fansiteapi.CharacterMapping]]. */
case class FansiteGameInformation(
    characterName: String,
    level: Int,
    vocation: String,
    isPromoted: Boolean,
    sex: String,
    world: String,
    residence: String,
    deletedTimestamp: Long,
    comment: Option[String],
    wasRecentlyTradedAndNotRenamed: Boolean,
    spouse: Option[String],
    formerWorld: Option[String],
    lastLogin: Long,
    isPremium: Boolean,
    formerNames: List[String],
    guildName: Option[String],
    guildRank: Option[String],
    achievementPoints: Int
)

/** One entry in a death's killer list.
 *
 *  This API returns killers and assists as a single list distinguished by
 *  `assist`, where TibiaData splits them into two. `remark` carries the summon
 *  creature for a summon kill — the same value TibiaData puts in its `summon`
 *  field, verified against a live "fire elemental of <player>" death. */
case class FansiteMurderer(
    name: String,
    tradedMurderer: Boolean,
    assist: Boolean,
    playerCharacter: Boolean,
    remark: Option[String]
)

/** `tooMany` is set when tibia.com itself truncated the list rather than when
 *  this API did — the same truncation TibiaData inherits. */
case class FansiteDeath(date: Long, level: Int, murderers: List[FansiteMurderer])
case class FansiteDeaths(tooMany: Boolean, deaths: List[FansiteDeath])

/** Null whenever the section was not requested, so `characterDeathsData` is an
 *  Option even though the client always asks for it. */
case class FansiteCharacterResponse(
    characterGameInformation: FansiteGameInformation,
    characterDeathsData: Option[FansiteDeaths]
)
