package com.tibiabot.domain

import java.time.ZonedDateTime

/** One entry on a guild's hunted or allied player list.
 *
 *  `tradedWhenAdded` is a snapshot, taken from the character's sheet at the
 *  moment somebody added them, and it cannot be recomputed later: the flag it
 *  came from decays once the character is renamed or six months pass. It is what
 *  tells "this player has since been traded, so the entry is probably dead" apart
 *  from "this player was already traded when they were added, which whoever added
 *  them evidently knew" — and the second must never be proposed for removal.
 *
 *  `flaggedReason` is empty until the entry has been flagged, then names why
 *  ("traded" or "world"). It is stored rather than derived so the admin-channel
 *  notice is said once: detecting is unconditional, announcing is not.
 */
case class Players(name: String, reason: String, reasonText: String, addedBy: String,
                   tradedWhenAdded: Boolean = false, flaggedReason: String = "",
                   flaggedAt: String = "")
case class PlayerCache(name: String, formerNames: List[String], guild: String, updatedTime: ZonedDateTime)

/** A world transfer already posted to a discord's activity channel. `formerWorlds`
 *  is the character's former-worlds list as it read when posted, so a *later*
 *  transfer — which changes that list — is told apart from the one just posted.
 *  `name` is stored lowercased: nothing is displayed from this record, it is only
 *  ever matched against, and the display name comes from the live character sheet. */
case class WorldTransfer(name: String, formerWorlds: List[String], detectedAt: ZonedDateTime)
