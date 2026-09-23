package com.tibiabot.presentation

import com.tibiabot.domain.Vocations

/** Groups already-rendered player lines by world, and orders the worlds:
 *  alphabetically, except the synthetic buckets in [[sortsLast]], which are
 *  pushed to the end. Pure; pinned by WorldListSpec. The lists draw their own
 *  world headings — see panels.ListPanel. */
object WorldList {

  /** Buckets that are not worlds and always follow every real one.
   *
   *  Both say "we could not place this player on a world", which is the least
   *  interesting thing a list can tell you — so they belong under the worlds
   *  rather than sorted in among them by their first letter. */
  val sortsLast: Set[String] = Set("Character does not exist", "Not checked yet")

  /** Group player entries — each `(level, world, renderedLine)`, keyed by
   *  vocation — into a per-world list of lines. Within a world, players are
   *  ordered by vocation (druid, knight, paladin, sorcerer, monk, none) then by
   *  descending level; ties keep input order. Pure; the result feeds [[sorted]].
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

  /** The worlds in display order — alphabetical, the buckets in [[sortsLast]]
   *  after every real world — each with its lines, for a caller drawing its own
   *  world headings. */
  def sorted(worlds: Map[String, List[String]]): List[(String, List[String])] =
    worlds.toList.sortWith { (a, b) =>
      (sortsLast.contains(a._1), sortsLast.contains(b._1)) match {
        case (false, true) => true
        case (true, false) => false
        case _             => a._1 < b._1
      }
    }
}
