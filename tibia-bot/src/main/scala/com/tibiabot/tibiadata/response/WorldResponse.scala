package com.tibiabot.tibiadata.response

case class OnlinePlayers(name: String, level: Double, vocation: String)
case class World(
    name: String,
    status: String,
    players_online: Double,
    record_players: Double,
    record_date: String,
    creation_date: String,
    location: String,
    pvp_type: String,
    premium_only: Boolean,
    transfer_type: String,
    world_quest_titles: Option[List[String]],
    battleye_protected: Boolean,
    battleye_date: String,
    game_world_type: String,
    tournament_world_type: String,
    online_players: Option[List[OnlinePlayers]]
)
case class WorldResponse(world: World, information: Information)
