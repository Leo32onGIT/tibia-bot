package com.tibiabot.presentation

/** Pure rendering helpers for the online list. (The full embed assembly in
 *  TibiaBot.onlineList depends on Config emoji constants + JDA channel state and
 *  stays there for now; this holds the Config-free, unit-testable bits.) */
object OnlineListEmbeds {

  /** Format an online duration (in seconds) as a backticked "Xhr Ymin" / "Xmin"
   *  string. Moved verbatim from TibiaBot.onlineList. */
  def durationString(durationInSec: Long): String = {
    val durationInMin = durationInSec / 60
    val durationStr =
      if (durationInMin >= 60) {
        val hours = durationInMin / 60
        val mins = durationInMin % 60
        s"${hours}hr ${mins}min"
      } else {
        s"${durationInMin}min"
      }
    s"`$durationStr`"
  }

  private val durationPattern = "`(?:\\d+hr )?\\d+min`".r

  /** The same line with every [[durationString]] masked out.
   *
   *  Used to decide whether an already-posted online-list message still says
   *  the same thing. Every line carries a live "how long online" duration that
   *  ticks up roughly every minute, so a raw compare almost never matches even
   *  when the roster itself is unchanged, and the message gets needlessly
   *  rewritten on every check. Masking the duration means an unchanged roster
   *  is correctly detected as unchanged; the tradeoff is that the *displayed*
   *  duration only refreshes when something else about the list changes too (a
   *  login/logout, level-up, guild change). Lives next to `durationString` so
   *  the two can't drift apart. */
  def withoutDurations(text: String): String = durationPattern.replaceAllIn(text, "`_`")

  /** The paywall's paused-online-list channel suffix (see BotApp.
   *  postPausedOnlineListNotice) — a single shared constant so the "strip
   *  the bot-appended suffix" regex below can never drift out of sync with
   *  what actually gets appended when a world pauses. */
  val pausedSuffix = "⚠️"

  // Strips a trailing "-<count>" or "-<pausedSuffix>" suffix; the regex
  // always matches, so for any real channel name the capture group is what's
  // returned. Both suffixes need stripping here, not just the numeric one —
  // otherwise resuming from paused appends the new count after the warning
  // icon instead of replacing it (e.g. "online-⚠️-64" instead of "online-64").
  private val namePattern = ("^(.*?)(?:-(?:[0-9]+|" + java.util.regex.Pattern.quote(pausedSuffix) + "))?$").r

  /** Recover a user's custom channel base name by dropping the bot-appended
   *  "-<count>" or "-<pausedSuffix>" suffix (e.g. "ɴᴇᴍᴇsɪs-42" -> "ɴᴇᴍᴇsɪs").
   *  Falls back to `default` only in the degenerate case where the pattern
   *  fails to match. Moved verbatim from TibiaBot.onlineList (numeric-suffix
   *  stripping only; the pausedSuffix case was added for the paywall). */
  def baseName(channelName: String, default: String): String =
    namePattern.findFirstMatchIn(channelName).map(_.group(1)).getOrElse(default)

  /** Build the online-list category name from the live ally/enemy counts:
   *  the world name, then "・🤍<allies>💀<enemies>" with each count omitted when
   *  zero and the "・" separator dropped entirely when both are zero. The
   *  mass-log "⚡" suffix is appended separately by the caller (the rename
   *  guard compares against this icon-free name, matching the original). */
  def categoryName(world: String, alliesCount: Int, enemiesCount: Int): String = {
    val allies = if (alliesCount > 0) s"🤍$alliesCount" else ""
    val enemies = if (enemiesCount > 0) s"💀$enemiesCount" else ""
    val spacer = if (alliesCount > 0 || enemiesCount > 0) "・" else ""
    s"$world$spacer$allies$enemies"
  }

  /** How much embed text one message may carry. Discord caps the summed text of
   *  every embed on a message (title + description + field text + footer) at
   *  6000; this leaves room for the "Last updated" footer and a little slack. */
  private val MessageBudget = 5900

