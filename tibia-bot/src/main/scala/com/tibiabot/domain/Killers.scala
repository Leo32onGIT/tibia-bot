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

  /** The tail an over-long killer list ends with. Rendered rather than guessed:
   *  its width is exactly what [[joinWithin]] has to reserve, and it grows with
   *  the digits of the count. */
  private def andMore(hidden: Int): String = s" and $hidden more"

  /** [[joinNatural]], but never wider than `limit` characters.
   *
   *  A death in a big war can name more killers than Discord's entire 4096-char
   *  description allows, and the list is rendered on a single line ("by a, b and
   *  c."). A cut made at the last newline that fits can therefore only drop that
   *  line whole — the killers, and the exiva list beneath them, disappeared and
   *  left a lone "out of space" marker in their place. Fitting the list here is
   *  what keeps the rest of the notification.
   *
   *  Entries are kept whole: half an entry is half a markdown link, which renders
   *  as raw text rather than as a name. The ones that do not fit become a count,
   *  and because that count needs room of its own, the last entry that would
   *  otherwise fit may have to be given up to make space for it — which is why
   *  this searches down from the full list rather than stopping at the first
   *  entry to overflow. */
  def joinWithin(parts: Seq[String], limit: Int): String = {
    val full = joinNatural(parts)
    if (parts.isEmpty || full.length <= limit) full
    else {
      val total = parts.size
      // widths(k) = characters in the first k entries, separators aside
      val widths = parts.iterator.map(_.length).scanLeft(0)(_ + _).toVector
      // k entries joined by ", ", followed by the tail naming the rest
      def width(kept: Int): Int = widths(kept) + 2 * (kept - 1) + andMore(total - kept).length
      (total - 1).to(1, -1).find(width(_) <= limit) match {
        case Some(kept) => parts.take(kept).mkString(", ") + andMore(total - kept)
        // Not even the first entry fits beside its tail. Unreachable at Discord's
        // limit with any real killer name, but the count still says what happened.
        case None       => if (total == 1) "1 killer" else s"$total killers"
      }
    }
  }

  /** How many killers an exiva list names. Everyone in the kill can be exiva'd,
   *  but a reader can only chase one at a time, and a war death lists dozens. */
  val ExivaTargets: Int = 5

  /** The killers an exiva list should name, hardest first.
   *
   *  Level is what ranks them: the high levels are the ones worth locating, and
   *  they are also the ones still standing after the fight. A killer whose level
   *  never resolved sorts last — unknown is not low, but it is not worth one of
   *  the few slots ahead of a level that is known. Ties keep the order the death
   *  reported them in.
   *
   *  A name can reach here twice, since a player and that player's summon are
   *  separate killer entries and both resolve to the same person to exiva. That
   *  is one name on the list, carrying the highest level seen for it. */
  def exivaTargets(killers: Seq[(String, Option[Int])], limit: Int = ExivaTargets): Seq[String] =
    killers.zipWithIndex
      .groupBy { case ((name, _), _) => name }
      .values
      .map(entries => (entries.head._1._1, entries.flatMap(_._1._2).maxOption, entries.map(_._2).min))
      .toSeq
      .sortBy { case (_, level, order) => (-level.getOrElse(0), order) }
      .take(limit)
      .map(_._1)
}
