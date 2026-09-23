package com.tibiabot.observer

import com.tibiabot.domain.RaidAnnouncement

/** Rank order for the raids channel: more-important raid types first (a per-type
 *  priority, higher wins), then the most-progressed stage
 *  (raid started > subarea revealed > area revealed), then the soonest start.
 *
 *  `typePriority` is a hook — it defaults to 0 for every type until a raid-type
 *  priority source is wired (the API's `RaidTypeInformation`, or a configured map),
 *  at which point only this function changes. */
object RaidRanking {
  private val stageRank = Map("raidStarted" -> 3, "subareaRevealed" -> 2, "areaRevealed" -> 1)

  def order(raids: List[RaidAnnouncement], typePriority: Int => Int = _ => 0): List[RaidAnnouncement] =
    raids.sortBy { r =>
      (-typePriority(r.raidTypeId),
       -stageRank.getOrElse(r.category, 0),
       r.startDate.map(_.getEpochSecond).getOrElse(Long.MaxValue))
    }
}
