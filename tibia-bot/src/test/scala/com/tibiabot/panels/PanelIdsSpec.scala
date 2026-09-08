package com.tibiabot.panels

import com.tibiabot.panels.PanelIds.{Ack, Panel}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PanelIdsSpec extends AnyFunSuite with Matchers {

  test("every button id round-trips back to the panel and action that built it") {
    for {
      panel <- Panel.all
      action <- PanelIds.settingsActions ++ PanelIds.listActions(panel) ++ PanelIds.adminActions ++
        List(PanelIds.ClearConfirm, PanelIds.Cancel)
    } withClue(s"${panel.token}/$action: ") {
      PanelIds.parse(PanelIds.button(panel, action)) shouldBe Some(panel -> action)
    }
  }

  /** Drawing the hunted buttons on a panel with nothing to add to would be a
   *  silent nonsense rather than a failure, so it is asserted instead. */
  test("only the two list panels have list actions") {
    PanelIds.listActions(Panel.Hunted) should not be empty
    PanelIds.listActions(Panel.Allies) should not be empty
    PanelIds.listActions(Panel.Settings) shouldBe empty
    PanelIds.listActions(Panel.Admin) shouldBe empty
  }

  /** The admin actions must not fall through to the OpensModal default that
   *  serves the other three panels: four of the six take no input at all, and a
   *  form opening on Repost boosted would be a press that does nothing. */
  test("only the two admin buttons that ask for a server id open a form") {
    val opensForm = PanelIds.adminActions.filter(action =>
      PanelIds.opensModal(PanelIds.button(Panel.Admin, action)))
    opensForm should contain theSameElementsAs List(PanelIds.Leave, PanelIds.Message)
    PanelIds.adminActions.diff(opensForm).foreach { action =>
      withClue(s"$action: ") {
        PanelIds.ackFor(PanelIds.button(Panel.Admin, action)) shouldBe Ack.Replies
      }
    }
  }

  /** `parse` reads an action without knowing its panel, so a token shared across
   *  two panels would resolve to whichever branch of `ackFor` came first. */
  test("no admin action collides with a settings or list action") {
    val others = PanelIds.settingsActions ++ PanelIds.listActions(Panel.Hunted) ++
      List(PanelIds.TagOne, PanelIds.ClearConfirm, PanelIds.Cancel)
    PanelIds.adminActions.intersect(others) shouldBe empty
  }

  test("a form id round-trips the same way, so a press and its form stay paired") {
    PanelIds.parse(PanelIds.form(Panel.Settings, PanelIds.Fullbless)) shouldBe
      Some(Panel.Settings -> PanelIds.Fullbless)
  }

  test("buttons and forms are told apart on the prefix alone") {
    val button = PanelIds.button(Panel.Hunted, PanelIds.Add)
    val form = PanelIds.form(Panel.Hunted, PanelIds.Add)
    button should not be form
    PanelIds.handlesButton(button) shouldBe true
    PanelIds.handlesForm(button) shouldBe false
    PanelIds.handlesForm(form) shouldBe true
    PanelIds.handlesButton(form) shouldBe false
  }

  /** Nothing else in the bot may be swallowed by this router — the respawn and
   *  notification buttons sit in the same if/else chain. */
  test("ids belonging to other features are not claimed") {
    List("respawn:claim:12", "boosted add", "galthenSet", "").foreach { id =>
      withClue(s"'$id': ") {
        PanelIds.handlesButton(id) shouldBe false
        PanelIds.handlesForm(id) shouldBe false
      }
    }
  }

  /** A button from an older deploy must be answered, not thrown on. */
  test("a malformed id parses to nothing and is answered with a message") {
    List("panel:", "panel:nonsense:add", "panel:settings", "panel:a:b:c").foreach { id =>
      withClue(s"'$id': ") {
        PanelIds.parse(id) shouldBe empty
        PanelIds.ackFor(id) shouldBe Ack.Replies
      }
    }
  }

  /** The acknowledgement is decided before the handler runs, and getting it wrong
   *  is what produces "the application did not respond": a form that was deferred
   *  can no longer open, and a reply that was not is three seconds from failing. */
  /** The list is the panel's own reply now, not a button — so every button on a
   *  list panel opens a form except the two that answer the Clear question. */
  test("forms are never deferred, and everything else is") {
    PanelIds.settingsActions.foreach { action =>
      withClue(s"/settings $action: ") {
        PanelIds.ackFor(PanelIds.button(Panel.Settings, action)) shouldBe Ack.OpensModal
      }
    }
    List(PanelIds.Add, PanelIds.Remove, PanelIds.Info, PanelIds.Config).foreach { action =>
      withClue(s"/hunted $action: ") {
        PanelIds.ackFor(PanelIds.button(Panel.Hunted, action)) shouldBe Ack.OpensModal
      }
    }
    PanelIds.ackFor(PanelIds.button(Panel.Hunted, PanelIds.Clear)) shouldBe Ack.EditsMessage
    PanelIds.ackFor(PanelIds.button(Panel.Hunted, PanelIds.ClearConfirm)) shouldBe Ack.EditsMessage
    PanelIds.ackFor(PanelIds.button(Panel.Hunted, PanelIds.Cancel)) shouldBe Ack.EditsMessage
  }

  test("opensModal agrees with ackFor") {
    val ids = Panel.all.flatMap(p => (PanelIds.settingsActions ++ PanelIds.listActions(p)).map(PanelIds.button(p, _)))
    ids.foreach(id => PanelIds.opensModal(id) shouldBe (PanelIds.ackFor(id) == Ack.OpensModal))
  }

  /** Discord rejects a component id past a hundred characters. */
  test("no id comes close to Discord's length limit") {
    val ids = Panel.all.flatMap(p =>
      (PanelIds.settingsActions ++ PanelIds.listActions(p)).flatMap(a =>
        List(PanelIds.button(p, a), PanelIds.form(p, a))))
    ids.foreach(id => id.length should be <= 100)
  }

  // --- tagging one player from a Look up reply ---

  /** The name rides in the component id, which is what lets the form ask only
   *  for the tag. Tibia names contain spaces but never a colon. */
  test("a subject round-trips through the id, spaces and all") {
    val id = PanelIds.buttonFor(Panel.Hunted, PanelIds.TagOne, "Violent Beams")
    PanelIds.parse(id) shouldBe Some(Panel.Hunted -> PanelIds.TagOne)
    PanelIds.subjectOf(id) shouldBe Some("Violent Beams")
  }

  test("a form id carries the subject the same way") {
    val id = PanelIds.formFor(Panel.Hunted, PanelIds.TagOne, "Bubble")
    PanelIds.parse(id) shouldBe Some(Panel.Hunted -> PanelIds.TagOne)
    PanelIds.subjectOf(id) shouldBe Some("Bubble")
  }

  /** Every component without one still reads as having no subject, rather than
   *  throwing or inventing an empty name. */
  test("components with no subject have none") {
    PanelIds.subjectOf(PanelIds.button(Panel.Hunted, PanelIds.Add)) shouldBe None
    PanelIds.subjectOf(PanelIds.button(Panel.Settings, PanelIds.Fullbless)) shouldBe None
    PanelIds.subjectOf("nonsense") shouldBe None
    PanelIds.subjectOf("panel:hunted:tagone:") shouldBe None
  }

  test("carrying a subject does not change how the press is acknowledged") {
    PanelIds.ackFor(PanelIds.buttonFor(Panel.Hunted, PanelIds.TagOne, "Bubble")) shouldBe Ack.OpensModal
  }

  /** Discord rejects a component id past a hundred characters, and a subject is
   *  the only part that varies in length. */
  test("even the longest name stays inside the id limit") {
    val longest = "A" * 29
    PanelIds.buttonFor(Panel.Hunted, PanelIds.TagOne, longest).length should be <= 100
    PanelIds.formFor(Panel.Hunted, PanelIds.TagOne, longest).length should be <= 100
  }
}
