package com.tibiabot.presentation

/** Name formatting shared by the command and boosted paths. */
object Names {

  /** Upper-case the first letter of each space-separated word, leaving the rest
   *  of each word untouched (so "violent beams" -> "Violent Beams"). This is the
   *  exact `split(" ").map(_.capitalize).mkString(" ")` idiom that was repeated
   *  across BotApp and BoostedService. */
  def capitalizeWords(name: String): String =
    name.split(" ").map(_.capitalize).mkString(" ")

  /** How a Discord user is named in anything a person reads.
   *
   *  Their name, not a `<@id>` mention. A mention is a live link: it lights the
   *  reader up with a notification, follows them into their mentions list, and
   *  renders as a raw `<@123…>` anywhere the client cannot resolve it — which is
   *  every log line, every audit trail, and every embed quoted somewhere else.
   *  None of these messages are trying to summon anybody; they are describing
   *  who did what, and a name does that without poking them.
   *
   *  Backticks are dropped rather than escaped: a code span cannot contain one,
   *  and Discord's own usernames cannot either — but a display name reaching
   *  here should not be able to break the formatting around it.
   */
  def user(name: String): String = {
    val cleaned = name.replace("`", "").trim
    if (cleaned.isEmpty) "**`someone`**" else s"**`$cleaned`**"
  }
}
