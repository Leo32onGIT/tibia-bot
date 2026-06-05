package com.tibiabot.presentation

import com.tibiabot.Config

/** Maps a creature/boss name to its boss-tier emoji prefix (nemesis, archfoe,
 *  bane, cube, ...) when building death notifications.
 *
 *  Extracted from TibiaBot's death block, where the whole category table was
 *  rebuilt as a Map on every killer of every death; here it is built once as a
 *  lazy val and matched in declared order (deterministic, unlike Map iteration).
 *  The matcher takes the categories as a parameter so it is unit-testable
 *  without loading Config; pinned by BossEmojiSpec.
 */
object BossEmoji {

  /** The first category whose name list contains `name.toLowerCase` wins (the
   *  lists are stored lower-cased); returns that category's emoji followed by a
   *  space, or "" if none match. */
  def categoryEmoji(name: String, categories: Seq[(Seq[String], String)]): String =
    categories
      .collectFirst { case (names, emoji) if names.contains(name.toLowerCase) => s"$emoji " }
      .getOrElse("")

  /** Built once from Config — the death path previously rebuilt this per killer. */
  private lazy val categories: Seq[(Seq[String], String)] = Seq(
    Config.nemesisCreatures -> Config.nemesisEmoji,
    Config.archfoeCreatures -> Config.archfoeEmoji,
    Config.baneCreatures -> Config.baneEmoji,
    Config.bossSummons -> Config.summonEmoji,
    Config.cubeBosses -> Config.cubeEmoji,
    Config.mkBosses -> Config.mkEmoji,
    Config.svarGreenBosses -> Config.svarGreenEmoji,
    Config.svarScrapperBosses -> Config.svarScrapperEmoji,
    Config.svarWarlordBosses -> Config.svarWarlordEmoji,
    Config.zelosBosses -> Config.zelosEmoji,
    Config.libBosses -> Config.libEmoji,
    Config.hodBosses -> Config.hodEmoji,
    Config.feruBosses -> Config.feruEmoji,
    Config.inqBosses -> Config.inqEmoji,
    Config.kilmareshBosses -> Config.kilmareshEmoji,
    Config.primalCreatures -> Config.primalEmoji,
    Config.hazardCreatures -> Config.hazardEmoji
  )

  /** The configured boss-tier emoji prefix for `name`, or "" if it is none. */
  def of(name: String): String = categoryEmoji(name, categories)
}
