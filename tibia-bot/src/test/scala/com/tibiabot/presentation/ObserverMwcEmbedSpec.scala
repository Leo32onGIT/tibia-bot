package com.tibiabot.presentation

import com.tibiabot.domain.MiniWorldChange
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The Mini World Changes embed in the server-save notifications message. The
 *  emoji is passed in so these run without loading Config. */
class ObserverMwcEmbedSpec extends AnyFunSuite with Matchers {

  private val emoji = "<:raid:1>"
  private def change(title: String, body: String = "Somewhere in Tibia.") = MiniWorldChange("Antica", title, body)

  test("is left out when nothing is active") {
    ObserverEmbeds.serverSaveMwcEmbed("Antica", Nil, emoji) shouldBe None
  }

  test("lists each change as its linked name with the description as a grey line under it") {
    val e = ObserverEmbeds.serverSaveMwcEmbed("Antica", List(
      change("Fury Gate", "The Fury Gate has opened near Venore."),
      change("Nomads", "Nomads have set up camp.")
    ), emoji).get
    e.getDescription shouldBe
      """The mini world changes for **Antica** are:
        |### <:raid:1> **[Fury Gate](https://tibia.fandom.com/wiki/Fury_Gates_Mini_World_Change)**
        |-# The Fury Gate has opened near Venore.
        |### <:raid:1> **[Nomads](https://tibia.fandom.com/wiki/Nomads_Mini_World_Change)**
        |-# Nomads have set up camp.""".stripMargin
    e.getThumbnail.getUrl shouldBe "https://violentbot.xyz/discord/observer/miniworldchange.png"
    (e.getColor.getRGB & 0xFFFFFF) shouldBe Embeds.BrandColor
    e.getTitle shouldBe null
  }

  test("says 'change ... is' for a single change") {
    val e = ObserverEmbeds.serverSaveMwcEmbed("Antica", List(change("Warpath")), emoji).get
    e.getDescription should startWith("The mini world change for **Antica** is:\n")
  }

  test("keeps a multi-line description on the one grey line, and drops an empty one") {
    val e = ObserverEmbeds.serverSaveMwcEmbed("Antica", List(
      change("Fury Gate", "  The gate is open.\n\nNear Venore.  "),
      change("Nomads", "   ")
    ), emoji).get
    e.getDescription.linesIterator.toList.drop(1) shouldBe List(
      "### <:raid:1> **[Fury Gate](https://tibia.fandom.com/wiki/Fury_Gates_Mini_World_Change)**",
      "-# The gate is open. Near Venore.",
      "### <:raid:1> **[Nomads](https://tibia.fandom.com/wiki/Nomads_Mini_World_Change)**")
  }

  test("drops whole changes rather than cutting one off when the description runs long") {
    val many = (1 to 40).toList.map(i => change(s"Change $i", "x" * 150))
    val d = ObserverEmbeds.serverSaveMwcEmbed("Antica", many, emoji).get.getDescription
    d.length should be <= 4000
    val names = d.linesIterator.count(_.startsWith("### "))
    names should (be > 0 and be < 40)
    d.linesIterator.count(_.startsWith("-# ")) shouldBe names
    d should endWith("x" * 150)
  }
}
