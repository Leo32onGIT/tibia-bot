package com.tibiabot.presentation

/** Pure vocation -> Discord emoji mapping.
 *
 *  IMPORTANT: two variants exist because the original code diverged, and this
 *  extraction preserves BOTH behaviours exactly rather than silently unifying
 *  them (pinned by EmojisSpec):
 *
 *    - `vocEmoji`           — TibiaBot's version; includes the `monk` vocation.
 *    - `vocEmojiWithoutMonk`— BotApp's version; predates monks and omits it,
 *                             so a monk maps to "" there.
 *
 *  Reconciling the two (i.e. adding monk to BotApp's path) would be a behaviour
 *  change and is intentionally left as a separate, explicit decision.
 */
object Emojis {

  /** Includes monk. Matches the original `TibiaBot.vocEmoji`. */
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

  /** Omits monk. Matches the original `BotApp.vocEmoji`. */
  def vocEmojiWithoutMonk(vocation: String): String =
    vocation.toLowerCase.split(' ').last match {
      case "knight"   => ":shield:"
      case "druid"    => ":snowflake:"
      case "sorcerer" => ":fire:"
      case "paladin"  => ":bow_and_arrow:"
      case "none"     => ":hatching_chick:"
      case _          => ""
    }
}
