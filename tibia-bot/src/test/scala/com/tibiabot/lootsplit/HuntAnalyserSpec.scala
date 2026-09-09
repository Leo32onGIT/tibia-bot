package com.tibiabot.lootsplit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers

import java.time.LocalDateTime

/** Pins the analyser reader and the split it feeds against a real session copied
 *  out of the client, numbers and all. Every expected figure here was checked
 *  against what the game itself reported for the same hunt.
 */
class HuntAnalyserSpec extends AnyFunSuite with Matchers with OptionValues {

  /** A real four-member session, tabs and all — this is byte-for-byte what the
   *  client puts on the clipboard. */
  private val Paste: String =
    List(
      "Session data: From 2026-09-01, 21:12:00 to 2026-09-01, 23:29:40",
      "Session: 02:17h",
      "Loot Type: Leader",
      "Loot: 14,359,954",
      "Supplies: 5,354,392",
      "Balance: 9,005,562",
      "Boss Jeremy",
      "\tLoot: 1,654,034",
      "\tSupplies: 1,591,724",
      "\tBalance: 62,310",
      "\tDamage: 14,380,542",
      "\tHealing: 3,755,746",
      "Neutrul The Wise",
      "\tLoot: 50,000",
      "\tSupplies: 798,351",
      "\tBalance: -748,351",
      "\tDamage: 17,679,880",
      "\tHealing: 11,307,154",
      "The Wingga (Leader)",
      "\tLoot: 11,518,496",
      "\tSupplies: 1,718,946",
      "\tBalance: 9,799,550",
      "\tDamage: 10,774,182",
      "\tHealing: 5,877,463",
      "Violent Beams",
      "\tLoot: 1,137,424",
      "\tSupplies: 1,245,371",
      "\tBalance: -107,947",
      "\tDamage: 25,228,828",
      "\tHealing: 3,970,719"
    ).mkString("\n")

  /** The sentence a bad paste comes back with, or a failed test if it parsed. */
  private def refusal(text: String): String =
    HuntAnalyser.parse(text).swap.getOrElse(fail("expected a refusal, got a session"))

  private def parsed(text: String = Paste): HuntSession =
    HuntAnalyser.parse(text) match {
      case Right(session) => session
      case Left(problem)  => fail(s"expected a session, got: $problem")
    }

  // --- reading ------------------------------------------------------------

  test("reads the header block") {
    val hunt = parsed()
    hunt.from shouldBe Some(LocalDateTime.of(2026, 9, 1, 21, 12, 0))
    hunt.to shouldBe Some(LocalDateTime.of(2026, 9, 1, 23, 29, 40))
    hunt.sessionLabel shouldBe "02:17h"
    hunt.lootType shouldBe "Leader"
    hunt.loot shouldBe 14359954L
    hunt.supplies shouldBe 5354392L
    hunt.balance shouldBe 9005562L
  }

  test("reads every member, in the order the client listed them") {
    parsed().members.map(_.name) shouldBe
      List("Boss Jeremy", "Neutrul The Wise", "The Wingga", "Violent Beams")
  }

  test("a negative balance keeps its sign") {
    parsed().members.find(_.name == "Neutrul The Wise").map(_.balance) shouldBe Some(-748351L)
  }

  test("the (Leader) marker is read and then dropped from the name") {
    val leader = parsed().members.find(_.leader)
    leader.map(_.name) shouldBe Some("The Wingga")
    parsed().members.filter(_.leader) should have size 1
  }

  test("a member's own figures are read from their own block, not the header's") {
    val wingga = parsed().members.find(_.name == "The Wingga").value
    wingga.loot shouldBe 11518496L
    wingga.supplies shouldBe 1718946L
    wingga.balance shouldBe 9799550L
    wingga.damage shouldBe 10774182L
    wingga.healing shouldBe 5877463L
  }

  /** The reason the reader keys off `<known key>:` rather than the leading tab:
   *  the text travels through a clipboard and a Discord textarea to get here, and
   *  indentation is the first thing that route loses. */
  test("a paste whose indentation was eaten reads exactly the same") {
    parsed(Paste.replace("\t", "")) shouldBe parsed()
  }

  test("blank lines and trailing whitespace are ignored") {
    parsed(Paste.replace("\n", "  \n\n")) shouldBe parsed()
  }

  test("a market-priced session reads the same but says so") {
    parsed(Paste.replace("Loot Type: Leader", "Loot Type: Market")).lootType shouldBe "Market"
  }

  // --- refusing -----------------------------------------------------------

  test("anything that isn't an analyser is refused, quoting what was pasted") {
    val problem = refusal("how do i split loot")
    problem should include("Session data:")
    problem should include("how do i split loot")
  }

  test("an empty box is refused") {
    HuntAnalyser.parse("   \n  ").isLeft shouldBe true
  }

  test("a header with no Balance line is refused rather than split as zero") {
    val without = Paste.split("\n").filterNot(_ == "Balance: 9,005,562").mkString("\n")
    refusal(without) should include("Balance:")
  }

  /** Discord's paragraph box holds 4,000 characters, and a paste longer than that
   *  arrives cut off mid-block with nothing to say so. Splitting the members who
   *  did survive would hand back numbers that look right and are not. */
  test("a paste cut off mid-member is refused as cut off, naming who it stopped on") {
    val cut = Paste.substring(0, Paste.indexOf("\tBalance: -107,947"))
    val problem = refusal(cut)
    problem should include("Violent Beams")
    problem should include("4,000 characters")
  }

