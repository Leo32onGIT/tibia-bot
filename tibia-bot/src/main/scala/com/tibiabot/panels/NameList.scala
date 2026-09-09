package com.tibiabot.panels

/** Turning a pasted block of text into the list of names it meant.
 *
 *  People paste these out of a spreadsheet column, a Discord message somebody
 *  wrote by hand, or a guild page — so what arrives is newline-separated most of
 *  the time, comma-separated some of the time, and decorated with bullets or
 *  numbering often enough to be worth handling. None of that is ambiguous with a
 *  Tibia character name, which is letters, spaces, apostrophes and hyphens.
 *
 *  Deliberately forgiving about layout and strict about nothing else: a name it
 *  cannot make sense of is kept and passed on to be looked up, because the API
 *  is the thing that actually knows what exists, and silently dropping a name
 *  somebody pasted is worse than reporting it back as not found.
 */
object NameList {

  /** Bullets and numbering at the start of a line: "- ", "* ", "• ", "1. ",
   *  "12) ". Not applied mid-name, so a hyphenated name survives. */
  private val Decoration = """^\s*(?:[-*•‣▪]|\d{1,3}[.)])\s+""".r

  /** Wrapping some clients add: quotes, backticks, and Discord's own bold.
   *  Stripped from both ends only. */
  private val Wrapping = """^[\s"'`*_\[\]]+|[\s"'`*_\[\]]+$""".r

  /** Split a pasted block into candidate names.
   *
   *  Newlines first, then commas and semicolons within a line — a line holding
   *  "Bubble, Eternal Oblivion" is two names, while a name itself never contains
   *  either. Tabs count as separators too, which is what a spreadsheet paste of
   *  more than one column produces.
   *
   *  Case is left alone: the lists are matched case-insensitively and the API
   *  echoes back the character's real capitalisation, so lowering here would only
   *  make the reply uglier.
   */
  def parse(pasted: String): List[String] =
    Option(pasted).getOrElse("")
      .split('\n').toList
      .flatMap(_.split(Array(',', ';', '\t')))
      .map(clean)
      .filter(isPlausible)
      .distinctBy(_.toLowerCase)

  private def clean(raw: String): String = {
    val undecorated = Decoration.replaceFirstIn(raw, "")
    Wrapping.replaceAllIn(undecorated, "").trim
  }

  /** Worth sending to the API. Tibia names run 2–29 characters; anything outside
   *  that never existed, so it is dropped here rather than spending a request to
   *  be told so. Everything else is somebody's problem to answer, not ours. */
  private def isPlausible(name: String): Boolean =
    name.length >= 2 && name.length <= 29

  /** Split at the ceiling, so the caller can act on what fits and say plainly
   *  what it left — silently processing the first hundred of a hundred and forty
   *  is the version of this that loses names without telling anybody. */
  def take(names: List[String], max: Int): (List[String], List[String]) =
    names.splitAt(max)
}
