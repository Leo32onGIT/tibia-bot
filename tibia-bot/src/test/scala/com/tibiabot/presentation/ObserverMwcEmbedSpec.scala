package com.tibiabot.presentation

import com.tibiabot.domain.MiniWorldChange
import net.dv8tion.jda.api.EmbedBuilder
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The Mini World Changes block in the server-save notifications message. The
 *  emoji are passed in so these run without loading Config. */
class ObserverMwcEmbedSpec extends AnyFunSuite with Matchers {

  private val emoji = "<:raid:1>"
  private val leadEmoji = "<:mwc:2>"
  private def change(title: String, body: String = "Somewhere in Tibia.") = MiniWorldChange("Antica", title, body)
  private def block(changes: List[MiniWorldChange]) = ObserverEmbeds.serverSaveMwcEmbed("Antica", changes, emoji, leadEmoji)

  test("is left out when nothing is active") {
    block(Nil) shouldBe None
  }

  test("opens on the world behind its emoji, then lists each change as its linked name with a grey line under it") {
    val e = block(List(
      change("Fury Gate", "The Fury Gate has opened near Venore."),
      change("Nomads", "Nomads have set up camp.")
    )).get
    // Joined explicitly rather than a multi-line literal, whose line endings follow
    // the checkout's (CRLF on Windows) while the embed's are always \n.
    e.getDescription shouldBe List(
      "### <:mwc:2> Mini World Changes for **Antica**",
      "### <:raid:1> **[Fury Gate](https://tibia.fandom.com/wiki/Fury_Gates_Mini_World_Change)**",
      "-# The Fury Gate has opened near Venore.",
      "### <:raid:1> **[Nomads](https://tibia.fandom.com/wiki/Nomads_Mini_World_Change)**",
      "-# Nomads have set up camp.").mkString("\n")
    (e.getColor.getRGB & 0xFFFFFF) shouldBe Embeds.BrandColor
    e.getTitle shouldBe null
  }

  /** No picture, so on the card the text has the whole width. */
  test("carries no picture") {
    block(List(change("Warpath"))).get.getThumbnail shouldBe null
  }

  test("opens the same way for a single change") {
    block(List(change("Warpath"))).get.getDescription should startWith("### <:mwc:2> Mini World Changes for **Antica**\n")
  }

  test("keeps a multi-line description on the one grey line, and drops an empty one") {
    val e = block(List(
      change("Fury Gate", "  The gate is open.\n\nNear Venore.  "),
      change("Nomads", "   ")
    )).get
    e.getDescription.linesIterator.toList.drop(1) shouldBe List(
      "### <:raid:1> **[Fury Gate](https://tibia.fandom.com/wiki/Fury_Gates_Mini_World_Change)**",
      "-# The gate is open. Near Venore.",
      "### <:raid:1> **[Nomads](https://tibia.fandom.com/wiki/Nomads_Mini_World_Change)**")
  }

  private def plain(text: String) = new EmbedBuilder().setDescription(text).build()

  test("a posted message's boosted boss and creature are found wherever its changes sit") {
    val mwc = block(List(change("Warpath"))).get
    val (boss, creature, rashid, dream) = (plain("boss"), plain("creature"), plain("rashid"), plain("dream"))
    ObserverEmbeds.isServerSaveMwcEmbed(mwc) shouldBe true
    ObserverEmbeds.isServerSaveMwcEmbed(rashid) shouldBe false
    // Changes first (since 24 Sep 2026), after the Dream Courts (before), or none at all.
    ObserverEmbeds.boostedEmbedsOf(List(mwc, boss, creature, rashid, dream)) shouldBe List(boss, creature)
    ObserverEmbeds.boostedEmbedsOf(List(boss, creature, rashid, dream, mwc)) shouldBe List(boss, creature)
    ObserverEmbeds.boostedEmbedsOf(List(boss, creature, rashid, dream)) shouldBe List(boss, creature)
  }

  /** Messages posted before 25 Sep 2026 have the old wording, and the picture. */
  test("still recognises the block in a message posted before it lost its picture") {
    val old = new EmbedBuilder().setDescription("The mini world changes for **Antica** are:\n### <:raid:1> **[Warpath](x)**")
      .setThumbnail("https://violentbot.xyz/discord/observer/miniworldchange.png").build()
    val (boss, creature) = (plain("boss"), plain("creature"))
    ObserverEmbeds.isServerSaveMwcEmbed(old) shouldBe true
    ObserverEmbeds.boostedEmbedsOf(List(old, boss, creature)) shouldBe List(boss, creature)
  }

  test("drops whole changes rather than cutting one off when the description runs long") {
    val many = (1 to 40).toList.map(i => change(s"Change $i", "x" * 150))
    val d = block(many).get.getDescription
    d.length should be <= ObserverEmbeds.MaxMwcDescription
    // The lead is a ### header too, so it is left out of the count.
    val names = d.linesIterator.drop(1).count(_.startsWith("### "))
    names should (be > 0 and be < 40)
    d.linesIterator.count(_.startsWith("-# ")) shouldBe names
    d should endWith("x" * 150)
  }
}
