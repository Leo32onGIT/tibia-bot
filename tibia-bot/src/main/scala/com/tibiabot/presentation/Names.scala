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

  /** The same, said the way a server knows somebody: the account, then what they
   *  are called here.
   *
   *  Two names because either alone leaves somebody guessing. A nickname is what
   *  people actually call each other and is often nothing like the account name;
   *  the account name is the one that is unique and searchable, and the only one
   *  that means anything to a moderator reading a log. Neither is a mention —
   *  see [[user(name:String)*]].
   *
   *  The account leads because it is the half that is always there. A nickname
   *  is optional and changes at whim, so putting it first would mean the same
   *  person starts with a different word from one line to the next, and a line
   *  with no nickname at all would begin somewhere else entirely.
   *
   *  Falls back to the account name alone when there is no nickname to add, or
   *  when it would only repeat it: **`bob`** (**@bob**) says nothing twice as
   *  loudly. Rows written before nicknames were kept have none, so most of the
   *  history reads exactly as it did.
   */
  /** The same pair of names, for a surface with no markdown in it.
   *
   *  [[user(nickname:String,username:String)*]] writes Discord's own formatting
   *  — bold, a code span, an `@` — which is right in an embed and is literal
   *  punctuation everywhere else. The web dashboard renders its answers as
   *  plain text, so a name built for Discord arrives there wearing asterisks
   *  and backticks, which reads as the bot having malfunctioned.
   *
   *  Same two names in the same order, and the same fallback when a nickname
   *  is absent or only repeats the account.
   */
  def plain(nickname: String, username: String): String = {
    val nick = nickname.trim
    if (nick.isEmpty || nick.equalsIgnoreCase(username.trim)) username.trim
    else s"${username.trim} ($nick)"
  }

  def user(nickname: String, username: String): String = {
    val nick = nickname.replace("`", "").replace("*", "").trim
    if (nick.isEmpty || nick.equalsIgnoreCase(username.trim)) user(username)
    else s"${user(username)} (**@$nick**)"
  }

  /** One name for somebody, where [[user(nickname:String,username:String)*]]
   *  writes two: what they are called here, and the account name only when
   *  there is no nickname to use in its place.
   *
   *  For the surfaces people hunting together read, where the pair is a second
   *  name for somebody they already know by the first — and where the second is
   *  the one nobody says out loud. The account name is still what a moderator
   *  needs, and a moderator has their own surfaces to find it on, so nothing is
   *  lost by leaving it off a card.
   *
   *  Both halves are written the same way, `@name`, rather than the nickname as
   *  an `@` and the fallback as a code span: a list of these is read as a
   *  column, and one row in a different shape reads as a different kind of
   *  thing. Still not a mention — see [[user(name:String)*]].
   */
  def called(nickname: String, username: String): String = {
    def clean(name: String) = name.replace("`", "").replace("*", "").trim
    val nick = clean(nickname)
    val name = if (nick.nonEmpty) nick else clean(username)
    if (name.isEmpty) user("") else s"**@$name**"
  }

  /** The same one name, for a surface with no markdown in it.
   *
   *  [[called(nickname:String,username:String)*]] writes Discord's own bold and
   *  its `@`, which is right in an embed and is literal punctuation everywhere
   *  else — the same reason [[plain]] exists beside
   *  [[user(nickname:String,username:String)*]]. What the web dashboard wants
   *  from here is only the choice of *which* name, made the same way; the page
   *  writes the `@` itself, in its own markup.
   *
   *  Empty when there is neither name, rather than a stand-in: only the caller
   *  knows what its own surface says about somebody it cannot name.
   */
  def calledPlain(nickname: String, username: String): String = {
    val nick = nickname.trim
    if (nick.nonEmpty) nick else username.trim
  }
}
