package com.tibiabot.notifications

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NotifyIdsSpec extends AnyFunSuite with Matchers {

  test("every control id round-trips") {
    NotifyIds.parseControl(NotifyIds.masslogToggle(7, enable = false)) shouldBe Some(NotifyIds.MasslogToggle(7, enable = false))
    NotifyIds.parseControl(NotifyIds.masslogToggle(7, enable = true)) shouldBe Some(NotifyIds.MasslogToggle(7, enable = true))
    NotifyIds.parseControl(NotifyIds.masslogMute(7)) shouldBe Some(NotifyIds.MasslogMute(7))
    NotifyIds.parseControl(NotifyIds.masslogThreshold(7)) shouldBe Some(NotifyIds.MasslogThreshold(7))
    NotifyIds.parseControl(NotifyIds.masslogDrop(7)) shouldBe Some(NotifyIds.MasslogDrop(7))
    NotifyIds.parseControl(NotifyIds.masslogAgain("1234", "Antica", 5)) shouldBe
      Some(NotifyIds.MasslogAgain("1234", "Antica", 5))
    NotifyIds.parseControl(NotifyIds.bountyToggle(9, enable = false)) shouldBe Some(NotifyIds.BountyToggle(9, enable = false))
    NotifyIds.parseControl(NotifyIds.bountyMute(9)) shouldBe Some(NotifyIds.BountyMute(9))
    NotifyIds.parseControl(NotifyIds.bountyDrop(9)) shouldBe Some(NotifyIds.BountyDrop(9))
    NotifyIds.parseControl(NotifyIds.bountyTrackAgain("1234", "Antica", "Bubble", 10)) shouldBe
      Some(NotifyIds.BountyTrackAgain("1234", "Antica", "Bubble", 10))
    NotifyIds.parseControl(NotifyIds.bountyAdd("Antica")) shouldBe Some(NotifyIds.BountyAdd("Antica"))
    NotifyIds.parseControl(NotifyIds.bountyRemove("Antica")) shouldBe Some(NotifyIds.BountyRemove("Antica"))
  }

  test("every form id round-trips, including worlds that are only a name") {
    NotifyIds.parseForm(NotifyIds.masslogForm("Antica")) shouldBe Some(NotifyIds.MasslogForm("Antica"))
    NotifyIds.parseForm(NotifyIds.bountyForm("Antica")) shouldBe Some(NotifyIds.BountyForm("Antica"))
    NotifyIds.parseForm(NotifyIds.thresholdForm(3)) shouldBe Some(NotifyIds.ThresholdForm(3))
    NotifyIds.parseForm(NotifyIds.muteForm(3, bounty = true)) shouldBe Some(NotifyIds.MuteForm(3, bounty = true))
    NotifyIds.parseForm(NotifyIds.muteForm(3, bounty = false)) shouldBe Some(NotifyIds.MuteForm(3, bounty = false))
    NotifyIds.parseForm(NotifyIds.removeForm("Antica")) shouldBe Some(NotifyIds.RemoveForm("Antica"))
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
    NotifyIds.opensModal(NotifyIds.masslogMute(1)) shouldBe true
    NotifyIds.opensModal(NotifyIds.masslogThreshold(1)) shouldBe true
    NotifyIds.opensModal(NotifyIds.bountyMute(1)) shouldBe true
    NotifyIds.opensModal(NotifyIds.bountyAdd("Antica")) shouldBe true
    NotifyIds.opensModal(NotifyIds.bountyRemove("Antica")) shouldBe true
    NotifyIds.opensModal(NotifyIds.masslogToggle(1, enable = true)) shouldBe false
    NotifyIds.opensModal(NotifyIds.bountyToggle(1, enable = false)) shouldBe false
    NotifyIds.opensModal(NotifyIds.bountyDrop(1)) shouldBe false
    NotifyIds.opensModal(NotifyIds.bountyTrackAgain("1", "Antica", "Bubble", 10)) shouldBe false
  }

  /** Track again carries the whole subscription rather than a key, so the parts
   *  that make a character name have to survive the round trip — and the id has
   *  to stay inside what Discord will take. */
  test("track again carries a name with spaces, and stays under the id cap") {
    val id = NotifyIds.bountyTrackAgain("123456789012345678", "Antica", "Eternal Oblivion", 1440)
    NotifyIds.parseControl(id) shouldBe Some(NotifyIds.BountyTrackAgain("123456789012345678", "Antica", "Eternal Oblivion", 1440))
    id.length should be <= NotifyIds.MaxCustomId

    // The longest anything real can be: a 20-digit guild, a long world and a
    // name at Tibia's own 29-character limit.
    val longest = NotifyIds.bountyTrackAgain("1" * 20, "Wintera", "a" * 29, 1440)
    longest.length should be <= NotifyIds.MaxCustomId
  }

  /** The picker's world and the alert's row id are two different removals, and
   *  a world named like a number must not be read as one. */
  test("removing from the panel and removing from a DM don't parse as each other") {
    NotifyIds.parseControl(NotifyIds.bountyRemove("7")) shouldBe Some(NotifyIds.BountyRemove("7"))
    NotifyIds.parseControl(NotifyIds.bountyDrop(7)) shouldBe Some(NotifyIds.BountyDrop(7))
  }

  /** The Bounty button opens the panel rather than the add form now, so it is
   *  acknowledged like every other press — the add form it used to open is
   *  reached from a button on that panel instead. */
  test("the bounty button answers with a panel, not a form") {
    NotifyIds.opensModal("bounty") shouldBe false
  }

  /** Removing a mass-log subscription and switching one off are different
   *  answers, and the DMs already in people's inboxes still carry the old one —
   *  so both have to keep parsing, as themselves. */
  test("mass-log remove and the older disable do not parse as each other") {
    NotifyIds.parseControl(NotifyIds.masslogDrop(7)) shouldBe Some(NotifyIds.MasslogDrop(7))
    NotifyIds.parseControl(NotifyIds.masslogToggle(7, enable = false)) shouldBe
      Some(NotifyIds.MasslogToggle(7, enable = false))
  }

  test("turning mass log back on carries its threshold and stays under the id cap") {
    // The threshold is the whole content of the subscription, so a Remove that
    // lost it would be destructive in a way one press should not be.
    NotifyIds.parseControl(NotifyIds.masslogAgain("1234", "Antica", 12)) shouldBe
      Some(NotifyIds.MasslogAgain("1234", "Antica", 12))
    val longest = NotifyIds.masslogAgain("1" * 20, "Wintera", 999)
    longest.length should be <= NotifyIds.MaxCustomId
  }

  /** Both edit the message they were pressed on, so neither may skip the early
   *  acknowledgement the way a form-opening press must. */
  test("neither mass-log button opens a modal") {
    NotifyIds.opensModal(NotifyIds.masslogDrop(7)) shouldBe false
    NotifyIds.opensModal(NotifyIds.masslogAgain("1234", "Antica", 5)) shouldBe false
  }
}