  test("a member missing a money line part-way up is refused as malformed") {
    val broken = Paste.split("\n").filterNot(_ == "\tBalance: 62,310").mkString("\n")
    val problem = refusal(broken)
    problem should include("Boss Jeremy")
    problem should include("without editing it")
  }

  // --- the split ----------------------------------------------------------

  test("the session runs for the header's real length, not the rounded label") {
    // 2h17m40s. The "02:17h" label rounds the 40 seconds away, and pricing the
    // loot against 2h17m00s overstates the hourly rate by about half a percent.
    parsed().durationSeconds shouldBe Some(8260L)
  }

  test("loot per hour") {
    parsed().lootPerHour shouldBe Some(6258575L)
  }

  test("individual balance floors the odd gold rather than inventing it") {
    // 9,005,562 / 4 is 2,251,390.5 exactly.
    parsed().individualBalance shouldBe 2251390L
  }

  test("damage shares, biggest first") {
    val shares = parsed().damageShares
    shares.map(_._1.name) shouldBe
      List("Violent Beams", "Neutrul The Wise", "Boss Jeremy", "The Wingga")
    shares.map(_._2).zip(List(37.0663, 25.9756, 21.1279, 15.8296)).foreach {
      case (actual, expected) => actual shouldBe (expected +- 0.001)
    }
  }

  test("healing shares, biggest first") {
    val shares = parsed().healingShares
    shares.map(_._1.name) shouldBe
      List("Neutrul The Wise", "The Wingga", "Violent Beams", "Boss Jeremy")
    shares.map(_._2).zip(List(45.3901, 23.5938, 15.9396, 15.0766)).foreach {
      case (actual, expected) => actual shouldBe (expected +- 0.001)
    }
  }

  test("nobody dealing damage produces no shares rather than a column of zeroes") {
    val quiet = parsed().copy(members = parsed().members.map(_.copy(damage = 0, healing = 0)))
    quiet.damageShares shouldBe empty
    quiet.healingShares shouldBe empty
  }

  /** The whole point of the feature: these are the three lines the leader types. */
  test("the transfers square the party up") {
    parsed().transfers shouldBe List(
      HuntTransfer("The Wingga", "Boss Jeremy", 2189080L),
      HuntTransfer("The Wingga", "Neutrul The Wise", 2999741L),
      HuntTransfer("The Wingga", "Violent Beams", 2359337L)
    )
  }

  test("a transfer reads as the command the game accepts, without separators") {
    parsed().transfers.head.command shouldBe "transfer 2189080 to Boss Jeremy"
  }

  test("everyone ends up on the individual balance, to within the floored gold") {
    val hunt = parsed()
    val moved = hunt.transfers.groupBy(_.to).view.mapValues(_.map(_.amount).sum).toMap
    hunt.members.filterNot(_.leader).foreach { member =>
      (member.balance + moved.getOrElse(member.name, 0L)) shouldBe hunt.individualBalance
    }
  }

  test("only those holding a surplus pay, and only once each") {
    parsed().transfersByPayer.map(_._1) shouldBe List("The Wingga")
  }

  test("a party already square needs no transfers") {
    val even = parsed().copy(
      balance = 400L,
      members = parsed().members.map(_.copy(balance = 100L))
    )
    even.transfers shouldBe empty
  }

  /** Two payers is rare — it needs two people to have looted more than their share
   *  — but the greedy match has to hold, and each payer has to get their own list. */
  test("two people in surplus each get their own transfers, in party order") {
    val member = (name: String, balance: Long) =>
      HuntMember(name, loot = 0, supplies = 0, balance = balance, damage = 0, healing = 0, leader = false)
    val hunt = parsed().copy(
      balance = 4000000L,
      members = List(
        member("Alpha Two", 2500000L),
        member("Beta Three", 1500000L),
        member("Gamma Four", -200000L),
        member("Delta Five", 200000L)
      )
    )
    hunt.individualBalance shouldBe 1000000L
    hunt.transfersByPayer shouldBe List(
      "Alpha Two" -> List(
        HuntTransfer("Alpha Two", "Gamma Four", 1200000L),
        HuntTransfer("Alpha Two", "Delta Five", 300000L)
      ),
      "Beta Three" -> List(
        HuntTransfer("Beta Three", "Delta Five", 500000L)
      )
    )
  }

  test("a solo session parses, and splits with nobody") {
    val solo = List(
      "Session data: From 2026-09-01, 21:12:00 to 2026-09-01, 22:12:00",
      "Session: 01:00h",
      "Loot Type: Leader",
      "Loot: 1,000,000",
      "Supplies: 400,000",
      "Balance: 600,000"
    ).mkString("\n")
    val hunt = parsed(solo)
    hunt.members shouldBe empty
    hunt.transfers shouldBe empty
    hunt.lootPerHour shouldBe Some(1000000L)
    hunt.individualBalance shouldBe 600000L
  }

  test("a header whose timestamps don't read costs the hourly rate and nothing else") {
    val hunt = parsed(Paste.replace("2026-09-01, 21:12:00", "yesterday evening"))
    hunt.from shouldBe empty
    hunt.lootPerHour shouldBe empty
    hunt.transfers should have size 3
  }
}
