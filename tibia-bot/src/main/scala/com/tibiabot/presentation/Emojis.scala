package com.tibiabot.presentation

/** Pure vocation -> Discord emoji mapping. Matches a vocation to its emoji by
 *  its last word, so promoted names ("Elite Knight", "Exalted Monk") resolve to
 *  the base vocation. Used by both the stream (death/online) and command paths.
 *
 *  Previously a second `vocEmojiWithoutMonk` variant existed because BotApp's
 *  original mapping predated monks and rendered them blank; that legacy variant
 *  has been unified away so monk players render consistently everywhere. */
object Emojis {

  def vocEmoji(vocation: String): String =
    vocation.toLowerCase.split(' ').last match {
      case "knight"   => ":shield:"
      case "druid"    => ":snowflake:"
      case "sorcerer" => ":fire:"
      case "paladin"  => ":bow_and_arrow:"
      case "monk"     => ":fist::skin-tone-3:"
      case "none"     => ":hatching_chick:"
      case _          => ""
    }
}
