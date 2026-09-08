package com.tibiabot.panels

import com.tibiabot.panels.PanelIds.{Ack, Panel}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PanelIdsSpec extends AnyFunSuite with Matchers {

  test("every button id round-trips back to the panel and action that built it") {
    for {
      panel <- Panel.all
      action <- PanelIds.settingsActions ++ PanelIds.listActions(panel) ++ List(PanelIds.ClearConfirm, PanelIds.Cancel)
    } withClue(s"${panel.token}/$action: ") {
      PanelIds.parse(PanelIds.button(panel, action)) shouldBe Some(panel -> action)
    }
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
}
