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
    Set("death", "earth", "energy", "fire", "ice", "holy", "a trap", "agony", "life drain", "drowning", "invalid")

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

  /** The (creature, summoner) behind a summon kill, or None for an ordinary one.
   *
   *  Both upstreams report a summon the same way, and it is '''not''' the way
   *  [[parseSummon]] expects: the summoner goes in the killer's `name` and the
   *  creature in a field beside it — `summon` on TibiaData, `remark` on the
   *  fansite API. Verified against the same live "fire elemental of <player>"
   *  death on both.
   *
   *  That field went unread for a long time, and because a player name always
   *  begins with a capital, [[parseSummon]] could never match one either — so
   *  every summon kill quietly rendered as a plain player kill. Preferring the
   *  field here is what actually turns summon rendering on.
   *
   *  [[parseSummon]] is still consulted as a fallback: it costs nothing, and it
   *  keeps working for any source that does inline the whole
   *  "<creature> of <player>" phrase into the name. */
  def summonBehind(name: String, summon: String): Option[(String, String)] =
    Option(summon).map(_.trim).filter(_.nonEmpty).map(creature => (creature, name))
      .orElse(parseSummon(name))

  /** The character names a death's killer list will want a level for, given the
   *  victim's name and each killer as (name, isPlayer).
   *
   *  Mirrors exactly what the death embed renders a "[level]" beside: player
   *  killers only (creatures and environmental sources have no level), never
   *  the victim themselves (deaths list "self" entries the embed skips), and a
   *  summon resolved to its summoner — "fire elemental of X" asks about X, not
   *  about the elemental. */
  def levelLookupNames(victim: String, killers: Seq[(String, Boolean)]): Seq[String] =
    killers.collect {
      case (name, true) if name != victim => parseSummon(name).map(_._2).getOrElse(name)
    }

  /** Join killer entries into one phrase: "a", "a and b", "a, b and c". Empty
   *  for no killers (the caller renders that as a suicide). */
  def joinNatural(parts: Seq[String]): String = parts match {
    case Seq()    => ""
    case Seq(one) => one
    case _        => parts.init.mkString(", ") + " and " + parts.last
  }
}
