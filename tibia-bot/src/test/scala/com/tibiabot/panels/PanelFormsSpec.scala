package com.tibiabot.panels

import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import net.dv8tion.jda.api.modals.Modal
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Guards the one thing about these forms that fails at run time rather than at
 *  compile time: Discord rejects a modal carrying more than `Modal.MAX_COMPONENTS`
 *  outright, and a form that grew a sixth box would only be found by somebody
 *  pressing the button in production.
 *
 *  The world picker is what makes this tight. A guild tracking one world does not
 *  get one, so a form has a spare slot there and not on a guild tracking several
 *  — which is exactly the case nobody would test by hand.
 */
class PanelFormsSpec extends AnyFunSuite with Matchers {

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
    exivaList = "true", activityChannel = "0", onlineCombined = "separate")

  private val one = List(world("Antica"))
  private val several = List(world("Antica"), world("Belobra"), world("Vunira"))

  private def sizeOf(modal: Modal): Int = modal.getComponents.size

  test("every settings form fits inside Discord's component limit") {
    for {
      action <- PanelIds.settingsActions
      worlds <- List(one, several)
    } {
      val modal = SettingsForms.modal(action, worlds)
      withClue(s"/settings $action with ${worlds.size} world(s): ") {
        modal shouldBe defined
        sizeOf(modal.get) should be <= Modal.MAX_COMPONENTS
      }
    }
  }

  test("every list form fits inside Discord's component limit") {
    for {
      panel <- List(Panel.Hunted, Panel.Allies)
      action <- List(PanelIds.Add, PanelIds.Remove, PanelIds.Info, PanelIds.Config)
      worlds <- List(one, several)
    } {
      val modal = ListForms.modal(panel, action, worlds)
      withClue(s"/${panel.token} $action with ${worlds.size} world(s): ") {
        modal shouldBe defined
        sizeOf(modal.get) should be <= Modal.MAX_COMPONENTS
      }
    }
  }

  /** The world picker is the difference between the two, and it must appear
   *  exactly when there is a choice to make. */
  test("a single-world guild is never asked which world") {
    PanelForms.worldPicker(one) shouldBe empty
    PanelForms.worldPicker(several) shouldBe defined
    sizeOf(SettingsForms.modal(PanelIds.Neutral, several).get) shouldBe
      sizeOf(SettingsForms.modal(PanelIds.Neutral, one).get) + 1
  }

  test("no form is offered when no world is set up") {
    SettingsForms.modal(PanelIds.Fullbless, Nil) shouldBe empty
  }

  /** Auto-detection is a hunted-only idea, so the allies form is one shorter. */
  test("only the hunted display form carries auto-detect") {
    sizeOf(ListForms.modal(Panel.Hunted, PanelIds.Config, one).get) shouldBe
      sizeOf(ListForms.modal(Panel.Allies, PanelIds.Config, one).get) + 1
  }

  test("an unknown action produces no form rather than an empty one") {
    SettingsForms.modal("nonsense", one) shouldBe empty
    ListForms.modal(Panel.Hunted, "nonsense", one) shouldBe empty
  }

  /** The tag picker is hunted-only, so the two panels' Add forms differ by one
   *  component. */
  test("only the hunted Add form carries the tag picker") {
    sizeOf(ListForms.modal(Panel.Hunted, PanelIds.Add, one).get) shouldBe
      sizeOf(ListForms.modal(Panel.Allies, PanelIds.Add, one).get) + 1
  }

  /** Both panels stay inside Discord's five-button row limit as drawn, and every
   *  form inside the component cap — the Add form is the one that grew. */
  test("every tag-bearing form still fits the component limit") {
    for {
      worlds <- List(one, several)
      action <- List(PanelIds.Add)
    } withClue(s"/hunted $action with ${worlds.size} world(s): ") {
      sizeOf(ListForms.modal(Panel.Hunted, action, worlds).get) should be <= Modal.MAX_COMPONENTS
    }
  }
}
