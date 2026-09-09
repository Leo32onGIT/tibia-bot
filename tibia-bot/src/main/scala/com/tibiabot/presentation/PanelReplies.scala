package com.tibiabot.presentation


import com.tibiabot.domain.BulkListOutcome
import com.tibiabot.panels.PanelIds.Panel
import com.tibiabot.panels.ListTags
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** What a pasted list of names comes back as.
 *
 *  Four groups, because they mean four different things and one of them is not
 *  the caller's fault. "Couldn't check" is the reason this is not just a count:
 *  a name Tibia's API never answered for has neither been added nor ruled out,
 *  and putting it under "not found" would report real characters as nonexistent
 *  in bulk - see [[com.tibiabot.domain.PlayerLookup]].
 *
 *  Names are truncated per group rather than listed in full. An embed field caps
 *  at 1024 characters and a hundred names will not fit; the count is the part
 *  that matters, and the first several name it enough to recognise which batch
 *  this was.
 */
object PanelReplies {

  /** Both emoji are required rather than defaulted. This object stays
   *  Config-free so it can be tested, so they are passed in — and a default
   *  meant a caller could quietly drop one and get a literal tick instead of the
   *  server's, which is exactly what happened twice before they were made
   *  mandatory. Let the compiler ask.
   */
  /** How many names are shown per group before the rest become a count. */
  private val ShownPerGroup = 15

  def bulkEmbed(panel: Panel, kind: String, adding: Boolean, outcome: BulkListOutcome,
                yesEmoji: String, noEmoji: String, tagKey: String = ""): MessageEmbed = {
    val noun = if (kind == "guild") "guild" else "player"
    val listName = panel.noun
    val builder = new EmbedBuilder().setColor(Embeds.BrandColor)

    val headline =
      if (adding) headlineFor(outcome.added.size, noun, s"added to the $listName")
      else headlineFor(outcome.added.size, noun, s"removed from the $listName")
    builder.setDescription(headline)

    // Named because the tag reaches players already on the list as well as the
    // ones just added — re-pasting with a tag chosen is how an entry gets
    // retagged, and saying nothing would leave that looking like it had not.
    ListTags.find(tagKey).foreach(tag =>
      builder.appendDescription(s"\nTagged ${tag.emoji} **${tag.label}**."))

    group(builder, s"$yesEmoji Added", outcome.added, adding && outcome.added.nonEmpty)
    group(builder, s"$yesEmoji Removed", outcome.added, !adding && outcome.added.nonEmpty)
    group(builder, ":arrow_right_hook: Already on the list", outcome.already, outcome.already.nonEmpty)
    val missingLabel = if (adding) ":grey_question: No such character" else ":grey_question: Not on the list"
    group(builder, missingLabel, outcome.notFound, outcome.notFound.nonEmpty)
    group(builder, ":warning: Couldn't check", outcome.unavailable, outcome.unavailable.nonEmpty)
    group(builder, ":no_entry: Over the limit", outcome.skipped, outcome.skipped.nonEmpty)

    if (outcome.unavailable.nonEmpty)
      builder.appendDescription(
        s"\n\n$noEmoji Tibia's API didn't answer for " +
          s"${outcome.unavailable.size} of these, so they were left alone. Paste them again to retry.")
    if (outcome.skipped.nonEmpty)
      builder.appendDescription(
        s"\n\nOnly the first ${outcome.added.size + outcome.already.size + outcome.notFound.size + outcome.unavailable.size}" +
          s" were processed - paste the rest separately.")
    builder.build()
  }

  private def headlineFor(count: Int, noun: String, what: String): String =
    if (count == 0) s"Nothing was $what."
    else if (count == 1) s"**1** $noun $what."
    else s"**$count** ${noun}s $what."

  private def group(builder: EmbedBuilder, title: String, names: List[String], include: Boolean): Unit =
    if (include) {
      val shown = names.take(ShownPerGroup).map(name => s"`$name`").mkString(", ")
      val more = if (names.sizeIs > ShownPerGroup) s" _and ${names.size - ShownPerGroup} more_" else ""
      builder.addField(s"$title (${names.size})", clamp(shown + more), false)
    }

  /** An embed field value is capped at 1024; a long enough run of names reaches
   *  it even after the per-group cut, so this is the backstop. */
  private def clamp(text: String): String =
    if (text.length <= MessageEmbed.VALUE_MAX_LENGTH) text
    else text.take(MessageEmbed.VALUE_MAX_LENGTH - 2).trim + "\u2026"
}
