package com.tibiabot.interactions

import net.dv8tion.jda.api.components.label.Label
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Covers the length clamping on modal labels.
 *
 *  Worth its own spec because Discord rejects an over-long label by failing the
 *  whole interaction, not by trimming the text — so the only symptom is a modal
 *  that never opens. That is exactly what happened when the holder's Discord
 *  username was interpolated into a label: a 47-character name blew the
 *  45-character limit and the Hunt duration button threw instead of opening.
 */
class RespawnModalsSpec extends AnyFunSuite with Matchers {

  test("text within the limit is left exactly as it is") {
    RespawnModals.clamp("Total hunt length (minutes)", Label.LABEL_MAX_LENGTH) shouldBe
      "Total hunt length (minutes)"
  }

  test("over-long text is cut to the limit rather than rejected") {
    val long = "Total hunt length for bwils120hotmail.com_98438 (minutes)"
    long.length should be > Label.LABEL_MAX_LENGTH // the real case that crashed
    val clamped = RespawnModals.clamp(long, Label.LABEL_MAX_LENGTH)
    clamped.length should be <= Label.LABEL_MAX_LENGTH
    clamped should endWith("…")
  }

  test("a pathological username can't push a label or description over its limit") {
    // Discord allows 32-character usernames and spawn names are guild-editable,
    // so neither is bounded by anything the bot controls.
    val name = "a" * 200
    RespawnModals.clamp(s"held by $name", Label.LABEL_MAX_LENGTH).length should
      be <= Label.LABEL_MAX_LENGTH
    RespawnModals.clamp(s"1220b — Nimmersatt's Breeding Ground -7, held by $name. 5 to 240.",
      Label.DESCRIPTION_MAX_LENGTH).length should be <= Label.DESCRIPTION_MAX_LENGTH
  }

  test("clamping is exact at the boundary") {
    val exact = "x" * Label.LABEL_MAX_LENGTH
    RespawnModals.clamp(exact, Label.LABEL_MAX_LENGTH) shouldBe exact
    RespawnModals.clamp(exact + "y", Label.LABEL_MAX_LENGTH).length shouldBe Label.LABEL_MAX_LENGTH
  }
}
