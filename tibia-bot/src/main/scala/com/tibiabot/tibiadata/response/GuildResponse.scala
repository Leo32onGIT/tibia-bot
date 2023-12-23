package com.tibiabot.tibiadata.response

case class Invites(
  name: String,
  date: String
)
case class Members(
  name: String,
  rank: String,
  title: String,
  vocation: String,
  joined: String,
  level: Double,
  status: String
)
case class GuildHalls(
  name: String,
  paid_until: String,
  world: String
)
case class GuildData(
  name: String,
  world: String,
  logo_url: String,
  description: String,
  guildhalls: Option[List[GuildHalls]],
  active: Boolean,
  founded: String,
  open_applications: Boolean,
  homepage: String,
  in_war: Boolean,
  disband_date: String,
  disband_condition: String,
  players_online: Double,
  players_offline: Double,
  members_total: Double,
  members_invited: Double,
  members: Option[List[Members]],
  invites: Option[List[Invites]]
)
case class GuildResponse(
  guild: GuildData,
  information: Information
)
