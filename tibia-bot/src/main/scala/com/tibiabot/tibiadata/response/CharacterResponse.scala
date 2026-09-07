package com.tibiabot.tibiadata.response

case class Houses(name: String, town: String, paid: String, houseid: Double)
case class Guild(name: String, rank: String)
case class Character(
    name: String,
    former_names: Option[List[String]],
    sex: String,
    title: String,
    unlocked_titles: Double,
    vocation: String,
    level: Double,
    achievement_points: Double,
    world: String,
    former_worlds: Option[List[String]],
    residence: String,
    married_to: Option[String],
    houses: Option[List[Houses]],
    guild: Option[Guild],
    last_login: Option[String],
    account_status: String,
    /** Whether the character was traded in the last six months.
     *
     *  Absent from the payload rather than false when it does not apply -
     *  TibiaData marks it `omitempty` - so it is optional here, and None and
     *  Some(false) mean the same thing. It decays: TibiaData reads it off the
     *  " (traded)" suffix tibia.com puts on the name, which goes when the new
     *  owner renames the character or six months pass. Anything that needs to
     *  know whether a character *was* traded at some past moment has to have
     *  recorded it then - it cannot be asked for afterwards. */
    traded: Option[Boolean]
)
case class Killers(name: String, player: Boolean, traded: Boolean, summon: String)
case class Deaths(time: String, level: Double, killers: List[Killers], assists: List[Killers], reason: String)
case class AccountInformation(position: Option[String], created: String, loyalty_title: Option[String])
case class CharacterSheet(
    character: Character,
    deaths: Option[List[Deaths]],
    account_information: Option[AccountInformation]
)
case class CharacterResponse(character: CharacterSheet, information: Information)
