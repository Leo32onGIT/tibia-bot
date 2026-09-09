package com.tibiabot.lootsplit

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** Reads what the Tibia client puts on the clipboard when you copy a party hunt
 *  session out of the party analyser.
 *
 *  The shape is a header block, then one block per member:
 *  {{{
 *  Session data: From 2026-09-01, 21:12:00 to 2026-09-01, 23:29:40
 *  Session: 02:17h
 *  Loot Type: Leader
 *  Loot: 14,359,954
 *  Supplies: 5,354,392
 *  Balance: 9,005,562
 *  The Wingga (Leader)
 *  	Loot: 11,518,496
 *  	...
 *  }}}
 *
 *  ==Why this does not read the indentation==
 *  The client indents a member's lines with a tab, which makes "is this a name or a
 *  value" look like a one-character question. It isn't: the text arrives here by way
 *  of a person's clipboard and a Discord textarea, and leading whitespace is exactly
 *  what that route is worst at preserving. A paste with the tabs eaten still has to
 *  split correctly, so the split is made on the only thing that survives — a line is
 *  a value if it reads `<known key>: <number>`, and anything else starts a member.
 *  Nothing in the format collides with that: Tibia names cannot contain a colon.
 *
 *  Pure and total: every failure comes back as a sentence to show the person who
 *  pasted, naming what was wrong rather than that something was.
 */
object HuntAnalyser {

  /** Keys the header block uses. `loot type` is the only one not also a member key. */
  private val HeaderKeys = Set("session", "loot type", "loot", "supplies", "balance")

  /** Keys a member block uses. Damage and healing are display-only, so a block
   *  missing them still splits; the money keys are checked on the way out. */
  private val MemberKeys = Set("loot", "supplies", "balance", "damage", "healing")

  private val Stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd, HH:mm:ss")

  private val SessionData = """(?i)^session data:\s*from\s+(.+?)\s+to\s+(.+?)\s*$""".r

  /** The client marks whoever is holding the loot. Dropped from the name — the
   *  transfers name people, and `transfer 1 to Bob (Leader)` is not a command. */
  private val LeaderSuffix = """(?i)\s*\(leader\)\s*$""".r

  /** `Loot: 1,654,034` -> `("loot", "1,654,034")`, but only for keys the format
   *  actually uses: every other line is somebody's name. */
  private object KeyLine {
    def unapply(line: String): Option[(String, String)] =
      line.split(":", 2) match {
        case Array(key, value) =>
          val normalised = key.trim.toLowerCase
          if (HeaderKeys.contains(normalised) || MemberKeys.contains(normalised)) Some(normalised -> value.trim)
          else None
        case _ => None
      }
  }

  private final case class Block(name: String, leader: Boolean, values: Map[String, String]) {
    def withValue(key: String, value: String): Block = copy(values = values + (key -> value))
  }

  def parse(text: String): Either[String, HuntSession] = {
    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    lines match {
      case Nil =>
        Left("There was nothing in that box to read.")
      case head :: _ if !head.toLowerCase.startsWith("session data:") =>
        Left("That doesn't look like a party hunt analyser. Copy the whole session out of the " +
          s"party window — it starts with a `Session data:` line, and yours starts with `${preview(head)}`.")
      case head :: rest =>
        val (header, blocks) = split(rest)
        for {
          balance <- number(header, "balance").toRight(
            "I couldn't find the session's `Balance:` line. Copy the whole session rather than part of it.")
          members <- readMembers(blocks)
        } yield {
          val (from, to) = timestamps(head)
          HuntSession(
            from = from,
            to = to,
            sessionLabel = header.getOrElse("session", ""),
            lootType = header.getOrElse("loot type", ""),
            loot = number(header, "loot").getOrElse(0L),
            supplies = number(header, "supplies").getOrElse(0L),
            balance = balance,
            members = members
          )
        }
    }
  }

  /** Header values, then a block per member, in the order they were pasted.
   *
   *  A key line before any name belongs to the header; after one, to the member
   *  whose name it followed. That single rule is the whole format. */
  private def split(lines: List[String]): (Map[String, String], List[Block]) = {
    var header = Map.empty[String, String]
    val blocks = ListBuffer.empty[Block]
    lines.foreach {
      case KeyLine(key, value) if blocks.isEmpty => header += (key -> value)
      case KeyLine(key, value)                   => blocks.update(blocks.size - 1, blocks.last.withValue(key, value))
      case name =>
        blocks += Block(LeaderSuffix.replaceAllIn(name, "").trim, LeaderSuffix.findFirstIn(name).isDefined, Map.empty)
    }
    (header, blocks.toList)
  }

  /** Every member, or a sentence saying which one didn't come through whole.
   *
   *  Discord caps a paragraph box at 4,000 characters, which a party of about
   *  twenty overruns — and a truncated paste ends mid-block rather than raising
   *  anything. So the last block being short is called out as a cut-off paste
   *  specifically: splitting the members who did survive would hand back numbers
   *  that look right and are not.
   */
  private def readMembers(blocks: List[Block]): Either[String, List[HuntMember]] = {
    val incomplete = blocks.filterNot(block => Set("loot", "supplies", "balance").subsetOf(block.values.keySet))
    if (incomplete.isEmpty) Right(blocks.map(member))
    else if (incomplete == List(blocks.last))
      Left(s"That paste stops part-way through **${preview(blocks.last.name)}** — Discord's box holds " +
        "4,000 characters, and the session is longer than that. Split the party's loot in the game client instead.")
    else
      Left(s"**${preview(incomplete.head.name)}** is missing a `Loot:`, `Supplies:` or `Balance:` line. " +
        "Copy the session again without editing it.")
  }

  private def member(block: Block): HuntMember =
    HuntMember(
      name = block.name,
      loot = number(block.values, "loot").getOrElse(0L),
      supplies = number(block.values, "supplies").getOrElse(0L),
      balance = number(block.values, "balance").getOrElse(0L),
      damage = number(block.values, "damage").getOrElse(0L),
      healing = number(block.values, "healing").getOrElse(0L),
      leader = block.leader
    )

  /** The header's two timestamps, both empty when they don't read as timestamps.
   *
   *  A soft failure rather than a hard one: everything except the hourly rate is
   *  computed from the numbers below, so a client writing its dates some other way
   *  should cost the reader one line of the embed and not the whole split. */
  private def timestamps(header: String): (Option[LocalDateTime], Option[LocalDateTime]) =
    header match {
      case SessionData(start, end) => (stamp(start), stamp(end))
      case _                       => (None, None)
    }

  private def stamp(text: String): Option[LocalDateTime] =
    Try(LocalDateTime.parse(text.trim, Stamp)).toOption

  private def number(values: Map[String, String], key: String): Option[Long] =
    values.get(key).flatMap(number)

  /** `-748,351` -> `-748351`. The separator is whatever the client felt like — a
   *  comma, a dot or a space, depending on where it thinks it is — so only the
   *  digits and a leading minus are kept. */
  private def number(raw: String): Option[Long] = {
    val digits = raw.replaceAll("[^0-9]", "")
    if (digits.isEmpty) None
    else Try(if (raw.trim.startsWith("-")) -digits.toLong else digits.toLong).toOption
  }

  /** A line quoted back in an error, short enough not to be the error. */
  private def preview(line: String): String =
    if (line.length <= 40) line else line.take(39).trim + "…"
}
