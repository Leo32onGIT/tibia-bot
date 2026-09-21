package com.tibiabot.domain

import java.time.ZonedDateTime

/** One tracked cooldown: whose it is, which collectible, when it was collected
 *  and the tag naming the character it was collected on (`""` for the user's
 *  own untagged one). */
case class CooldownStamp(user: String, kind: CooldownKind, when: ZonedDateTime, tag: String)

/** One boosted notification subscription.
 *
 *  `botId` is which bot identity delivers this user's DM — the one that last
 *  handled a `/boosted` command for them. Several bots can share the
 *  boosted_notifications table, and only one of them is in a guild with any
 *  given subscriber, so an unrouted DM sweep has every bot trying every user.
 *  `""` means unclaimed (a row predating the column, or one whose owner has
 *  never managed a successful delivery); the first bot to reach that user
 *  claims them. Display-only stamps built for an embed leave both at default. */
case class BoostedStamp(user: String, boostedType: String, boostedName: String, botId: String = "", dmFailures: Int = 0)
