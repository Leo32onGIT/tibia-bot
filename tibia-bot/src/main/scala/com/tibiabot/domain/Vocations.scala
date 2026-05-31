package com.tibiabot.domain

/** Canonical display order of Tibia vocations — druids first, "none" (unknown /
 *  no vocation) last. Players are grouped and sorted by this order across the
 *  online list, the `/allies`|`/hunted` list and the world list. Single source of
 *  truth: a new vocation (as `monk` once was) is added here in one place instead
 *  of in every grouping site. */
object Vocations {
  val displayOrder: List[String] =
    List("druid", "knight", "paladin", "sorcerer", "monk", "none")
}
