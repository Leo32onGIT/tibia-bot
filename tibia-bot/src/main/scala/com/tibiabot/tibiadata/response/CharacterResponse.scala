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
    account_status: String
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
