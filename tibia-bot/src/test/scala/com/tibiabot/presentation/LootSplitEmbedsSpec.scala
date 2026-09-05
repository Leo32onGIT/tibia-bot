package com.tibiabot.presentation

import com.tibiabot.lootsplit.{HuntMember, HuntSession}
import net.dv8tion.jda.api.entities.MessageEmbed
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDateTime
import scala.jdk.CollectionConverters._

/** What the split actually looks like when it lands. The numbers are pinned in
 *  [[com.tibiabot.lootsplit.HuntAnalyserSpec]]; this pins how they are written and
 *  that the result fits inside a Discord embed.
 */
class LootSplitEmbedsSpec extends AnyFunSuite with Matchers with OptionValues {

  private def member(name: String, balance: Long, damage: Long, healing: Long, leader: Boolean = false) =
    HuntMember(name, loot = 0, supplies = 0, balance = balance, damage = damage, healing = healing, leader = leader)

  private val Hunt = HuntSession(
    from = Some(LocalDateTime.of(2026, 9, 1, 21, 12, 0)),
    to = Some(LocalDateTime.of(2026, 9, 1, 23, 29, 40)),
    sessionLabel = "02:17h",
    lootType = "Leader",
    loot = 14359954L,
    supplies = 5354392L,
    balance = 9005562L,
    members = List(
      member("Boss Jeremy", 62310L, 14380542L, 3755746L),
      member("Neutrul The Wise", -748351L, 17679880L, 11307154L),
      member("The Wingga", 9799550L, 10774182L, 5877463L, leader = true),
      member("Violent Beams", -107947L, 25228828L, 3970719L)
    )
  )

  /** A stand-in for the server's custom coin: Config cannot initialise in a test,
   *  which is why the emoji is an argument rather than read in there. */
  private val Gold = ":gold:"

  private def split(hunt: HuntSession): MessageEmbed = LootSplitEmbeds.session(hunt, Gold)

  private def fields(embed: MessageEmbed): List[MessageEmbed.Field] = embed.getFields.asScala.toList

  private def field(embed: MessageEmbed, name: String): MessageEmbed.Field =
    fields(embed).find(_.getName == name).getOrElse(fail(s"no '$name' field in $embed"))

  test("the title counts the party") {
    split(Hunt).getTitle shouldBe "Party Hunt Session – 4 members"
  }

  test("the headline carries the balance, the split and the hourly rate, with separators") {
    val description = split(Hunt).getDescription
    description should include("**Balance:** 9,005,562")
    description should include("**Individual balance:** 2,251,390")
    description should include("**Loot per hour:** 6,258,575")
  }

  test("damage and healing sit side by side, biggest share first") {
    val embed = split(Hunt)
    val damage = field(embed, "Damage")
    val healing = field(embed, "Healing")
    damage.isInline shouldBe true
    healing.isInline shouldBe true
    damage.getValue.linesIterator.toList shouldBe List(
      "‣ Violent Beams (37.07%)",
      "‣ Neutrul The Wise (25.98%)",
      "‣ Boss Jeremy (21.13%)",
      "‣ The Wingga (15.83%)"
    )
    healing.getValue.linesIterator.toList shouldBe List(
      "‣ Neutrul The Wise (45.39%)",
      "‣ The Wingga (23.59%)",
      "‣ Violent Beams (15.94%)",
      "‣ Boss Jeremy (15.08%)"
    )
  }

  /** One block per transfer rather than one block holding all of them: a phone
   *  copies a code block with a tap, and three lines in one block is one copy of
   *  three commands. */
  test("each transfer is its own copyable code block, in party order") {
    val transfers = field(split(Hunt), "Transfers for The Wingga")
    transfers.isInline shouldBe false
    transfers.getValue shouldBe
      "```\ntransfer 2189080 to Boss Jeremy\n```\n" +
      "```\ntransfer 2999741 to Neutrul The Wise\n```\n" +
      "```\ntransfer 2359337 to Violent Beams\n```"
  }

  test("the footer says how long the hunt ran and when it started") {
    split(Hunt).getFooter.getText shouldBe "02:17h hunt on 2026-09-01T21:12"
  }

  test("a party that is already square says so rather than showing an empty field") {
    val square = Hunt.copy(balance = 400L, members = Hunt.members.map(_.copy(balance = 100L)))
    field(split(square), "Transfers").getValue should include("already square")
  }

  test("a solo session drops the individual balance and the transfers entirely") {
    val solo = Hunt.copy(members = Nil)
    val embed = split(solo)
    embed.getTitle shouldBe "Hunt Session"
    embed.getDescription should not include "Individual balance"
    fields(embed) shouldBe empty
  }

  test("a session with no damage or healing leaves those columns off") {
    val quiet = Hunt.copy(members = Hunt.members.map(_.copy(damage = 0, healing = 0)))
    fields(split(quiet)).map(_.getName) shouldBe List("Transfers for The Wingga")
  }

  test("a header with no readable timestamps loses the hourly rate, not the split") {
    val embed = split(Hunt.copy(from = None, to = None))
    embed.getDescription should not include "Loot per hour"
    embed.getDescription should include("**Individual balance:** 2,251,390")
    embed.getFooter.getText shouldBe "02:17h hunt"
  }

  /** Not reachable from the game — a Tibia party is far smaller — but a field
   *  Discord rejects fails the whole reply rather than that one column, so the
   *  bounds are held rather than assumed. */
  test("an implausibly large party still fits inside Discord's limits") {
    // Thirty people each owing a little into ten deep pockets: thirty payers, so
    // thirty "Transfers for" fields want drawing where Discord allows 25 in total
    // and 6,000 characters across them.
    val crowd = (1 to 40).map(index =>
      member(s"Party Member Number $index", balance = if (index <= 30) 1000L else -3000L,
        damage = index * 1000L, healing = index * 500L)).toList
    val embed = split(Hunt.copy(balance = 0L, members = crowd))
    fields(embed).size should be <= MessageEmbed.MAX_FIELD_AMOUNT
    fields(embed).foreach(_.getValue.length should be <= MessageEmbed.VALUE_MAX_LENGTH)
    embed.getLength should be <= MessageEmbed.EMBED_MAX_LENGTH_BOT
  }

  test("payers whose transfers had to be dropped are named rather than lost") {
    val crowd = (1 to 40).map(index =>
      member(s"Party Member Number $index", balance = if (index <= 30) 1000L else -3000L,
        damage = 1L, healing = 1L)).toList
    val embed = split(Hunt.copy(balance = 0L, members = crowd))
    field(embed, "Transfers").getValue should include("Still to send")
  }

  test("a column that had to be cut says how much it left out") {
    val crowd = (1 to 40).map(index =>
      member(s"Party Member Number $index", balance = 0L, damage = index * 1000L, healing = 1L)).toList
    field(split(Hunt.copy(members = crowd)), "Damage").getValue should include("more")
  }
}
