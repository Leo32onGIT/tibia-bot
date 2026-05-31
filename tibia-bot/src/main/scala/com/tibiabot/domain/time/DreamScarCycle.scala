package com.tibiabot.domain.time

/** The Dream Courts (Dream Scar) boss-of-the-day rotation.
 *  https://tibia.fandom.com/wiki/Template:Dream_Scar_Boss/Offsets */
object DreamScarCycle {

  val bossCycle: Vector[String] = Vector(
    "Plagueroot",
    "Malofur Mangrinder",
    "Maxxenius",
    "Alptramun",
    "Izcandar the Banished"
  )

  val indexOfBoss: Map[String, Int] = bossCycle.zipWithIndex.toMap

  /** True if `name` is one of the Dream Courts boss-of-the-day bosses
   *  (case-insensitive). Single source of truth for "is this a Dream Court boss". */
  def isDreamCourtBoss(name: String): Boolean =
    bossCycle.exists(_.equalsIgnoreCase(name))

  /** Shift each world's boss to the next in the cycle; unknown bosses are kept
   *  unchanged. Extracted verbatim from `BotApp.shiftAllBossesUp`. */
  def shiftAllBossesUp(current: Map[String, String]): Map[String, String] =
    current.map { case (world, boss) =>
      val nextBoss = indexOfBoss.get(boss) match {
        case Some(idx) => bossCycle((idx + 1) % bossCycle.length)
        case None      => boss // fallback: keep unchanged
      }
      world -> nextBoss
    }
}