  /** How much one embed's description may carry. Half the message budget, so two
   *  full embeds fit one message — which is the whole point of packing at two
   *  levels. Well under Discord's own 4096-per-description cap, and nothing is
   *  lost to the smaller figure: the message budget is what binds, and two
   *  embeds of this size saturate it exactly. */
  private val EmbedBudget = MessageBudget / 2

  /** Headroom below `EmbedBudget` at which an incoming guild header starts a
   *  fresh embed rather than being stranded above a line or two. Carried over
   *  verbatim from the single-level packing (4060 - 3850), where it bought the
   *  same thing at a whole message's cost; here an extra embed is free. */
  private val HeaderHeadroom = 210

  /** Discord's cap on embeds per message. */
  private val MaxEmbedsPerMessage = 10

  /** Would this line take the current embed past what it may hold? A guild
   *  header ("### [") breaks `HeaderHeadroom` early rather than being stranded
   *  above a line or two. */
  private def embedFull(currentField: String, line: String): Boolean =
    currentField.length >= EmbedBudget ||
      (currentField.length >= EmbedBudget - HeaderHeadroom && line.startsWith("### ["))

  /** "### " not followed by "[" — the allies/enemies/others section headings,
   *  as opposed to a guild's own header. */
  private def isSectionHeader(line: String): Boolean = line.matches("### [^\\[].*")

  /** Pack online-list lines into messages, each holding one or more embed
   *  descriptions.
   *
   *  Two levels, because Discord bounds both: a description caps near 4096, but
   *  the summed text of a message's embeds caps at 6000. One embed per message
   *  therefore wastes roughly a third of every message, and messages are the
   *  unit Discord rate-limits edits on. Packing to the message budget and
   *  splitting into embeds inside it cuts the message count by about the same
   *  third, so a refresh spends that many fewer PATCHes.
   *
   *  Lines accumulate (newline-joined) into the current embed. A line starts a
   *  fresh embed when it would take the current one to `EmbedBudget` (or to
   *  within `HeaderHeadroom` of it, when the line is a guild header "### ["),
   *  or when it is a section header ("### " not followed by "[") and the current
   *  embed is not still empty. A fresh embed rolls over to a fresh message only
   *  when this one has no room left for it — so the section and guild-header
   *  breaks, which used to cost a whole message each, now usually cost nothing.
   *
   *  Always returns at least one message holding at least one description, so an
   *  empty input yields one empty description — as the single-level packing did,
   *  which always emitted a final embed. */
  def packMessages(values: List[String]): List[List[String]] = {
    val messages = scala.collection.mutable.ListBuffer.empty[List[String]]
    var embeds = scala.collection.mutable.ListBuffer.empty[String]
    var field = ""
    // Text already committed to closed embeds on the message being built; the
    // live total is this plus `field`.
    var messageUsed = 0

    def closeMessage(): Unit = {
      messages += embeds.toList
      embeds = scala.collection.mutable.ListBuffer.empty[String]
      messageUsed = 0
    }

    // Close the current embed and open a new one holding `line`, rolling over to
    // a new message when this one cannot hold that line as well.
    def startEmbed(line: String): Unit = {
      embeds += field
      messageUsed += field.length
      if (embeds.size >= MaxEmbedsPerMessage || messageUsed + line.length >= MessageBudget) closeMessage()
      field = line
    }

    values.foreach { v =>
      val currentField = field + "\n" + v
      if (messageUsed + currentField.length >= MessageBudget || embedFull(currentField, v)) startEmbed(v)
      else if (isSectionHeader(v)) {
        if (field == "") field = currentField
        else startEmbed(v)
      } else field = currentField
    }
    embeds += field
    messages += embeds.toList
    messages.toList
  }

  /** Pack lines into embed descriptions only, for a set of lines already known
   *  to fit one message. Same break rules as [[packMessages]] without the
   *  message budget, since the caller has already decided what goes together.
   *
   *  @param leadingNewline whether the first description opens with a newline.
   *         [[packMessages]] gives one only to the very first message — it starts
   *         accumulating from an empty string, while every later message starts
   *         from the line that would not fit on the one before. Reproduced rather
   *         than tidied away so a channel repacked this way is not rewritten
   *         wholesale the first time. */
  private def packEmbeds(lines: List[String], leadingNewline: Boolean): List[String] = {
    val embeds = scala.collection.mutable.ListBuffer.empty[String]
    var field = ""
    var started = false
    lines.foreach { v =>
      val currentField = if (started || leadingNewline) field + "\n" + v else v
      if (started && (embedFull(currentField, v) || isSectionHeader(v))) {
        embeds += field
        field = v
      } else field = currentField
      started = true
    }
    embeds += field
    embeds.toList
  }

