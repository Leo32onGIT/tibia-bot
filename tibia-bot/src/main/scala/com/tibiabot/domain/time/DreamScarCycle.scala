package com.tibiabot.domain.time

import java.time.DayOfWeek

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
    shiftAllBossesUp(current, 1)

  /** As above, but `steps` days forward at once — used to bring a wiki render
   *  that was cached some days ago up to the current game day. */
  def shiftAllBossesUp(current: Map[String, String], steps: Int): Map[String, String] =
    current.map { case (world, boss) =>
      val nextBoss = indexOfBoss.get(boss) match {
        case Some(idx) => bossCycle(Math.floorMod(idx + steps, bossCycle.length))
        case None      => boss
      }
      world -> nextBoss
    }

  /** How many days behind `expected` a render dated `rendered` is, 0-6. The
   *  rotation advances exactly one step per day, so this doubles as the number
   *  of shifts needed to bring that render up to date.
   *
   *  Weekdays are all the page gives us, so this can't tell "yesterday" from
   *  "eight days ago" — fine in practice, since what it exists to catch is a
   *  cache that hasn't rolled over yet. */
  def daysBehind(rendered: DayOfWeek, expected: DayOfWeek): Int =
    Math.floorMod(expected.getValue - rendered.getValue, 7)
}
