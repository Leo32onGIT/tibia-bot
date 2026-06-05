package com.tibiabot.presentation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Pure builders for tibia.com community URLs.
 *
 *  Extracted verbatim from the byte-identical `charUrl`/`guildUrl` helpers that
 *  were duplicated in both `BotApp` and `TibiaBot`. Behaviour is unchanged —
 *  pinned by UrlsSpec.
 */
object Urls {

  def charUrl(char: String): String = {
    val encodedString = URLEncoder.encode(char, StandardCharsets.UTF_8.toString)
    s"https://www.tibia.com/community/?name=${encodedString}"
  }

  def guildUrl(guild: String): String = {
    val encodedString = URLEncoder.encode(guild, StandardCharsets.UTF_8.toString)
    s"https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${encodedString}"
  }

  /** Resolve a creature name to its TibiaWiki file/page name.
   *
   *  Extracted verbatim from the name-parsing block shared by
   *  `BotApp.creatureImageUrl`, `BotApp.creatureWikiUrl` and
   *  `TibiaBot.creatureImageUrl`. The `mappings` (Config.creatureUrlMappings)
   *  are passed in so this stays decoupled from config loading and unit-testable.
   */
  def creatureFileName(creature: String, mappings: Map[String, String]): String =
    mappings.getOrElse(creature.toLowerCase, {
      // Capitalise the start of each word, including after punctuation e.g. "Mooh'Tah Warrior", "Two-Headed Turtle"
      val rx1 = """([^\w]\w)""".r
      val parsed1 = rx1.replaceAllIn(creature, m => m.group(1).toUpperCase)

      // Lowercase the articles, prepositions etc., e.g. "The Voice of Ruin"
      val rx2 = """( A| Of| The| In| On| To| And| With| From)(?=( ))""".r
      val parsed2 = rx2.replaceAllIn(parsed1, m => m.group(1).toLowerCase)

      // Replace spaces with underscores and make sure the first letter is capitalised
      parsed2.replaceAll(" ", "_").capitalize
    })

  def creatureImageUrl(creature: String, mappings: Map[String, String]): String =
    s"https://www.tibiawiki.com.br/wiki/Special:Redirect/file/${creatureFileName(creature, mappings)}.gif"

  def creatureWikiUrl(creature: String, mappings: Map[String, String]): String =
    s"https://www.tibiawiki.com.br/wiki/${creatureFileName(creature, mappings)}"
}
