package com.tibiabot.panels

import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.modals.Modal
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

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

  /** The admin forms are the only ones that take no worlds at all, so nothing
   *  about them varies with the guild — but they are still modals, and Discord
   *  still rejects an oversized one at the point somebody presses the button. */
  test("both admin forms fit inside Discord's component limit") {
    for (action <- List(PanelIds.Leave, PanelIds.Message)) withClue(s"$action: ") {
      val modal = AdminForms.modal(action).getOrElse(fail(s"no form for $action"))
      sizeOf(modal) should be <= Modal.MAX_COMPONENTS
    }
  }

  test("both admin forms ask for a server id, and one other thing") {
    for (action <- List(PanelIds.Leave, PanelIds.Message)) withClue(s"$action: ") {
      val ids = AdminForms.modal(action).getOrElse(fail(s"no form for $action"))
        .getComponents.asScala.toList
        .map(_.asInstanceOf[Label].getChild.asInstanceOf[TextInput].getCustomId)
      ids should have size 2
      ids should contain(PanelForms.GuildIdField)
    }
  }

  /** Four of the six admin buttons act on the press. Asking for a form they do
   *  not have must come back empty rather than as an empty modal — the press
   *  handler reads None as "not a form action". */
  test("the admin buttons that act on the press have no form") {
    PanelIds.adminActions.diff(List(PanelIds.Leave, PanelIds.Message))
      .foreach(action => withClue(s"$action: ")(AdminForms.modal(action) shouldBe None))
  }

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

  // --- the command log, the one setting that is not about a world ---

  private def channelPicker(modal: Modal): EntitySelectMenu =
    modal.getComponents.get(0).asInstanceOf[Label].getChild.asInstanceOf[EntitySelectMenu]

  /** Every other form on this panel opens with "which world?" on a guild tracking
   *  several. There is one command log per server, so this one must not — and a
   *  form that asked would have no answer to write it to. */
  test("the command log form asks for a channel and never for a world") {
    for (worlds <- List(one, several)) withClue(s"${worlds.size} world(s): ") {
      val modal = SettingsForms.modal(PanelIds.CommandLog, worlds).get
      modal.getComponents should have size 1
      val picker = channelPicker(modal)
      picker.getCustomId shouldBe PanelForms.ChannelField
      picker.getEntityTypes.asScala should contain only EntitySelectMenu.SelectTarget.CHANNEL
    }
  }

  /** Text channels only: what comes back is resolved with getTextChannelById, and
   *  AdminLog posts to a TextChannel. A voice or forum channel picked here would
   *  come back as nothing at all. */
  test("only text channels can be picked for the command log") {
    channelPicker(SettingsForms.modal(PanelIds.CommandLog, one).get)
      .getChannelTypes.asScala should contain only ChannelType.TEXT
  }

  /** The form shows the setting as well as taking it, which for a channel means
   *  opening on the one in use — and opening on nothing when there is none to
   *  show, rather than on a channel that has since been deleted. */
  test("the command log form opens on the channel it uses now") {
    val current = channelPicker(SettingsForms.modal(PanelIds.CommandLog, one, Some("123456789")).get)
    current.getDefaultValues.size shouldBe 1
    current.getDefaultValues.get(0).getId shouldBe "123456789"

    channelPicker(SettingsForms.modal(PanelIds.CommandLog, one, None).get)
      .getDefaultValues shouldBe empty
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
