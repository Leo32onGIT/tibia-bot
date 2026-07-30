package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PermissionsSpec extends AnyFunSuite with Matchers {

  test("isBotCreator only matches the exact application-owner id") {
    Permissions.isBotCreator("123", "123") shouldBe true
    Permissions.isBotCreator("123", "456") shouldBe false
  }

  test("isBotCreator denies everyone when the owner id is unknown (empty)") {
    Permissions.isBotCreator("123", "") shouldBe false
    Permissions.isBotCreator("", "") shouldBe false
  }

  private val moderatorRole = "1500000000000000001"

  test("Manage Server alone still grants access, with or without the role") {
    // The role is additive: nobody who could use these commands before it existed
    // may lose access because of it.
    Permissions.grantsAccess(hasManageServer = true, Set.empty, moderatorRole) shouldBe true
    Permissions.grantsAccess(hasManageServer = true, Set(moderatorRole), moderatorRole) shouldBe true
    Permissions.grantsAccess(hasManageServer = true, Set.empty, "0") shouldBe true
  }

  test("the moderator role alone grants access") {
    Permissions.grantsAccess(hasManageServer = false, Set(moderatorRole), moderatorRole) shouldBe true
  }

  test("holding some other role grants nothing") {
    Permissions.grantsAccess(hasManageServer = false, Set("999", "1000"), moderatorRole) shouldBe false
    Permissions.grantsAccess(hasManageServer = false, Set.empty, moderatorRole) shouldBe false
  }

  test("an unset role id falls back to Manage Server rather than matching anything") {
    // "0" is what a guild that never ran /setup has stored. If that were treated
    // as a real role id the check would be meaningless the moment a role happened
    // to carry it.
    Permissions.grantsAccess(hasManageServer = false, Set("0"), "0") shouldBe false
    Permissions.grantsAccess(hasManageServer = false, Set(""), "") shouldBe false
    Permissions.grantsAccess(hasManageServer = false, Set("0", "999"), "") shouldBe false
  }

  test("the moderator role's name is fixed, since /setup adopts it by name") {
    // getOrCreateRole reuses an existing role of this name, so changing the string
    // would silently create a second, empty role in every guild.
    Permissions.ModeratorRoleName shouldBe "Violent Bot Moderator"
  }
}
