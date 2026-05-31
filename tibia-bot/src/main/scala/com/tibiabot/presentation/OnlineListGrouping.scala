package com.tibiabot.presentation

/** Pure layout logic for the online-list channels (allies / neutrals / enemies).
 *  Extracted from TibiaBot, where the same group-and-order block was repeated
 *  for each list with only the row filter differing. */
object OnlineListGrouping {

  /** Groups online-list rows (each a `guildName -> renderedMessage` pair) by
   *  guild, ordering the guilds by descending member count and placing the
   *  guildless bucket (empty guild name) last.
   *
   *  Guilds with equal member counts keep `groupBy`'s unspecified order, which
   *  reproduces the original behaviour verbatim. */
  def groupByGuild(rows: Iterable[(String, String)]): List[(String, List[String])] =
    rows
      .groupBy(_._1)
      .view.mapValues(_.map(_._2).toList)
      .toList
      .partition(_._1.isEmpty) match {
        case (guildless, withGuilds) =>
          withGuilds.sortBy { case (_, messages) => -messages.length } ++ guildless
      }

  /** Flattens grouped guild buckets into the final markdown line list: each
   *  guild bucket is prefixed with a header linking its name to its guild page
   *  with the member count; the guildless bucket ("") is prefixed with
   *  `guildlessHeader(count)`. The bucket's message lines follow its header. */
  def withHeaders(grouped: List[(String, List[String])], guildlessHeader: Int => String): List[String] =
    grouped.flatMap {
      case ("", messages) => guildlessHeader(messages.length) :: messages
      case (guildName, messages) => s"### [$guildName](${Urls.guildUrl(guildName)}) ${messages.length}" :: messages
    }

  /** Assembles the body of the single combined online-list channel from the
   *  three already-rendered category lists.
   *
   *  Allies and enemies each get a section header, but only when at least one
   *  other category is also present — a single-category list needs no header.
   *  When neutrals are the only category present and they carry no guild
   *  sub-headers (just the "### Others" bucket), that lone header is dropped so
   *  the list reads as a plain roster rather than a one-section list.
   *
   *  @param neutralsList         the raw neutrals roster, consulted only for emptiness
   *  @param flattenedNeutralsList the neutrals roster already rendered with "### Others"/guild headers
   */
  def combinedChannelBody(
    alliesList: List[String],
    enemiesList: List[String],
    neutralsList: List[String],
    flattenedNeutralsList: List[String],
    allyEmoji: String,
    enemyEmoji: String
  ): List[String] = {
    val modifiedAllies =
      if (alliesList.nonEmpty && (neutralsList.nonEmpty || enemiesList.nonEmpty))
        s"### $allyEmoji **Allies** $allyEmoji ${alliesList.size}" :: alliesList
      else alliesList
    val modifiedEnemies =
      if (enemiesList.nonEmpty && (alliesList.nonEmpty || neutralsList.nonEmpty))
        s"### $enemyEmoji **Enemies** $enemyEmoji ${enemiesList.size}" :: enemiesList
      else enemiesList

    val headerToRemove = "### Others"
    val hasOtherHeaders = flattenedNeutralsList.exists(h => h.startsWith("### ") && !h.startsWith(headerToRemove))
    if (modifiedAllies.isEmpty && modifiedEnemies.isEmpty && !hasOtherHeaders)
      flattenedNeutralsList.filterNot(_.startsWith(headerToRemove))
    else
      modifiedAllies ++ modifiedEnemies ++ flattenedNeutralsList
  }
}
