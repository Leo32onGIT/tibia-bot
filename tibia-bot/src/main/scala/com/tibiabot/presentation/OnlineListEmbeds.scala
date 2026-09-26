package com.tibiabot.presentation

import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.entities.Message

import scala.jdk.CollectionConverters._

/** Pure rendering helpers for the online list: its rows' pieces, how its lines
 *  are packed into messages, and each message as a Components V2 card (27 Sep
 *  2026; embeds before, which is where the name comes from). The rows and the
 *  grouping are assembled in TibiaBot.onlineList, which needs Config emoji and
 *  JDA channel state; this holds the Config-free, unit-testable bits. */
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

  // --- the message itself (Components V2 since 27 Sep 2026; embeds before) ---
  //
  // A message is one card with no edge, the way the embeds before it wore the
  // background colour. Each group — the allies, the enemies, a guild, the
  // guildless — is one text block, with a divider between every two, and the
  // last message closes on "Last updated", counting live.

  /** How much text one message may carry. Discord caps everything on a V2
   *  message at 4,000 characters, counted over the raw text; this leaves room
   *  for the Last updated line and a little slack. */
  private val MessageBudget = 3900

  /** Blocks on one message. Each is a text and, after the first, a divider,
   *  and Discord allows 40 components: the card, eighteen blocks with the
   *  dividers between them, and the Last updated line with its own divider come
   *  to 38. */
  private val MaxBlocks = 18

  /** What the Last updated line opens with, which is how a message read back
   *  from the channel is told apart from the list itself. */
  private val LastUpdatedLead = "-# Last updated"

  /** A heading: the label over a group, allies and enemies and guilds alike.
   *  Rows never open with it. */
  private def isHeader(line: String): Boolean = line.startsWith("-# ")

  /** Pack online-list lines into messages, each holding one or more text
   *  blocks.
   *
   *  Every heading opens a block of its own, so the divider between two groups
   *  falls where one ends and the next begins. A line rolls over to a fresh
   *  message when this one cannot hold it — together with whatever it must keep
   *  with it, see `follows` — or when a heading would give it more blocks than
   *  Discord's component cap allows. Messages are the unit Discord rate-limits
   *  edits on, so each one is filled as far as it goes.
   *
   *  Always returns at least one message holding at least one block, so an
   *  empty input yields one empty block. */
  def packMessages(values: List[String]): List[List[String]] = {
    val messages = scala.collection.mutable.ListBuffer.empty[List[String]]
    var blocks = scala.collection.mutable.ListBuffer.empty[String]
    var block = ""
    // Text already committed to closed blocks on the message being built; the
    // live total is this plus `block`.
    var messageUsed = 0

    def closeMessage(): Unit = {
      messages += blocks.toList
      blocks = scala.collection.mutable.ListBuffer.empty[String]
      messageUsed = 0
    }

    // What each heading must be able to keep on its own message: the headings
    // that follow it, if any, and the first row underneath them. Rows keep
    // nothing, so this is 0 for them.
    //
    // Without it a heading that merely fits is placed, the row it introduces
    // trips the message budget on the very next line, and the reader is left
    // with a guild name at the bottom of one message and its players at the top
    // of the next.
    //
    // Measured rather than a fixed allowance, because a heading is only stranded
    // by the row that actually follows it, and rounding that up to a constant
    // would roll headings onto a new message that had room for them.
    val follows = new Array[Int](values.size)
    var keeps = 0
    values.zipWithIndex.reverse.foreach { case (line, index) =>
      follows(index) = if (isHeader(line)) keeps else 0
      keeps = if (isHeader(line)) keeps + line.length + 1 else line.length + 1
    }

    values.zipWithIndex.foreach { case (v, index) =>
      val keepsWith = follows(index)
      val grown = if (block.isEmpty) v else block + "\n" + v
      if (block.nonEmpty && messageUsed + grown.length + keepsWith > MessageBudget) {
        blocks += block
        closeMessage()
        block = v
      } else if (isHeader(v) && block.nonEmpty) {
        blocks += block
        messageUsed += block.length
        if (blocks.size >= MaxBlocks || messageUsed + v.length + keepsWith > MessageBudget) closeMessage()
        block = v
      } else block = grown
    }
    blocks += block
    messages += blocks.toList
    messages.toList
  }

  /** Lines already known to fit one message, as its blocks: a new one at every
   *  heading, the same break [[packMessages]] makes. */
  private def packBlocks(lines: List[String]): List[String] =
    lines.foldLeft(List.empty[List[String]]) {
      case (Nil, line) => List(List(line))
      case (current :: done, line) =>
        if (isHeader(line)) List(line) :: current :: done else (line :: current) :: done
    }.reverse.map(_.reverse.mkString("\n"))

  /** Whether blocks are more than one message may carry. */
  private def overfull(blocks: List[String]): Boolean =
    blocks.map(_.length).sum > MessageBudget || blocks.size > MaxBlocks

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
   *  @param previous the blocks currently posted, message by message. Matched
   *                  against `values` with durations masked out (see
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
    previous.zipWithIndex.foreach { case (blocks, index) =>
      blocks.foreach(_.split("\n").filter(_.nonEmpty).foreach { line =>
        where.getOrElseUpdate(withoutDurations(line), index)
      })
    }

    // What each line's position should follow. A row follows itself; a heading
    // follows the first row underneath it, because a heading's place in the
    // channel is decided entirely by where its rows are. Staying put is what
    // this whole function is for, but a heading that stays put while its rows
    // move is the one case where staying put is the wrong answer — and, since
    // both sides then keep the positions they are being read from, the one case
    // that never recovers on its own. Anchoring pulls such a heading forward to
    // its rows on the next cycle instead of waiting for the 6-hourly purge.
    val anchors = new Array[String](values.size)
    var nextRow: String = null
    values.zipWithIndex.reverse.foreach { case (line, index) =>
      if (isHeader(line)) anchors(index) = nextRow
      else {
        nextRow = withoutDurations(line)
        anchors(index) = nextRow
      }
    }

    // Walk the new list, keeping each line on its own message. An index that
    // would go backwards is clamped forward, so the messages stay in order
    // whatever has moved.
    val runs = scala.collection.mutable.ListBuffer.empty[scala.collection.mutable.ListBuffer[String]]
    var current = scala.collection.mutable.ListBuffer.empty[String]
    var currentIndex = 0
    values.zipWithIndex.foreach { case (line, index) =>
      // A heading with no rows after it at all has nothing to follow, so it
      // keeps its own place.
      val anchor = Option(anchors(index)).getOrElse(withoutDurations(line))
      val wanted = math.max(where.getOrElse(anchor, currentIndex), currentIndex)
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
      def spill(): Unit = {
        if (i + 1 == runs.size) runs += scala.collection.mutable.ListBuffer.empty[String]
        runs(i + 1).prepend(runs(i).remove(runs(i).size - 1))
      }
      while (runs(i).nonEmpty && overfull(packBlocks(runs(i).toList))) {
        spill()
        // Whatever a spill leaves exposed at the end goes with it. Moving rows
        // one at a time off the end of a full message will otherwise take the
        // last of a guild's players and leave the guild's name behind — the
        // same separation as above, arrived at from the other direction, and
        // then pinned in place by `where` on every cycle after it.
        while (runs(i).nonEmpty && isHeader(runs(i).last)) spill()
      }
      i += 1
    }

    val packed = runs.toList.filter(_.nonEmpty).map(run => packBlocks(run.toList))
    // An empty roster still owes Discord one message, as packMessages does.
    if (packed.isEmpty) packMessages(values) else packed
  }

  /** One message of the list: its blocks as one card, a divider between every
   *  two, and — on the last message — Last updated, as a live timestamp so it
   *  reads "a minute ago" rather than a clock time the reader has to compare.
   *  An empty block is left out; a message with nothing at all still gets a
   *  blank line, since a card must hold something. */
  def card(blocks: List[String], updatedAt: Option[java.time.Instant]): Container = {
    val texts = blocks.filter(_.trim.nonEmpty) ++ updatedAt.map(at => s"$LastUpdatedLead <t:${at.getEpochSecond}:R>")
    val children = (if (texts.isEmpty) List("​") else texts).zipWithIndex.flatMap { case (text, index) =>
      val shown: ContainerChildComponent = TextDisplay.of(text)
      if (index == 0) List(shown) else List(Separator.createDivider(Separator.Spacing.SMALL), shown)
    }
    Container.of(children.asJava)
  }

  /** The blocks of a message the bot posted, read back from the channel: the
   *  card's texts without Last updated, or — for a message posted before 27 Sep
   *  2026 — its embeds' descriptions. Either way it is what [[packMessagesStable]]
   *  and the change comparison expect to be handed. */
  def blocksOf(message: Message): List[String] =
    if (message.isUsingComponentsV2)
      message.getComponents.asScala.toList.collect { case card: Container => card }
        .flatMap(_.getComponents.asScala).collect { case text: TextDisplay => text.getContent }
        .filterNot(_.startsWith(LastUpdatedLead))
    else message.getEmbeds.asScala.toList.map(embed => Option(embed.getDescription).getOrElse(""))
}
