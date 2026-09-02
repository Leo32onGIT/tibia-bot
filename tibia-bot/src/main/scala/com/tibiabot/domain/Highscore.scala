package com.tibiabot.domain

import java.time.Instant

/** A character's stored standing in one highscore list, as the `highscore_value`
 *  table holds it.
 *
 *  `name` is the lowercased key and `displayName` the casing tibia.com shows —
 *  the same split the world-transfer cache uses, and for the same reason: the
 *  key must not hold "Bubble" and "bubble" as two characters, while the Levels
 *  post has to render the name the player actually has.
 *
 *  `lastSeen` is the snapshot this character was last present in, not the time
 *  the row was written. It is stamped on every snapshot whether or not the
 *  score moved, because what it answers is "were they in the list last time?" —
 *  which is what separates a real advance from a character re-entering the top
 *  thousand after a long absence. Writing it only on change would eventually
 *  make a long-stable score look stale and silently swallow the advance that
 *  finally came. */
final case class HighscoreRecord(
    name: String,
    displayName: String,
    vocation: String,
    level: Int,
    score: Long,
    lastSeen: Instant
)

/** A detected advance, kept for audit and for the dashboard's recent-events
 *  strip. Not what stops a repost — the stored score does that — so this table
 *  can be pruned freely without anything being announced twice.
 *
 *  `category` is the endpoint's own slug ("swordfighting", "magiclevel", ...),
 *  which is the stable identifier for the list across restarts and schema
 *  changes alike. */
final case class HighscoreEvent(
    world: String,
    category: String,
    name: String,
    displayName: String,
    vocation: String,
    level: Int,
    previousScore: Long,
    score: Long,
    observed: Instant
)
