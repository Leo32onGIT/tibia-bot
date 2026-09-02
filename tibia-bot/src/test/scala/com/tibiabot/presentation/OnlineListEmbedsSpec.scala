package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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

  // Discord's own caps, which packMessages exists to stay under: a message's
  // embed text summed, and any one description.
  private val DiscordMessageCap = 6000
  private val DiscordDescriptionCap = 4096

  test("packMessages always returns at least one message holding one (empty) description") {
    OnlineListEmbeds.packMessages(Nil) shouldBe List(List(""))
  }

  test("packMessages newline-joins short lines into a single description") {
    OnlineListEmbeds.packMessages(List("a", "b", "c")) shouldBe List(List("\na\nb\nc"))
  }

  test("a section header starts a new embed but stays on the same message") {
    OnlineListEmbeds.packMessages(List("a", "### Neutrals", "b")) shouldBe List(List("\na", "### Neutrals\nb"))
  }

  test("a section header does not start a new embed while the current one is empty") {
    OnlineListEmbeds.packMessages(List("### Neutrals", "b")) shouldBe List(List("\n### Neutrals\nb"))
  }

  test("a guild header ('### [') stays with the preceding lines") {
    OnlineListEmbeds.packMessages(List("a", "### [Guild](u)", "b")) shouldBe List(List("\na\n### [Guild](u)\nb"))
  }

  test("a full embed starts a second embed on the same message, not a second message") {
    val packed = OnlineListEmbeds.packMessages(List("x" * 2900, "y" * 100))
    packed should have size 1
    packed.head shouldBe List("\n" + ("x" * 2900), "y" * 100)
  }

  test("a second full embed fills the message, so the next line rolls to a new one") {
    val packed = OnlineListEmbeds.packMessages(List("x" * 2900, "y" * 2900, "z" * 100))
    packed should have size 2
    packed.head should have size 2
    packed.last shouldBe List("z" * 100)
  }

  test("an incoming guild header breaks the embed early rather than being stranded") {
    val packed = OnlineListEmbeds.packMessages(List("x" * 2730, "### [G](u)"))
    packed shouldBe List(List("\n" + ("x" * 2730), "### [G](u)"))
  }

  test("a message never carries more than Discord's ten embeds") {
    val packed = OnlineListEmbeds.packMessages(List.fill(11)("### Section"))
    packed.map(_.size) shouldBe List(10, 1)
  }

  test("a realistic roster stays inside both Discord caps and beats one embed per message") {
    // Section headers, guild buckets and ~140-char rows, which is what a
    // rendered row costs (emoji, level, name + character URL, guild icon,
    // duration, flag).
    def rows(from: Int, n: Int) = (from until from + n).map(i => s"$i" + ("r" * 139)).toList
    val lines =
      ("### Allies 40" :: rows(0, 40)) :::
      ("### Enemies 60" :: rows(40, 60)) :::
      (1 to 8).toList.flatMap { g =>
        s"### [Guild $g](https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=Guild+$g) 45" ::
          rows(100 + g * 45, 45)
      }
    val packed = OnlineListEmbeds.packMessages(lines)

    packed.foreach { descriptions =>
      descriptions.map(_.length).sum should be < DiscordMessageCap
      descriptions.size should be <= 10
      descriptions.foreach(_.length should be < DiscordDescriptionCap)
    }
    // No line is dropped, duplicated or reordered on the way through.
    packed.flatten.flatMap(_.split("\n").filter(_.nonEmpty)) shouldBe lines

    // The single-level packing fitted ~4060 chars per message, and paid a whole
    // message for each of the ten headers above; this fits ~5800 and pays none.
    val totalChars = lines.map(_.length + 1).sum
    packed.size should be < totalChars / 4060
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
    OnlineListEmbeds.packMessagesStable(ticked, posted) shouldBe List(List("\nBubble `9min`\nCip `1hr 6min`"))
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
      packed.foreach { descriptions =>
        descriptions.map(_.length).sum should be < DiscordMessageCap
        descriptions.size should be <= 10
        descriptions.foreach(_.length should be < DiscordDescriptionCap)
      }
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

    // Measured at roughly a third of the rewrites; asserted loosely so the
    // figure can move without the guarantee going quietly missing.
    info(s"fresh $freshEdits edits / ${fresh.size} msgs, stable $stableEdits edits / ${stable.size} msgs")
    stableEdits.toDouble should be < (freshEdits * 0.75)
    // and without buying that with a pile of extra messages
    stable.size.toDouble should be < (fresh.size * 1.25)
    stable.flatMap(linesOf) shouldBe lines
  }
}
