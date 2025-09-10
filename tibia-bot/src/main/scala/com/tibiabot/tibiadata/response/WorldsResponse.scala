package com.tibiabot.tibiadata.response

case class RegularWorld(
    name: String,
    status: String,
    players_online: Double,
    location: String,
    pvp_type: String,
    premium_only: Boolean,
    transfer_type: String,
    battleye_protected: Boolean,
    battleye_date: String,
    game_world_type: String,
    tournament_world_type: String
)

case class Worlds(
    players_online: Double,
    record_players: Double,
    record_date: String,
    regular_worlds: List[RegularWorld]
)

case class WorldsResponse(worlds: Worlds, information: Information)