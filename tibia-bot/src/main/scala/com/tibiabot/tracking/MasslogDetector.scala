package com.tibiabot.tracking

/** Pure masslog threshold: how many recently-logged-in enemies (`zapCount`) it
 *  takes, relative to the total enemies online, to flag a "masslog". Used by
 *  `TibiaBot.onlineList`, which currently always calls it with `sensitivity = 0`;
 *  the rest of the sensitivity table is kept ready for when that becomes
 *  configurable.
 */
object MasslogDetector {

  val DefaultFloor = 3

  /** Multiplier applied to the base percentage; lower = more sensitive.
   *  Only 0-4 are handled; other values throw MatchError. */
  def sensitivityModifier(sensitivity: Int): Double = sensitivity match {
    case 0 => 1.20 // stricter
    case 1 => 1.10
    case 2 => 1.00 // default
    case 3 => 0.90
    case 4 => 0.80 // very sensitive
  }

  /** Fraction of online enemies that must have just logged in, by enemy count. */
  def basePercentage(enemyCount: Int): Double = enemyCount match {
    case n if n <= 5  => 0.60
    case n if n <= 10 => 0.55
    case n if n <= 20 => 0.40
    case _            => 0.32
  }

  /** Minimum number of just-logged-in enemies to trigger a masslog. */
  def requiredZapCount(enemyCount: Int, sensitivity: Int = 0, floor: Int = DefaultFloor): Int =
    math.max(floor, math.ceil(enemyCount * basePercentage(enemyCount) * sensitivityModifier(sensitivity)).toInt)

  def isMasslog(zapCount: Int, enemyCount: Int, sensitivity: Int = 0, floor: Int = DefaultFloor): Boolean =
    zapCount >= requiredZapCount(enemyCount, sensitivity, floor)
}
