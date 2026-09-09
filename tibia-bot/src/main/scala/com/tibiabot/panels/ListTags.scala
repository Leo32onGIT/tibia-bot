package com.tibiabot.panels

/** The tags a server can put on a hunted player.
 *
 *  A fixed set rather than free text, because Discord has no emoji picker
 *  component: a modal can hold a select menu whose options each carry an emoji,
 *  which is as close as the platform gets, and it means a tag cannot arrive as
 *  three emoji, a custom one the bot cannot render, or nothing at all.
 *
 *  Hunted only. These describe an opponent, and most of them say nothing useful
 *  about somebody you are allied with.
 *
 *  '''Stored by key, drawn by emoji.''' The key is what goes in the database, so
 *  changing one orphans every entry already tagged with it — add and retire keys
 *  rather than renaming them. Labels and emoji can change freely.
 */
object ListTags {

  final case class Tag(key: String, label: String, emoji: String)

  /** In the order the picker shows them: how a player fights, then what they are
   *  worth knowing about, then the two that are really states rather than roles. */
  val all: List[Tag] = List(
    Tag("bot", "Bot", "🤖"),
    Tag("toxic", "Toxic", "🤬"),
    Tag("carbomber", "Carbomber", "🪤"),
    Tag("bomb", "Bomb", "💣"),
    Tag("thief", "Thief", "🥷"),
    Tag("rat", "Rat", "🐀"),
    Tag("killer", "Killer", "⚔️"),
    Tag("tank", "Tank", "🛡️"),
    Tag("leader", "Leader", "👑"),
    Tag("rich", "Rich", "💰"),
    Tag("priority", "Priority", "🎯"),
    Tag("inactive", "Inactive", "🧊"),
    Tag("unknown", "Unknown", "❓")
  )

  /** The key used to take a tag off again, offered in the picker alongside the
   *  real ones — otherwise a tag could be changed but never removed. */
  val NoneKey: String = "none"

  private val byKey: Map[String, Tag] = all.map(tag => tag.key -> tag).toMap

  /** The tag for a stored key, or None for an untagged entry — and for a key
   *  that is no longer offered, which reads the same way rather than throwing. */
  def find(key: String): Option[Tag] =
    if (key == null || key.isEmpty || key == NoneKey) None else byKey.get(key.toLowerCase)

  /** What a row shows for this entry: the emoji, or nothing. */
  def mark(key: String): String = find(key).map(tag => s" ${tag.emoji}").getOrElse("")

  /** True for anything the picker could have produced, including the clearing
   *  choice and the empty string an untagged entry carries. */
  def valid(key: String): Boolean =
    key == null || key.isEmpty || key == NoneKey || byKey.contains(key.toLowerCase)
}
