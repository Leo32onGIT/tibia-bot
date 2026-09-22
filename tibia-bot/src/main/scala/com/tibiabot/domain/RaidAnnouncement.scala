package com.tibiabot.domain

import java.time.Instant

/** One raid the Observer feed is reporting, at one of its stages (`category`:
 *  `areaRevealed` → `subareaRevealed` → `raidStarted`). `raidId` is stable across
 *  the stages of the same raid, so it is what delivery dedupes on. */
final case class RaidAnnouncement(
  raidId: String,
  world: String,
  area: String,
  subarea: Option[String],
  category: String,
  startDate: Option[Instant],
  raidTypeId: Int
)
