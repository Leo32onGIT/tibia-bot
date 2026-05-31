package com.tibiabot.presentation

import com.tibiabot.domain.Vocations

/** Formats a per-world map of already-rendered player lines into a flat list
 *  with a world header before each world's players. Worlds are ordered
 *  alphabetically, except the synthetic "Character does not exist" bucket which
 *  is pushed to the end. Pure; pinned by WorldListSpec. */
object WorldList {

  /** Group player entries — each `(level, world, renderedLine)`, keyed by
   *  vocation — into a per-world list of lines. Within a world, players are
   *  ordered by vocation (druid, knight, paladin, sorcerer, monk, none) then by
   *  descending level; ties keep input order. Pure; the result feeds [[format]].
   *
   *  Extracted from listAlliesAndHuntedPlayers, which repeated the per-vocation
   *  group-and-sort six times then folded them together. */
  def byWorld(vocationEntries: Map[String, Seq[(Int, String, String)]]): Map[String, List[String]] = {
    // Fold in reverse display order so each vocation prepends ahead of the
    // previous, leaving druids first and "none" last within each world.
    val foldOrder = Vocations.displayOrder.reverse
    foldOrder.foldLeft(Map.empty[String, List[String]]) { (acc, voc) =>
      val perWorld = vocationEntries.getOrElse(voc, Seq.empty)
        .groupBy(_._2)
        .map { case (world, entries) => world -> entries.toList.sortBy(-_._1).map(_._3) }
      perWorld.foldLeft(acc) { case (map, (world, lines)) =>
        map + (world -> (lines ++ map.getOrElse(world, List())))
      }
    }
  }

  def format(worlds: Map[String, List[String]]): List[String] = {
    val sortedWorlds = worlds.toList.sortBy(_._1)
      .sortWith((a, b) => {
        if (a._1 == "Character does not exist") false
        else if (b._1 == "Character does not exist") true
        else a._1 < b._1
      })
    sortedWorlds.flatMap {
      case (world, players) =>
        s":globe_with_meridians: **$world** :globe_with_meridians:" :: players
    }
  }
}
