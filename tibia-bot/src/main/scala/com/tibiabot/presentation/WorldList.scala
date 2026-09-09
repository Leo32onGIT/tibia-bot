package com.tibiabot.presentation

import com.tibiabot.domain.Vocations

/** Formats a per-world map of already-rendered player lines into a flat list
 *  with a world header before each world's players. Worlds are ordered
 *  alphabetically, except the synthetic buckets in [[sortsLast]], which are
 *  pushed to the end. Pure; pinned by WorldListSpec. */
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

  /** Flatten to lines, each world introduced by a markdown heading.
   *
   *  `## ` rather than bolded text with globes either side: Discord renders it
   *  as an actual heading, which is easier to find when scrolling a long list,
   *  and it is what the embed packer keys on to start a fresh embed at a world
   *  boundary — see OnlineListEmbeds.packMessages, which the online list has
   *  used for the same reason. */
  def format(worlds: Map[String, List[String]]): List[String] = {
    val sortedWorlds = worlds.toList.sortWith { (a, b) =>
      (sortsLast.contains(a._1), sortsLast.contains(b._1)) match {
        case (false, true) => true
        case (true, false) => false
        case _             => a._1 < b._1
      }
    }
    sortedWorlds.flatMap { case (world, players) => s"## $world" :: players }
  }
}
