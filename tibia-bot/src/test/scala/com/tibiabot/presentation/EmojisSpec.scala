package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Characterization of the unified vocation->emoji mapping. */
class EmojisSpec extends AnyFunSuite with Matchers {

  test("vocEmoji matches the promoted vocation by its last word") {
    Emojis.vocEmoji("Elite Knight") shouldBe ":shield:"
    Emojis.vocEmoji("Elder Druid") shouldBe ":snowflake:"
    Emojis.vocEmoji("Master Sorcerer") shouldBe ":fire:"
    Emojis.vocEmoji("Royal Paladin") shouldBe ":bow_and_arrow:"
    Emojis.vocEmoji("Exalted Monk") shouldBe ":fist::skin-tone-3:"
    Emojis.vocEmoji("None") shouldBe ":hatching_chick:"
    Emojis.vocEmoji("") shouldBe ""
  }

  test("monk now resolves everywhere (the BotApp-without-monk variant is gone)") {
    Emojis.vocEmoji("Monk") shouldBe ":fist::skin-tone-3:"
  }

  test("every grouped vocation has an emoji (guards against the monk-blank desync)") {
    // The monk bug was a display-order vocation with no emoji. Pin that every
    // vocation we group/sort by (domain.Vocations.displayOrder) renders something.
    com.tibiabot.domain.Vocations.displayOrder.foreach { voc =>
      withClue(s"vocation '$voc' must map to a non-empty emoji: ") {
        Emojis.vocEmoji(voc) should not be empty
      }
    }
  }
}
