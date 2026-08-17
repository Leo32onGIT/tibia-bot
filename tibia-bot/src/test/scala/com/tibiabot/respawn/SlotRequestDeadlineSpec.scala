package com.tibiabot.respawn

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.ZonedDateTime

/** How long the owner of a booked slot has to say whether they are hunting it.
 *
 *  The question is "are you hunting this tonight", and only the evening can
 *  answer it — so the clock runs to the slot rather than from the asking.
 */
class SlotRequestDeadlineSpec extends AnyWordSpec with Matchers {

  private val slotStart = ZonedDateTime.parse("2026-08-13T20:00:00Z")
  private val grace = 10

  "the deadline to answer" should {

    "land a little way into the slot itself" in {
      RespawnService.answerDeadline(slotStart, grace) shouldBe slotStart.plusMinutes(10)
    }

    // The change. It used to be an hour from the question, cut short if that ran
    // past the slot — so somebody asked at lunchtime about an evening slot lost
    // it by mid-afternoon, having never been near the hunt they were being asked
    // about.
    "give a question put hours early the whole evening to be answered" in {
      val askedAt = slotStart.minusHours(6)
      val deadline = RespawnService.answerDeadline(slotStart, grace)
      deadline.isAfter(askedAt.plusHours(1)) shouldBe true
      deadline shouldBe slotStart.plusMinutes(10)
    }

    // Landing exactly on the start would take the slot off somebody logging in
    // on time, which is the opposite of what the question was asking.
    "leave room to arrive on time" in {
      RespawnService.answerDeadline(slotStart, grace).isAfter(slotStart) shouldBe true
    }

    "follow the slot when the slot is the thing that moves" in {
      RespawnService.answerDeadline(slotStart.plusDays(1), grace) shouldBe
        slotStart.plusDays(1).plusMinutes(10)
    }

    // A guild that wants the answer settled the moment the hunt begins can say
    // so; the deadline is still the slot's own start and not the asking.
    "honour a grace of nothing at all" in {
      RespawnService.answerDeadline(slotStart, 0) shouldBe slotStart
    }
  }
}
