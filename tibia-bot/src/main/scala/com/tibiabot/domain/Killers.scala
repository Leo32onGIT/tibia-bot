package com.tibiabot.domain

/** Pure interpretation of a Tibia death "killer" entry, used when assembling
 *  the death-notification text. Extracted from TibiaBot's death block so the
 *  fiddly edge cases (summon-vs-player detection, English articles) are
 *  unit-testable; pinned by KillersSpec. */
object Killers {

  /** Death sources that read as a substance/environment rather than a creature.
   *  They take NO article ("died by energy", not "died by an energy"), and are the
   *  killer names that denote an environmental death — `presentation.DeathEffect`
   *  draws its effect-animation keys from this same vocabulary. */
  val substanceSources: Set[String] =
    Set("death", "earth", "energy", "fire", "ice", "holy", "a trap", "agony", "life drain", "drowning")

  /** Indefinite article ("a"/"an") for a name, chosen by its first letter. */
  def article(name: String): String =
    name.take(1).toLowerCase match {
      case "a" | "e" | "i" | "o" | "u" => "an"
      case _                           => "a"
    }

  /** Article for an all-lowercase death source, WITH a trailing space, or ""
   *  for the substance-like sources above. The caller only uses this for
   *  uncapitalised names (capitalised names are bosses and take no article). */
  def sourceArticle(name: String): String =
    if (substanceSources.contains(name)) "" else s"${article(name)} "

  /** A killer entry like "fire elemental of Violent Beams" is a *summon*: the
   *  creature part before " of " is lowercase. "Knight of Flame" is a player
   *  whose name merely contains " of " (the leading part is capitalised), so it
   *  is NOT a summon. Returns Some((creature, summoner)) for a summon, else None. */
  def parseSummon(name: String): Option[(String, String)] = {
    val parts = name.split(" of ", 2)
    if (parts.length > 1 && !parts(0).exists(_.isUpper)) Some((parts(0), parts(1)))
    else None
  }

  /** Join killer entries into one phrase: "a", "a and b", "a, b and c". Empty
   *  for no killers (the caller renders that as a suicide). */
  def joinNatural(parts: Seq[String]): String = parts match {
    case Seq()    => ""
    case Seq(one) => one
    case _        => parts.init.mkString(", ") + " and " + parts.last
  }
}
