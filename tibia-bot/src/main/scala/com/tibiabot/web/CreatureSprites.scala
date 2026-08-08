package com.tibiabot.web

/** Turning a creature name into a file name that is safe to put on disk and in
 *  a URL.
 *
 *  This exists because the input is not trusted. A spawn's creature is part of
 *  a guild's own catalogue, editable by that guild's admins, and it ends up
 *  naming a file we write, read and serve. `StatusRoute`'s images are safe by
 *  being a fixed allow-list of three names; that guarantee does not carry over
 *  to a set that grows with whatever anybody types, so the checking has to be
 *  done here instead.
 *
 *  The rule is an allow-list of characters rather than a search for bad ones.
 *  Every real creature name is covered by it — `Orc Warlord`, `Sign (Library)`,
 *  `Mooh'Tah Warrior`, `Two-Headed Turtle` — and anything outside it is refused
 *  rather than stripped, because silently rewriting an attacker's input into
 *  something that passes is how mistakes get made.
 *
 *  Note the dot is *not* allowed. The extension is appended by us, so a name
 *  never needs one, and forbidding it outright means `..` cannot be expressed
 *  at all — no traversal check to get subtly wrong.
 */
object CreatureSprites {

  /** Long enough for the longest real creature name several times over, short
   *  enough that nothing can be used to exhaust a filesystem's path limit. */
  private val MaxLength = 64

  private def allowed(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '_' || c == '-' || c == '(' || c == ')' || c == '\''

  /** The sprite file name for a creature, or None if it isn't one we are willing
   *  to touch the filesystem with.
   *
   *  A creature is written the way a person writes it — "Minotaur Cult
   *  Follower" — and the wiki names its file the way a wiki does,
   *  `Minotaur_Cult_Follower`. So spaces become underscores here, which is the
   *  wiki's own convention rather than a liberty taken with the input: it is a
   *  total mapping onto a character the allow-list already permits, and a space
   *  can no more escape a directory than an underscore can.
   *
   *  Runs of spaces collapse, because "Orc  Warlord" names the same creature as
   *  "Orc Warlord" and `Orc__Warlord.gif` is not a file anybody has. Only the
   *  plain space folds: a tab or a newline in a creature name is junk, and
   *  folding junk into something valid is the very thing the allow-list is for.
   *
   *  Everything outside the allow-list is still refused rather than stripped.
   *  Refusing costs a placeholder; stripping is how an input gets quietly
   *  rewritten into something that passes.
   */
  def safeFileName(creature: String): Option[String] = {
    val name = creature.trim.replaceAll(" +", "_")
    if (name.isEmpty || name.length > MaxLength) None
    else if (!name.forall(allowed)) None
    else Some(s"$name.gif")
  }

  /** Where a sprite is served from, or None when the name is not safe — in
   *  which case the caller shows the placeholder instead. */
  def urlFor(wikiName: String): Option[String] =
    safeFileName(wikiName).map(name => s"/dashboard/sprites/$name")

  /** The creature behind a served path segment, or None if the segment isn't
   *  one we produced.
   *
   *  The inverse of [[urlFor]], and deliberately routed back through
   *  [[safeFileName]] rather than trusting the shape: the segment arrives
   *  straight off the wire, so "it ends in .gif" says nothing about the rest of
   *  it. Stripping the extension first is what lets the dot stay forbidden
   *  everywhere else. */
  def wikiNameOf(segment: String): Option[String] =
    Some(segment)
      .filter(_.endsWith(".gif"))
      .map(_.dropRight(4))
      .filter(name => safeFileName(name).isDefined)

  /** The stand-in for a spawn whose creature is unset or unusable: the bot's
   *  own avatar, already vendored and already served from this domain, so it
   *  cannot be geoblocked and cannot 404. Drained of colour by the page. */
  val placeholderUrl: String = "/dashboard/images/avatar.png"
}