  /** The message this packing would put `lines` on, and whether that message is
   *  over what Discord allows. */
  private def overfull(embeds: List[String]): Boolean =
    embeds.map(_.length).sum > MessageBudget || embeds.size > MaxEmbedsPerMessage

  /** Pack lines into messages the way [[packMessages]] does, but keeping every
   *  line on the message it is already posted on.
   *
   *  [[packMessages]] fills each message in turn, so inserting one line shifts
   *  every boundary after it by about a line and every message from there down
   *  has to be rewritten — one login costs roughly half the channel's messages.
   *  Here a line that is already posted stays where it is, a new line joins the
   *  message its neighbour is on, and a line that has gone simply leaves a
   *  smaller message behind. Nothing is pulled backwards to fill that gap, so
   *  the room a logout leaves is what the next login into that message uses: the
   *  slack is earned by churn rather than reserved up front, which is why this
   *  costs almost no extra messages.
   *
   *  Only a message that would pass the budget spills, and it spills one line at
   *  a time into the next message, stopping at the first one with room.
   *
   *  The layout does drift — messages sit a little emptier than a fresh packing,
   *  and a levelled-up character whose row has moved backwards is dragged forward
   *  to keep the order. Both are bounded by packing from scratch periodically,
   *  which the 6-hourly purge already does.
   *
   *  @param previous the embed descriptions currently posted, message by message.
   *                  Matched against `values` with durations masked out (see
   *                  [[withoutDurations]]), or every line would look new each
   *                  time its duration ticked. */
  def packMessagesStable(values: List[String], previous: List[List[String]]): List[List[String]] = {
    // With nothing to stay put on, every line would land on message 0 and be
    // spilled forward one at a time to reach the layout packMessages reaches
    // directly.
    if (previous.isEmpty) packMessages(values)
    else packStable(values, previous)
  }

  private def packStable(values: List[String], previous: List[List[String]]): List[List[String]] = {
    val where = scala.collection.mutable.Map.empty[String, Int]
    previous.zipWithIndex.foreach { case (descriptions, index) =>
      descriptions.foreach(_.split("\n").filter(_.nonEmpty).foreach { line =>
        where.getOrElseUpdate(withoutDurations(line), index)
      })
    }

    // Walk the new list, keeping each line on its own message. An index that
    // would go backwards is clamped forward, so the messages stay in order
    // whatever has moved.
    val runs = scala.collection.mutable.ListBuffer.empty[scala.collection.mutable.ListBuffer[String]]
    var current = scala.collection.mutable.ListBuffer.empty[String]
    var currentIndex = 0
    values.foreach { line =>
      val wanted = math.max(where.getOrElse(withoutDurations(line), currentIndex), currentIndex)
      if (wanted != currentIndex && current.nonEmpty) {
        runs += current
        current = scala.collection.mutable.ListBuffer.empty[String]
      }
      currentIndex = wanted
      current += line
    }
    if (current.nonEmpty) runs += current

    // Spill only what does not fit, and only as far as it takes to find room.
    var i = 0
    while (i < runs.size) {
      while (runs(i).nonEmpty && overfull(packEmbeds(runs(i).toList, i == 0))) {
        if (i + 1 == runs.size) runs += scala.collection.mutable.ListBuffer.empty[String]
        val moved = runs(i).remove(runs(i).size - 1)
        runs(i + 1).prepend(moved)
      }
      i += 1
    }

    val packed = runs.toList.filter(_.nonEmpty).zipWithIndex.map {
      case (run, index) => packEmbeds(run.toList, index == 0)
    }
    // An empty roster still owes Discord one message, as packMessages does.
    if (packed.isEmpty) packMessages(values) else packed
  }
}
