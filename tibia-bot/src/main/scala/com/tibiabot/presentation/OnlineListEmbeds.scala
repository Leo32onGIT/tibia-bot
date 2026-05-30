package com.tibiabot.presentation

/** Pure rendering helpers for the online list. (The full embed assembly in
 *  TibiaBot.onlineList depends on Config emoji constants + JDA channel state and
 *  stays there for now; this holds the Config-free, unit-testable bits.) */
object OnlineListEmbeds {

  /** Format an online duration (in seconds) as a backticked "Xhr Ymin" / "Xmin"
   *  string. Moved verbatim from TibiaBot.onlineList. */
  def durationString(durationInSec: Long): String = {
    val durationInMin = durationInSec / 60
    val durationStr =
      if (durationInMin >= 60) {
        val hours = durationInMin / 60
        val mins = durationInMin % 60
        s"${hours}hr ${mins}min"
      } else {
        s"${durationInMin}min"
      }
    s"`$durationStr`"
  }
}
