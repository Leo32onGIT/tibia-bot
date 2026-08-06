package com.tibiabot.presentation

import com.tibiabot.domain.PlayerCache

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class GuildActivitySpec extends AnyFunSuite with Matchers {

  private val now = ZonedDateTime.parse("2026-08-05T12:00:00Z")
  private val earlier = now.minusHours(1)
  private def row(name: String, guild: String = "Nemesis") = PlayerCache(name, List(""), guild, earlier)
  private val nobodyOnline: String => Boolean = _ => false

  test("activityColor: hunted is red, allied is green, otherwise yellow") {
    GuildActivity.activityColor(huntedGuild = true, alliedGuild = false) shouldBe 13773097
    GuildActivity.activityColor(huntedGuild = false, alliedGuild = true) shouldBe 36941
    GuildActivity.activityColor(huntedGuild = false, alliedGuild = false) shouldBe 14397256
  }

  test("activityColor prefers hunted when both flags are set") {
    GuildActivity.activityColor(huntedGuild = true, alliedGuild = true) shouldBe 13773097
  }

  test("guildType: hunted / allied / neutral label") {
    GuildActivity.guildType(huntedGuild = true, alliedGuild = false) shouldBe "hunted"
    GuildActivity.guildType(huntedGuild = false, alliedGuild = true) shouldBe "allied"
    GuildActivity.guildType(huntedGuild = false, alliedGuild = false) shouldBe "neutral"
  }

  test("guildType prefers hunted when both flags are set") {
    GuildActivity.guildType(huntedGuild = true, alliedGuild = true) shouldBe "hunted"
  }

  test("renameFromFormerNames matches the tracked row a character used to be stored under") {
    val activity = List(row("Bob"), row("Carol"))
    GuildActivity.renameFromFormerNames(activity, "Alice", List("Bob"), nobodyOnline) shouldBe
      Some(GuildActivity.Rename("Bob", earlier, "Nemesis"))
  }

  test("renameFromFormerNames reports the guild the row is still recorded in, not the character's current one") {
    val activity = List(row("Bob", "Nemesis"))
    GuildActivity.renameFromFormerNames(activity, "Alice", List("Bob"), nobodyOnline).map(_.guild) shouldBe Some("Nemesis")
  }

  test("renameFromFormerNames is case-insensitive on the stored name") {
    val activity = List(row("bob"))
    GuildActivity.renameFromFormerNames(activity, "Alice", List("Bob"), nobodyOnline).map(_.oldName) shouldBe Some("bob")
  }

  test("renameFromFormerNames ignores a former name somebody else is online under") {
    val activity = List(row("Bob"))
    GuildActivity.renameFromFormerNames(activity, "Alice", List("Bob"), _ == "Bob") shouldBe None
  }

  test("renameFromFormerNames ignores a former name once the character is tracked under their new one") {
    val activity = List(row("Bob"), row("Alice"))
    GuildActivity.renameFromFormerNames(activity, "Alice", List("Bob"), nobodyOnline) shouldBe None
  }

  test("renameFromFormerNames ignores a character carrying its own name in former_names") {
    val activity = List(row("Bob"))
    GuildActivity.renameFromFormerNames(activity, "Alice", List("Bob", "alice"), nobodyOnline) shouldBe None
  }

  test("renameFromFormerNames returns nothing when no former name is tracked") {
    GuildActivity.renameFromFormerNames(List(row("Carol")), "Alice", List("Bob"), nobodyOnline) shouldBe None
    GuildActivity.renameFromFormerNames(List(row("Bob")), "Alice", Nil, nobodyOnline) shouldBe None
    GuildActivity.renameFromFormerNames(List(row("Bob")), "", List("Bob"), nobodyOnline) shouldBe None
  }

  test("applyRename moves only the renamed row and leaves the rest untouched") {
    val carol = row("Carol")
    val result = GuildActivity.applyRename(List(row("Bob"), carol), "Bob", "Alice", List("Bob"), now)
    result should contain theSameElementsAs List(PlayerCache("Alice", List("Bob"), "Nemesis", now), carol)
  }

  test("applyRename leaves the recorded guild alone so the next poll still sees a swap") {
    val result = GuildActivity.applyRename(List(row("Bob", "Nemesis")), "Bob", "Alice", List("Bob"), now)
    result.map(_.guild) shouldBe List("Nemesis")
  }

  test("applyRename keeps one row per name when the new name is already present") {
    val result = GuildActivity.applyRename(List(row("Bob", "Nemesis"), row("Alice", "Vindicate")), "Bob", "Alice", List("Bob"), now)
    result shouldBe List(PlayerCache("Alice", List("Bob"), "Nemesis", now))
  }

  test("applyRename is a no-op when the old row has since gone") {
    val activity = List(row("Carol"))
    GuildActivity.applyRename(activity, "Bob", "Alice", List("Bob"), now) shouldBe activity
  }

  /* A cache-bypassed character fetch can be served different cached copies of
   * the same sheet from one poll to the next, so a rename shows up, vanishes
   * and shows up again. Moving the row is what makes the announcement one-shot,
   * which is why TibiaBot only moves it on the poll it announces on — these two
   * pin down the halves of that. */

  test("a rename held back for the settling window is still there to find next poll") {
    val activity = List(row("Bob"))
    val first = GuildActivity.renameFromFormerNames(activity, "Alice", List("Bob"), nobodyOnline)
    // Nothing was moved, so a later poll re-detects exactly the same rename —
    // against the row's original timestamp, so the window still expires on time.
    GuildActivity.renameFromFormerNames(activity, "Alice", List("Bob"), nobodyOnline) shouldBe first
    first.map(_.previousUpdate) shouldBe Some(earlier)
  }

  test("once the row is moved a flip-flopping sheet cannot announce the rename again") {
    val moved = GuildActivity.applyRename(List(row("Bob")), "Bob", "Alice", List("Bob"), now)
    // The stale copy comes back naming the character Bob, with no former names.
    GuildActivity.renameFromFormerNames(moved, "Bob", Nil, nobodyOnline) shouldBe None
    // Then the fresh copy again — the moved row is what refuses it this time.
    GuildActivity.renameFromFormerNames(moved, "Alice", List("Bob"), nobodyOnline) shouldBe None
  }
}
