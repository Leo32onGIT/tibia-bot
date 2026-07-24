package com.tibiabot.migration

import com.tibiabot.domain.{Guilds, Players}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/** One guild's hunted/allied watchlists, portable between Export/Import — see
 *  the "Migrate Blue/Red hunted+allied data" plan. Deliberately carries
 *  nothing else (no world config, no channel IDs): those depend on the
 *  Discord application running the guild, which changes across this
 *  migration, so they have to be recreated via a real `/setup` regardless. */
final case class GuildExport(
  guildId: String,
  guildName: String,
  huntedPlayers: List[Players],
  huntedGuilds: List[Guilds],
  alliedPlayers: List[Players],
  alliedGuilds: List[Guilds]
)

/** jsonFormatN case-class derivation, matching the convention already used
 *  for the TibiaData API models (tibiadata.JsonSupport) and
 *  OnlineDurationPersistence — not this codebase's hand-rolled JsObject
 *  style, which is reserved for parsing irregular external envelopes. */
trait HuntedAlliedExportFormat extends DefaultJsonProtocol {
  implicit val playersFormat: RootJsonFormat[Players] = jsonFormat4(Players)
  implicit val guildsFormat: RootJsonFormat[Guilds] = jsonFormat4(Guilds)
  implicit val guildExportFormat: RootJsonFormat[GuildExport] = jsonFormat6(GuildExport)
}
