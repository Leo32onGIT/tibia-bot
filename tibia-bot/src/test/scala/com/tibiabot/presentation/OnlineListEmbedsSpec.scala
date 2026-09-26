package com.tibiabot.presentation

import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class OnlineListEmbedsSpec extends AnyFunSuite with Matchers {

  test("durationString formats seconds as backticked minutes under an hour") {
    OnlineListEmbeds.durationString(0) shouldBe "`0min`"
    OnlineListEmbeds.durationString(59) shouldBe "`0min`"
    OnlineListEmbeds.durationString(60) shouldBe "`1min`"
    OnlineListEmbeds.durationString(3540) shouldBe "`59min`"
  }

  test("durationString switches to hours+minutes at 60 minutes") {
    OnlineListEmbeds.durationString(3600) shouldBe "`1hr 0min`"
    OnlineListEmbeds.durationString(3660) shouldBe "`1hr 1min`"
    OnlineListEmbeds.durationString(7320) shouldBe "`2hr 2min`"
  }

  test("baseName strips the bot-appended '-<count>' suffix") {
    OnlineListEmbeds.baseName("online-42", "online") shouldBe "online"
    OnlineListEmbeds.baseName("ɴᴇᴍᴇsɪs-5", "enemies") shouldBe "ɴᴇᴍᴇsɪs"
  }

  test("baseName keeps a name that has no count suffix") {
    OnlineListEmbeds.baseName("allies", "allies") shouldBe "allies"
  }

  test("baseName only strips a trailing -digits, preserving internal hyphens and bare dashes") {
    OnlineListEmbeds.baseName("my-cool-list-99", "online") shouldBe "my-cool-list"
    OnlineListEmbeds.baseName("online-", "online") shouldBe "online-"
  }

  test("baseName strips the bot-appended paused-suffix, same as a count suffix") {
    OnlineListEmbeds.baseName(s"online-${OnlineListEmbeds.pausedSuffix}", "online") shouldBe "online"
  }

  test("baseName round-trips through pause then resume without stacking suffixes") {
    // Regression: resuming from paused must replace "-<pausedSuffix>" with
    // "-<count>", not append after it (e.g. "online-⚠️-64").
    val paused = s"online-${OnlineListEmbeds.pausedSuffix}"
    val resumed = s"${OnlineListEmbeds.baseName(paused, "online")}-64"
    resumed shouldBe "online-64"
  }

  test("categoryName shows both counts with the separator when both are positive") {
    OnlineListEmbeds.categoryName("Antica", 5, 2) shouldBe "Antica・🤍5💀2"
  }

  test("categoryName omits a zero count but keeps the separator while the other is positive") {
    OnlineListEmbeds.categoryName("Antica", 5, 0) shouldBe "Antica・🤍5"
    OnlineListEmbeds.categoryName("Antica", 0, 3) shouldBe "Antica・💀3"
  }

  test("categoryName drops the separator entirely when both counts are zero") {
    OnlineListEmbeds.categoryName("Antica", 0, 0) shouldBe "Antica"
  }

  // --- packMessages ---

  // Discord's own caps on a V2 message, which packMessages exists to stay
  // under: the text of everything on it, and its components.
  private val DiscordTextCap = 4000
  private val DiscordComponentCap = 40

  private val label = "-# **OTHERS** · 3"

  /** Everything a message is sent with, Last updated included, as Discord
   *  counts it. */
  private def discordCost(blocks: List[String]): (Int, Int) = {
    val card = OnlineListEmbeds.card(blocks, Some(java.time.Instant.parse("2026-09-27T20:00:00Z")))
    val parts = card.getComponents.asScala.toList
    val text = parts.collect { case t: TextDisplay => t.getContent.length }.sum
    (text, 1 + parts.size)
  }

  private def withinDiscord(messages: List[List[String]]): Unit =
    messages.foreach { blocks =>
      val (text, components) = discordCost(blocks)
      text should be <= DiscordTextCap
      components should be <= DiscordComponentCap
    }

  test("packMessages always returns at least one message holding one (empty) block") {
    OnlineListEmbeds.packMessages(Nil) shouldBe List(List(""))
  }

  test("packMessages newline-joins short lines into a single block") {
    OnlineListEmbeds.packMessages(List("a", "b", "c")) shouldBe List(List("a\nb\nc"))
  }

  test("a label opens a new block on the same message") {
    OnlineListEmbeds.packMessages(List("a", label, "b")) shouldBe List(List("a", s"$label\nb"))
  }

  test("a label does not open an empty block while the current one is empty") {
    OnlineListEmbeds.packMessages(List(label, "b")) shouldBe List(List(s"$label\nb"))
  }

  test("every label opens its own block, so a divider falls between every two groups") {
    OnlineListEmbeds.packMessages(List(label, "a", label, "b", label, "c")) shouldBe
      List(List(s"$label\na", s"$label\nb", s"$label\nc"))
  }

  test("a line that would take the message past its budget rolls to a new one") {
    val packed = OnlineListEmbeds.packMessages(List("x" * 3000, "y" * 1000))
    packed shouldBe List(List("x" * 3000), List("y" * 1000))
  }

  test("a label is never left at the foot of a message without its first row") {
    val packed = OnlineListEmbeds.packMessages(List("x" * 3882, label, "b"))
    packed shouldBe List(List("x" * 3882), List(s"$label\nb"))
  }

  test("a message never carries more blocks than Discord's components allow") {
    val packed = OnlineListEmbeds.packMessages(List.fill(19)(List(label, "r")).flatten)
    packed.map(_.size) shouldBe List(18, 1)
    withinDiscord(packed)
  }

  test("a realistic roster stays inside both of Discord's caps, and loses nothing") {
    // Labels, guild buckets and ~140-char rows, which is what a rendered row
    // costs (emoji, level, name + character URL, guild icon, duration, flag).
    def rows(from: Int, n: Int) = (from until from + n).map(i => s"$i" + ("r" * 139)).toList
    val lines =
      ("-# :ally: **ALLIES** · 40" :: rows(0, 40)) :::
      ("-# :enemy: **ENEMIES** · 60" :: rows(40, 60)) :::
      (1 to 8).toList.flatMap { g =>
        OnlineListGrouping.guildLabel(s"Guild $g", 45) :: rows(100 + g * 45, 45)
      }
    val packed = OnlineListEmbeds.packMessages(lines)

    withinDiscord(packed)
    // No line is dropped, duplicated or reordered on the way through.
    packed.flatten.flatMap(_.split("\n").filter(_.nonEmpty)) shouldBe lines
    // Every message but the last is filled close to the budget.
    val totalChars = lines.map(_.length + 1).sum
    packed.size should be <= (totalChars / 3500 + 1)
  }

  // --- the card ---

  test("a message is one card with no edge, its blocks divided, and only the last says when") {
    val at = java.time.Instant.parse("2026-09-27T20:00:00Z")
    val last = OnlineListEmbeds.card(List(s"$label\na", s"$label\nb"), Some(at))
    last.getAccentColorRaw shouldBe null
    last.getComponents.asScala.toList.map {
      case t: TextDisplay => t.getContent
      case _: Separator   => "---"
      case other          => other.toString
    } shouldBe List(s"$label\na", "---", s"$label\nb", "---", s"-# Last updated <t:${at.getEpochSecond}:R>")

    val earlier = OnlineListEmbeds.card(List("a"), None)
    earlier.getComponents.asScala.toList.collect { case t: TextDisplay => t.getContent } shouldBe List("a")
  }

  test("a message with nothing in it still makes a valid card") {
    OnlineListEmbeds.card(List(""), None).getComponents.asScala should have size 1
  }

  // --- packMessagesStable ---

  /** ~142 chars, the cost of a rendered row. */
  private def rows(n: Int, from: Int = 0): List[String] =
    (from until from + n).map(i => "%05d".format(i) + ("r" * 137)).toList

  private def linesOf(message: List[String]): List[String] =
    message.flatMap(_.split("\n").filter(_.nonEmpty))

  test("with nothing posted yet it packs from scratch") {
    val lines = rows(120)
    OnlineListEmbeds.packMessagesStable(lines, Nil) shouldBe OnlineListEmbeds.packMessages(lines)
  }

  test("an unchanged list repacks to exactly what is already posted") {
    val lines = rows(160)
    val posted = OnlineListEmbeds.packMessages(lines)
    OnlineListEmbeds.packMessagesStable(lines, posted) shouldBe posted
  }

  test("durations ticking up move nothing") {
    val lines = List("Bubble `5min`", "Cip `1hr 2min`")
    val posted = OnlineListEmbeds.packMessages(lines)
    val ticked = List("Bubble `9min`", "Cip `1hr 6min`")
    OnlineListEmbeds.packMessagesStable(ticked, posted) shouldBe List(List("Bubble `9min`\nCip `1hr 6min`"))
  }

  test("a logout shrinks its own message and leaves every later one alone") {
    val lines = rows(160)
    val posted = OnlineListEmbeds.packMessages(lines)
    posted.size should be >= 4
    val goneFrom = linesOf(posted(2)).head
    val packed = OnlineListEmbeds.packMessagesStable(lines.filterNot(_ == goneFrom), posted)

    packed.take(2) shouldBe posted.take(2)
    packed.drop(3) shouldBe posted.drop(3)
    linesOf(packed(2)) shouldBe linesOf(posted(2)).tail
  }

  test("a login joins the message it belongs to, once a logout has left room there") {
    val lines = rows(160)
    val posted = OnlineListEmbeds.packMessages(lines)
    val target = linesOf(posted(2))
    val loosened = OnlineListEmbeds.packMessagesStable(lines.filterNot(_ == target.head), posted)

    // a new row sorting into the middle of that same message
    val withLogin = lines.filterNot(_ == target.head).flatMap { l =>
      if (l == target(4)) List(l, "99999" + ("n" * 137)) else List(l)
    }
    val packed = OnlineListEmbeds.packMessagesStable(withLogin, loosened)

    packed.take(2) shouldBe loosened.take(2)
    packed.drop(3) shouldBe loosened.drop(3)
    linesOf(packed(2)) should contain("99999" + ("n" * 137))
  }

  test("a full message spills one line forward, and only as far as room") {
    val lines = rows(160)
    val posted = OnlineListEmbeds.packMessages(lines)
    posted.size should be >= 4
    // make room on the third message, then log somebody in at the very front
    val loosened = OnlineListEmbeds.packMessagesStable(lines.filterNot(_ == linesOf(posted(2)).head), posted)
    val remaining = lines.filterNot(_ == linesOf(posted(2)).head)
    val packed = OnlineListEmbeds.packMessagesStable(("00000" + ("n" * 137)) :: remaining, loosened)

    // the first two messages absorbed and passed on a line; the third took it
    // and stopped there, so nothing beyond moved
    packed.drop(3) shouldBe loosened.drop(3)
    packed.head should not be loosened.head
  }

  test("a message emptied of everything disappears") {
    val lines = rows(160)
    val posted = OnlineListEmbeds.packMessages(lines)
    val second = linesOf(posted(1)).toSet
    val packed = OnlineListEmbeds.packMessagesStable(lines.filterNot(second.contains), posted)

    packed.size shouldBe posted.size - 1
    packed.head shouldBe posted.head
    packed(1) shouldBe posted(2)
  }

  test("a line that has moved backwards is dragged forward, keeping the order") {
    val lines = rows(160)
    val posted = OnlineListEmbeds.packMessages(lines)
    val moved = linesOf(posted(2)).head
    // that row now sorts to the very front, e.g. after a level-up
    val reordered = moved :: lines.filterNot(_ == moved)
    val packed = OnlineListEmbeds.packMessagesStable(reordered, posted)

    packed.flatMap(linesOf) shouldBe reordered
  }

  test("a stably packed list still stays inside both Discord caps") {
    var lines = rows(400)
    var packed = OnlineListEmbeds.packMessages(lines)
    // fifty refreshes of churn, packing against what the last one produced
    (1 to 50).foreach { cycle =>
      lines = lines.drop(3) ::: rows(3, 100000 + cycle * 3)
      packed = OnlineListEmbeds.packMessagesStable(lines, packed)
      withinDiscord(packed)
      packed.flatMap(linesOf) shouldBe lines
    }
  }

  test("staying put costs far fewer message rewrites than repacking each time") {
    // The whole point of the function: pack fresh and one login shunts every
    // message after it along by a line, so half the channel is rewritten.
    val rng = new scala.util.Random(4)
    var lines = rows(400)
    var fresh = OnlineListEmbeds.packMessages(lines)
    var stable = fresh
    var freshEdits = 0
    var stableEdits = 0

    def changed(before: List[List[String]], after: List[List[String]]): Int =
      (0 until math.max(before.size, after.size)).count(i => before.lift(i) != after.lift(i))

    (1 to 60).foreach { cycle =>
      // one logout and one login, both landing somewhere in the middle
      lines = lines.patch(rng.nextInt(lines.size), Nil, 1)
      lines = lines.patch(rng.nextInt(lines.size), rows(1, 900000 + cycle), 0)

      val nextFresh = OnlineListEmbeds.packMessages(lines)
      val nextStable = OnlineListEmbeds.packMessagesStable(lines, stable)
      freshEdits += changed(fresh, nextFresh)
      stableEdits += changed(stable, nextStable)
      fresh = nextFresh
      stable = nextStable
    }

    // Asserted loosely so the figure can move without the guarantee going
    // quietly missing.
    info(s"fresh $freshEdits edits / ${fresh.size} msgs, stable $stableEdits edits / ${stable.size} msgs")
    stableEdits.toDouble should be < (freshEdits * 0.75)
    // and without buying that with a pile of extra messages
    stable.size.toDouble should be < (fresh.size * 1.25)
    stable.flatMap(linesOf) shouldBe lines
  }

  // --- a label and the rows it introduces ---
  //
  // A label is the one line whose meaning comes from what is under it, so being
  // the last thing in a block makes it read as a mistake: the reader gets a
  // guild name with nothing beneath it and that guild's players opening the
  // next message, as if they belonged to nobody.

  private def guildHeading(name: String, count: Int) = OnlineListGrouping.guildLabel(name, count)

  private def guildRoster(guilds: Int, per: Int): List[String] =
    (1 to guilds).flatMap(g => guildHeading(s"Guild$g", per) :: rows(per, from = g * 100)).toList

  /** Labels left as the last line of the block they sit in. */
  private def strandedHeadings(messages: List[List[String]]): List[String] =
    messages.flatMap(_.flatMap(_.split("\n").filter(_.nonEmpty).lastOption.filter(_.startsWith("-# "))))

  test("a fresh packing never ends a block on a label, at any roster shape") {
    // Swept rather than sampled: the failure needs a label to land within a
    // row's length of the message budget, so a single shape proves very little
    // and the shapes that hit it are not the ones anybody would pick by hand.
    val offenders = for {
      guilds <- 4 to 40
      per <- 2 to 8
      bad = strandedHeadings(OnlineListEmbeds.packMessages(guildRoster(guilds, per)))
      if bad.nonEmpty
    } yield s"$guilds guilds of $per -> ${bad.mkString(",")}"

    offenders shouldBe empty
  }

  test("a label rolled onto a new message takes its rows with it") {
    val packed = OnlineListEmbeds.packMessages(guildRoster(22, 4))
    // Wherever a message starts with a label, the next line is one of its rows
    // rather than another message boundary.
    packed.foreach { message =>
      val lines = linesOf(message)
      if (lines.headOption.exists(_.startsWith("-# "))) lines.size should be > 1
    }
    packed.flatMap(linesOf) shouldBe guildRoster(22, 4)
    withinDiscord(packed)
  }

  test("the stable packing does not strand a label as churn spills rows forward") {
    // The spill moves rows one at a time off the end of a full message. Taking
    // the last of a guild's players while leaving the guild's name behind is the
    // same separation as above, reached from the other direction.
    val random = new scala.util.Random(7)
    var lines = guildRoster(28, 5)
    var stable = OnlineListEmbeds.packMessages(lines)

    (1 to 60).foreach { round =>
      lines = lines.patch(random.nextInt(lines.size), rows(1, from = 50000 + round), 0)
      val victim = lines.indexWhere(line => !line.startsWith("-# ") && random.nextBoolean())
      if (victim >= 0) lines = lines.patch(victim, Nil, 1)
      // A guild whose last player has gone loses its label with them: the list
      // never labels a guild with nobody under it.
      lines = lines.zipWithIndex.filterNot { case (line, i) =>
        line.startsWith("-# ") && lines.lift(i + 1).forall(_.startsWith("-# "))
      }.map(_._1)
      stable = OnlineListEmbeds.packMessagesStable(lines, stable)
      withClue(s"round $round: ") {
        strandedHeadings(stable) shouldBe empty
        withinDiscord(stable)
      }
    }
    stable.flatMap(linesOf) shouldBe lines
  }

  test("a label already separated from its rows is reunited, not preserved") {
    // Keeping every line where it is is the point of the stable packing,
    // and this is the one case where it is the wrong answer: both sides keep the
    // position they are being read from, so the split cannot heal on its own and
    // a channel stays wrong until the 6-hourly purge repacks it.
    val lines = guildHeading("Alpha", 2) :: rows(2, from = 1) :::
      guildHeading("NoDramas", 3) :: rows(3, from = 10)
    val split = List(
      List((guildHeading("Alpha", 2) :: rows(2, from = 1)).mkString("\n"), guildHeading("NoDramas", 3)),
      List(rows(3, from = 10).mkString("\n"))
    )
    strandedHeadings(split) should have size 1

    val healed = OnlineListEmbeds.packMessagesStable(lines, split)
    strandedHeadings(healed) shouldBe empty
    linesOf(healed.last).head shouldBe guildHeading("NoDramas", 3)
    healed.flatMap(linesOf) shouldBe lines
  }
}
