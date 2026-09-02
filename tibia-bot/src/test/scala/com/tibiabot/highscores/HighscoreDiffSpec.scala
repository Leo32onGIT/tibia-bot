package com.tibiabot.highscores

import com.tibiabot.domain.HighscoreRecord
import com.tibiabot.tibiadata.HighscoreCategory
import com.tibiabot.tibiadata.response.HighscoreEntry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

/** The advance rules. Every one of them exists to keep an artefact of a list
 *  only a thousand deep from reading as something a player did. */
class HighscoreDiffSpec extends AnyFunSuite with Matchers {

  private val snapshot = Instant.parse("2026-09-02T05:40:00Z")

  private def entry(name: String, value: Long, level: Int = 400, vocation: String = "Elite Knight") =
    HighscoreEntry(rank = 1, name = name, vocation = vocation, world = "Antica", level = level, value = value)

  private def stored(name: String, score: Long, seen: Instant = snapshot.minus(Duration.ofHours(1))) =
    HighscoreRecord(HighscoreDiff.key(name), name, "Elite Knight", 400, score, seen)

  test("a higher score against a fresh baseline is an advance") {
    HighscoreDiff.classify(Some(stored("Bubble", 115)), entry("Bubble", 116), snapshot) shouldBe
      HighscoreChange.Advanced(115, 116)
  }

  test("the same score is unchanged, which is the common case") {
    // Only a handful of a thousand move in any hour; everything else lands here.
    HighscoreDiff.classify(Some(stored("Bubble", 116)), entry("Bubble", 116), snapshot) shouldBe
      HighscoreChange.Unchanged
  }

  test("a character with no stored score records but never posts") {
    // Otherwise a cold start announces the entire top thousand of every list.
    HighscoreDiff.classify(None, entry("Bubble", 116), snapshot) shouldBe HighscoreChange.FirstSighting
    HighscoreChange.FirstSighting.isAdvance shouldBe false
  }

  test("a score that dropped is a decline, not an advance") {
    HighscoreDiff.classify(Some(stored("Bubble", 116)), entry("Bubble", 115), snapshot) shouldBe
      HighscoreChange.Declined(116, 115)
  }

  test("a baseline older than the window re-baselines in silence") {
    // The character fell out of the top thousand and has come back several
    // levels up. That is not one event, so it is taken as the new baseline.
    val old = stored("Bubble", 110, seen = snapshot.minus(Duration.ofDays(4)))
    HighscoreDiff.classify(Some(old), entry("Bubble", 118), snapshot) shouldBe
      HighscoreChange.Rebaselined(110, 118)
  }

  test("an ordinary gap between snapshots still counts as a fresh baseline") {
    // A restart, a few failed fetches, or simply the hour between snapshots must
    // never cost a real advance.
    val yesterday = stored("Bubble", 115, seen = snapshot.minus(Duration.ofDays(2)))
    HighscoreDiff.classify(Some(yesterday), entry("Bubble", 116), snapshot).isAdvance shouldBe true
  }

  test("a decline from a stale baseline is still only a decline") {
    val old = stored("Bubble", 116, seen = snapshot.minus(Duration.ofDays(9)))
    HighscoreDiff.classify(Some(old), entry("Bubble", 110), snapshot) shouldBe
      HighscoreChange.Declined(116, 110)
  }

  test("only an advance is announced") {
    val changes = List(
      HighscoreChange.FirstSighting,
      HighscoreChange.Unchanged,
      HighscoreChange.Declined(2, 1),
      HighscoreChange.Rebaselined(1, 9),
      HighscoreChange.Advanced(1, 2)
    )
    changes.filter(_.isAdvance) shouldBe List(HighscoreChange.Advanced(1, 2))
  }

  test("names are keyed case-insensitively, as Tibia treats them") {
    val previous = Map(HighscoreDiff.key("Bubble") -> stored("Bubble", 115))
    val events = HighscoreDiff.advances(
      "Antica", HighscoreCategory.SwordFighting, posts = true, previous,
      List(entry("bubble", 116)), snapshot)
    events.map(_.previousScore) shouldBe List(115)
    // The key is folded, but the post has to render the casing tibia.com shows.
    events.map(_.displayName) shouldBe List("bubble")
    events.map(_.name) shouldBe List("bubble")
  }

  test("a rename reads as a first sighting, so it needs no handling of its own") {
    val previous = Map(HighscoreDiff.key("Old Name") -> stored("Old Name", 115))
    HighscoreDiff.advances(
      "Antica", HighscoreCategory.SwordFighting, posts = true, previous,
      List(entry("New Name", 116)), snapshot) shouldBe empty
  }

  test("advances keep rank order and carry the level the gate will need") {
    val previous = Map(
      HighscoreDiff.key("First") -> stored("First", 130),
      HighscoreDiff.key("Second") -> stored("Second", 120),
      HighscoreDiff.key("Third") -> stored("Third", 110)
    )
    val events = HighscoreDiff.advances(
      "Antica", HighscoreCategory.MagicLevel, posts = true, previous,
      List(entry("First", 131, level = 900), entry("Second", 120), entry("Third", 111, level = 300)),
      snapshot)

    events.map(_.displayName) shouldBe List("First", "Third")
    events.map(_.level) shouldBe List(900, 300)
    events.map(_.category).distinct shouldBe List("magiclevel")
    events.map(_.observed).distinct shouldBe List(snapshot)
  }

  test("a list that does not post yields no events even when scores moved") {
    // Experience. Belt and braces: the caller cannot announce a level-up by
    // forgetting to check, because there is nothing to announce.
    val previous = Map(HighscoreDiff.key("Bubble") -> stored("Bubble", 1000000L))
    HighscoreDiff.advances(
      "Antica", HighscoreCategory.Experience, posts = false, previous,
      List(entry("Bubble", 2000000L)), snapshot) shouldBe empty
  }

  test("the baseline window is longer than a snapshot gap and shorter than a comeback") {
    HighscoreDiff.MaxBaselineAge.toHours should be > 24L
    HighscoreDiff.MaxBaselineAge.toDays should be < 7L
  }
}
