package com.tibiabot.panels

import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import net.dv8tion.jda.api.components.Component
import net.dv8tion.jda.api.components.container.Container
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** The `/settings` reply: a button beside each setting's explanation. The things
 *  that fail at run time rather than compile time are a setting with no button
 *  and a panel past Discord's component cap, so those are what this pins. */
class SettingsPanelSpec extends AnyFunSuite with Matchers {

  private def world(name: String): Worlds = Worlds(
    name = name,
    alliesChannel = "0", enemiesChannel = "0", neutralsChannel = "0",
    levelsChannel = "0", deathsChannel = "0", category = "0",
    fullblessRole = "0", nemesisRole = "0", allyPkRole = "0", masslogRole = "0",
    bountyRole = "0", fullblessChannel = "0", nemesisChannel = "0",
    fullblessLevel = 250,
    showNeutralLevels = "true", showNeutralDeaths = "true",
    showAlliesLevels = "true", showAlliesDeaths = "true",
    showEnemiesLevels = "true", showEnemiesDeaths = "true",
    detectHunteds = "true", levelsMin = 8, deathsMin = 8,
    activityChannel = "0", onlineCombined = "separate")

  private def panel(worlds: List[Worlds] = List(world("Antica"))): Container =
    Panels.settingsPanel(worlds, fullblessEmoji = "<:inq:1>")

  private def children(c: Container) = c.getComponents.asScala.toList

  private def sections(c: Container) =
    children(c).filter(_.getType == Component.Type.SECTION).map(_.asSection)

  private def texts(c: Container): List[String] =
    children(c).filter(_.getType == Component.Type.TEXT_DISPLAY).map(_.asTextDisplay.getContent)

  test("every setting has one gear button, opening its own form") {
    val buttons = sections(panel()).map(_.getAccessory.asButton)
    buttons.map(_.getCustomId).sorted shouldBe
      PanelIds.settingsActions.map(PanelIds.button(Panel.Settings, _)).sorted
    buttons.map(_.getEmoji.getName).distinct shouldBe List("⚙️")
    buttons.map(b => Option(b.getLabel).getOrElse("")).distinct shouldBe List("")
  }

  test("each button sits beside the setting's name and what it changes") {
    val fullbless = sections(panel()).find(_.getAccessory.asButton.getCustomId ==
      PanelIds.button(Panel.Settings, PanelIds.Fullbless)).get
    val text = fullbless.getContentComponents.asScala.map(_.asTextDisplay.getContent).mkString
    text should startWith("<:inq:1> **Fullbless**\n-# ")
    text should include("fullbless role")
  }

  test("settings are grouped into per-world and server-wide, in that order") {
    texts(panel()).filter(_.startsWith("-# **")) shouldBe List("-# **PER WORLD**", "-# **SERVER-WIDE**")
  }

  test("the header names every tracked world") {
    texts(panel(List(world("Secura"), world("Antica")))).head should include("**Antica**, **Secura**")
  }

  test("stays inside Discord's cap on components in a message") {
    // The container, each child, and each section's text and button all count.
    val c = panel()
    val count = 1 + children(c).size + sections(c).map(s => s.getContentComponents.size + 1).sum
    count should be <= 40
  }
}
