package com.tibiabot.notifications

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NotifyIdsSpec extends AnyFunSuite with Matchers {

  test("every control id round-trips") {
    NotifyIds.parseControl(NotifyIds.masslogToggle(7, enable = false)) shouldBe Some(NotifyIds.MasslogToggle(7, enable = false))
    NotifyIds.parseControl(NotifyIds.masslogToggle(7, enable = true)) shouldBe Some(NotifyIds.MasslogToggle(7, enable = true))
    NotifyIds.parseControl(NotifyIds.masslogMute(7)) shouldBe Some(NotifyIds.MasslogMute(7))
    NotifyIds.parseControl(NotifyIds.masslogThreshold(7)) shouldBe Some(NotifyIds.MasslogThreshold(7))
    NotifyIds.parseControl(NotifyIds.bountyToggle(9, enable = false)) shouldBe Some(NotifyIds.BountyToggle(9, enable = false))
    NotifyIds.parseControl(NotifyIds.bountyMute(9)) shouldBe Some(NotifyIds.BountyMute(9))
  }

  test("every form id round-trips, including worlds that are only a name") {
    NotifyIds.parseForm(NotifyIds.masslogForm("Antica")) shouldBe Some(NotifyIds.MasslogForm("Antica"))
    NotifyIds.parseForm(NotifyIds.bountyForm("Antica")) shouldBe Some(NotifyIds.BountyForm("Antica"))
    NotifyIds.parseForm(NotifyIds.thresholdForm(3)) shouldBe Some(NotifyIds.ThresholdForm(3))
    NotifyIds.parseForm(NotifyIds.muteForm(3, bounty = true)) shouldBe Some(NotifyIds.MuteForm(3, bounty = true))
    NotifyIds.parseForm(NotifyIds.muteForm(3, bounty = false)) shouldBe Some(NotifyIds.MuteForm(3, bounty = false))
  }

  test("ids from another feature are left alone") {
    NotifyIds.handlesButton("galthenAdd") shouldBe false
    NotifyIds.handlesButton("respawn:claim:1") shouldBe false
    NotifyIds.handlesModal("add galthen") shouldBe false
    NotifyIds.parseControl("notify:ml:off:not-a-number") shouldBe None
    NotifyIds.parseControl("notify:xx:off:1") shouldBe None
  }

  test("the two autorole buttons keep their bare ids so posted embeds keep working") {
    NotifyIds.handlesButton("masslog") shouldBe true
    NotifyIds.handlesButton("bounty") shouldBe true
  }

  /** BotListener acknowledges a press before queueing it — unless the press
   *  answers with a modal, which Discord requires be the first response. */
  test("only the form-opening presses are exempt from the early acknowledgement") {
    NotifyIds.opensModal("masslog") shouldBe true
    NotifyIds.opensModal("bounty") shouldBe true
    NotifyIds.opensModal(NotifyIds.masslogMute(1)) shouldBe true
    NotifyIds.opensModal(NotifyIds.masslogThreshold(1)) shouldBe true
    NotifyIds.opensModal(NotifyIds.bountyMute(1)) shouldBe true
    NotifyIds.opensModal(NotifyIds.masslogToggle(1, enable = true)) shouldBe false
    NotifyIds.opensModal(NotifyIds.bountyToggle(1, enable = false)) shouldBe false
  }
}
