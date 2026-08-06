package com.tibiabot.domain

import java.time.ZonedDateTime

case class Players(name: String, reason: String, reasonText: String, addedBy: String)
case class PlayerCache(name: String, formerNames: List[String], guild: String, updatedTime: ZonedDateTime)

/** A world transfer already posted to a discord's activity channel. `formerWorlds`
 *  is the character's former-worlds list as it read when posted, so a *later*
 *  transfer — which changes that list — is told apart from the one just posted.
 *  `name` is stored lowercased: nothing is displayed from this record, it is only
 *  ever matched against, and the display name comes from the live character sheet. */
case class WorldTransfer(name: String, formerWorlds: List[String], detectedAt: ZonedDateTime)
