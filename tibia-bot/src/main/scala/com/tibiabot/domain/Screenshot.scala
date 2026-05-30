package com.tibiabot.domain

import java.time.ZonedDateTime

case class DeathScreenshot(guildId: String, world: String, characterName: String, deathTime: Long, screenshotUrl: String, addedBy: String, addedName: String, addedAt: ZonedDateTime, messageId: String)
case class PendingScreenshot(charName: String, deathTime: Long, messageId: String, guildId: String, world: String, userId: String, channelId: String)
