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

  // Strips a trailing "-<count>" suffix; the regex always matches, so for any
  // real channel name the capture group is what's returned.
  private val namePattern = "^(.*?)(?:-[0-9]+)?$".r

  /** Recover a user's custom channel base name by dropping the bot-appended
   *  "-<count>" suffix (e.g. "ɴᴇᴍᴇsɪs-42" -> "ɴᴇᴍᴇsɪs"). Falls back to `default`
   *  only in the degenerate case where the pattern fails to match. Moved
   *  verbatim from TibiaBot.onlineList. */
  def baseName(channelName: String, default: String): String =
    namePattern.findFirstMatchIn(channelName).map(_.group(1)).getOrElse(default)

  /** Build the online-list category name from the live ally/enemy counts:
   *  the world name, then "・🤍<allies>💀<enemies>" with each count omitted when
   *  zero and the "・" separator dropped entirely when both are zero. The
   *  mass-log "⚡" suffix is appended separately by the caller (the rename
   *  guard compares against this icon-free name, matching the original). */
  def categoryName(world: String, alliesCount: Int, enemiesCount: Int): String = {
    val allies = if (alliesCount > 0) s"🤍$alliesCount" else ""
    val enemies = if (enemiesCount > 0) s"💀$enemiesCount" else ""
    val spacer = if (alliesCount > 0 || enemiesCount > 0) "・" else ""
    s"$world$spacer$allies$enemies"
  }

  /** Pack online-list lines into the descriptions of one or more embeds, since a
   *  Discord embed description caps near 4096 characters. Lines accumulate
   *  (newline-joined) into the current embed until:
   *    - adding the line would reach 4060 chars, or reach 3850 with the line a
   *      guild header ("### ["), in which case the line starts a fresh embed; or
   *    - the line is a section header ("### " not followed by "["), which starts
   *      a fresh embed unless the current one is still empty.
   *
   *  Always returns at least one element (the trailing embed), so an empty input
   *  yields one empty description — matching the original, which always emitted a
   *  final embed. Extracted verbatim from TibiaBot.updateMultiFields. */
  def packFields(values: List[String]): List[String] = {
    val fields = scala.collection.mutable.ListBuffer.empty[String]
    var field = ""
    values.foreach { v =>
      val currentField = field + "\n" + v
      if (currentField.length >= 4060 || (currentField.length >= 3850 && v.startsWith("### ["))) {
        fields += field
        field = v
      } else if (v.matches("### [^\\[].*")) {
        if (field == "") field = currentField
        else {
          fields += field
          field = v
        }
      } else {
        field = currentField
      }
    }
    fields += field
    fields.toList
  }
}
