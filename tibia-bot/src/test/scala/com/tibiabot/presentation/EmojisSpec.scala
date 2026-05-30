package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Characterization of the vocation->emoji mapping, including the deliberate
 *  divergence between the two call sites. */
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

  test("vocEmojiWithoutMonk matches BotApp's original behaviour") {
    Emojis.vocEmojiWithoutMonk("Elite Knight") shouldBe ":shield:"
    Emojis.vocEmojiWithoutMonk("None") shouldBe ":hatching_chick:"
  }

  test("the divergence is preserved: monk maps to an emoji in one path, '' in the other") {
    Emojis.vocEmoji("Monk") shouldBe ":fist::skin-tone-3:"
    Emojis.vocEmojiWithoutMonk("Monk") shouldBe "" // BotApp predates monks
  }
}
