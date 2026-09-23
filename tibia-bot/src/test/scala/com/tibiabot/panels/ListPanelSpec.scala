package com.tibiabot.panels

import com.tibiabot.panels.PanelIds.Panel
import net.dv8tion.jda.api.components.Component
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponentUnion}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** The `/hunted` and `/allies` card. What fails at run time rather than compile
 *  time is a message past Discord's limits, a button in the wrong place, and a
 *  long list losing a line or its world heading where it spills — so those are
 *  what this pins. */
class ListPanelSpec extends AnyFunSuite with Matchers {

  private val thumb = "https://example/coffin.gif"

  private def player(i: Int, world: String) =
    s":fire: **${400 + i}** - **[Somebody Long Named $i $world](https://www.tibia.com/community/?name=x$i)** <:enemyguild:1> <t:1790000000:R>"

  private val guilds = List("**[Bloodbound Legion](https://g)** — **47** members", "**[Night Serpents](https://g)**")
  private val small = List("Antica" -> List(player(1, "Antica"), player(2, "Antica")), "Secura" -> List(player(3, "Secura")))
  private val large = List("Antica", "Secura", "Vunira").map(w => w -> (1 to 45).toList.map(player(_, w)))

  private def children(c: Container): List[ContainerChildComponentUnion] = c.getComponents.asScala.toList

  /** Every piece of text in a message, where Discord counts its 4,000. */
  private def texts(c: Container): List[String] = children(c).flatMap { child =>
    child.getType match {
      case Component.Type.TEXT_DISPLAY => List(child.asTextDisplay.getContent)
      case Component.Type.SECTION      => child.asSection.getContentComponents.asScala.map(_.asTextDisplay.getContent)
      case _                           => Nil
    }
  }

  /** Discord's count: the container, each child, and everything inside those. */
  private def componentCount(c: Container): Int = 1 + children(c).map { child =>
    child.getType match {
      case Component.Type.SECTION    => 1 + child.asSection.getContentComponents.size + 1
      case Component.Type.ACTION_ROW => 1 + child.asActionRow.getComponents.size
      case _                         => 1
    }
  }.sum

  private def headingButtons(c: Container): List[String] =
    children(c).filter(_.getType == Component.Type.SECTION).map(_.asSection.getAccessory)
      .filter(_.getType == Component.Type.BUTTON).map(_.asButton.getCustomId)

  private def footerButtons(c: Container): List[String] =
    children(c).filter(_.getType == Component.Type.ACTION_ROW)
      .flatMap(_.asActionRow.getButtons.asScala.map(_.getCustomId))

  test("a short list is one card: header, Add on each heading, the rest in one row at the foot") {
    val pages = ListPanel.pages(Panel.Hunted, thumb, guilds, small)
    pages should have size 1
    headingButtons(pages.head) shouldBe List(PanelIds.AddGuild, PanelIds.AddPlayer).map(PanelIds.button(Panel.Hunted, _))
    footerButtons(pages.head) shouldBe PanelIds.listFooterActions.map(PanelIds.button(Panel.Hunted, _))
    texts(pages.head).head should startWith("### ☠️ Hunted list\n-# 2 guilds · 3 players")
  }

  test("each world gets a small heading above its players") {
    val all = texts(ListPanel.pages(Panel.Allies, thumb, guilds, small).head).mkString("\n")
    all should include("-# **ANTICA**")
    all should include("-# **SECURA**")
    all.indexOf("-# **ANTICA**") should be < all.indexOf(player(1, "Antica"))
  }

  test("an empty list says so in both halves") {
    val all = texts(ListPanel.pages(Panel.Hunted, thumb, Nil, Nil).head).mkString("\n")
    all should include("*No guilds on the list yet.*")
    all should include("*Nobody on the list yet.*")
  }

  test("a long list spills onto more messages, each inside Discord's limits") {
    val pages = ListPanel.pages(Panel.Hunted, thumb, guilds, large)
    pages.size should be > 1
    pages.foreach { page =>
      texts(page).map(_.length).sum should be <= 4000
      componentCount(page) should be <= 40
    }
  }

  test("nothing is lost when a list spills, and a split world is headed again") {
    val pages = ListPanel.pages(Panel.Hunted, thumb, guilds, large)
    val all = pages.flatMap(texts).mkString("\n")
    large.flatMap(_._2).foreach(line => all should include(line))
    all should include(", continued")
  }

  test("the header leads the first message and the row of buttons closes the last") {
    val pages = ListPanel.pages(Panel.Hunted, thumb, guilds, large)
    texts(pages.head).head should startWith("### ☠️ Hunted list")
    pages.init.foreach(page => footerButtons(page) shouldBe empty)
    footerButtons(pages.last) should not be empty
  }

  test("Clear All asks before it clears, naming what would go") {
    val c = ListPanel.confirmClear(Panel.Allies, 5, 1, "<:no:1>")
    texts(c).head should include("**5 players** and **1 guild** from the allies list")
    footerButtons(c) shouldBe List(PanelIds.ClearConfirm, PanelIds.Cancel).map(PanelIds.button(Panel.Allies, _))
  }
}
