package com.tibiabot.tibiadata.response

/**
case class CreatureList(
    featured: Boolean,
    image_url: String,
    name: String
)
**/
case class Race(
    be_convinced: Boolean,
    be_paralysed: Boolean,
    be_summoned: Boolean,
    behaviour: String,
    convinced_mana: Double,
    description: String,
    experience_points: Double,
    featured: Boolean,
    healed: Option[List[String]],
    hitpoints: Double,
    image_url: String,
    immune: Option[List[String]],
    is_lootable: Boolean,
    loot_list: List[String],
    name: String,
    race: String,
    see_invisible: Boolean,
    strong: Option[List[String]],
    summoned_mana: Double,
    weakness: Option[List[String]]
)
case class RaceResponse(creature: Option[Race], information: Information)
