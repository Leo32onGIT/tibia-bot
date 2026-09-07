package com.tibiabot.hunted

import com.tibiabot.domain.Players
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The rule behind renaming a listed player, kept apart from the poll that runs
 *  it so it can be exercised without JDA or a database.
 *
 *  A rename used to reach the hunted list only through the activity records, and
 *  those exist only for characters who joined a *tracked guild*. A player added
 *  to the list on their own therefore kept the name they were added under
 *  forever — matching no live character, colouring no death, appearing in no
 *  online list, and looking for all the world like it still worked.
 */
class ListRenameSpec extends AnyFunSuite with Matchers {

  /** What TibiaBot.renameListEntries decides, in isolation: which listed entry —
   *  if any — a character's former names point at. */
  private def entryToRename(listed: List[Players], formerNames: List[String]): Option[String] = {
    val wasCalled = formerNames.map(_.toLowerCase).toSet
    listed.find(player => wasCalled.contains(player.name.toLowerCase)).map(_.name)
  }

  private def listOf(names: String*): List[Players] =
    names.toList.map(name => Players(name, "false", "none", "someone"))

  test("a former name matching a listed entry is what gets renamed") {
    entryToRename(listOf("bubble", "charm"), List("Bubble")) shouldBe Some("bubble")
  }

  test("matching ignores case, since entries are stored lowercased") {
    entryToRename(listOf("bubble"), List("BUBBLE")) shouldBe Some("bubble")
    entryToRename(listOf("Bubble"), List("bubble")) shouldBe Some("Bubble")
  }

  test("a character with no former names renames nothing") {
    entryToRename(listOf("bubble"), Nil) shouldBe None
  }

  test("former names nobody lists rename nothing") {
    entryToRename(listOf("bubble"), List("Someone Else")) shouldBe None
  }

  /** The character sheet carries every previous name, not just the last one, so
   *  a player renamed twice while nobody was looking is still found. */
  test("any former name matches, not only the most recent") {
    entryToRename(listOf("original"), List("Middle Name", "Original")) shouldBe Some("original")
  }
  /** Idempotence is what lets this run every poll with no debounce: once the
   *  entry carries the new name, the former names stop matching it. */
  test("re-running after the rename finds nothing to do") {
    entryToRename(listOf("newname"), List("OldName")) shouldBe None
  }

  test("only one entry is renamed even if several former names are listed") {
    // Would mean the same person listed twice, which adding already refuses.
    entryToRename(listOf("first", "second"), List("First", "Second")) shouldBe Some("first")
  }

  /** The guard that decides whether a scan writes the cache at all.
   *
   *  Held in memory rather than read back from the row, so a listed player whose
   *  sheet has not moved costs nothing per poll. It covers world and guild as
   *  well as level, because a transfer or a guild swap changes what the list
   *  draws just as much as levelling does.
   */
  private type Fingerprint = (Int, String, String)

  private def wouldWrite(known: Map[String, Fingerprint], name: String, fp: Fingerprint): Boolean =
    !known.get(name.toLowerCase).contains(fp)

  test("an unchanged sheet is not written again") {
    val known = Map("bubble" -> ((200, "Antica", "Some Guild")))
    wouldWrite(known, "Bubble", (200, "Antica", "Some Guild")) shouldBe false
  }

  test("a level change is written") {
    val known = Map("bubble" -> ((200, "Antica", "Some Guild")))
    wouldWrite(known, "Bubble", (201, "Antica", "Some Guild")) shouldBe true
  }

  test("a world transfer is written, even at the same level") {
    val known = Map("bubble" -> ((200, "Antica", "Some Guild")))
    wouldWrite(known, "Bubble", (200, "Belobra", "Some Guild")) shouldBe true
  }

  test("a guild change is written, even at the same level and world") {
    val known = Map("bubble" -> ((200, "Antica", "Some Guild")))
    wouldWrite(known, "Bubble", (200, "Antica", "Another Guild")) shouldBe true
  }

  /** The map starts empty after a restart, so the first scan of every listed
   *  player refreshes their row — which is what makes a restart repair anything
   *  that drifted while the process was down. */
  test("the first scan after a restart always writes") {
    wouldWrite(Map.empty, "Bubble", (200, "Antica", "")) shouldBe true
  }
}
