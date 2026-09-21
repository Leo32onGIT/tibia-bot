package com.tibiabot.cooldowns

import com.tibiabot.domain.CooldownKind
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CooldownIdsSpec extends AnyFunSuite with Matchers {

  import CooldownIds.{Action => A}

  private val actions = List(
    A.Panel, A.Open, A.Set, A.Remove, A.RemoveAll, A.Lock, A.Unlock, A.Remind, A.Dismiss, A.AddForm, A.RemoveForm)

  test("every button id round-trips for every kind") {
    for {
      kind   <- CooldownKind.all
      action <- actions
    } CooldownIds.parse(CooldownIds.button(kind, action)) shouldBe Some((kind, action))
  }

  test("a kind's ids are never confused for the other kind's") {
    val satchel = actions.map(CooldownIds.button(CooldownKind.Satchel, _)).toSet
    val dragon = actions.map(CooldownIds.button(CooldownKind.DragonHead, _)).toSet
    satchel intersect dragon shouldBe empty
  }

  /** The reason this object exists. `galthen default` is on a notifications
   *  embed posted whenever /setup last ran, and galthenRemind/galthenClear are
   *  on expiry DMs sitting in inboxes — none of them get re-posted, so all ten
   *  have to keep working, and all ten mean the satchel. */
  test("the pre-kind panel button opens the new panel, so old embeds reach both kinds") {
    // Those notifications embeds are never re-posted, so this mapping is the
    // only way a guild that has not re-run /setup sees the second tracker.
    CooldownIds.parse("galthen default") shouldBe Some((CooldownKind.Satchel, A.Panel))
  }

  test("every pre-kind button id still resolves, to the satchel") {
    val legacy = Map(
      "galthen default"  -> A.Panel,
      "galthenSet"       -> A.Set,
      "galthenRemove"    -> A.Remove,
      "galthenRemoveAll" -> A.RemoveAll,
      "galthenLock"      -> A.Lock,
      "galthenUnLock"    -> A.Unlock,
      "galthenRemind"    -> A.Remind,
      "galthenClear"     -> A.Dismiss,
      "galthenAdd"       -> A.AddForm,
      "galthenButtonRem" -> A.RemoveForm
    )
    legacy.foreach { case (id, action) =>
      CooldownIds.parse(id) shouldBe Some((CooldownKind.Satchel, action))
    }
    // and nothing was left out of the map the handler actually reads
    CooldownIds.LegacyButtons.keySet shouldBe legacy.keySet
  }

  test("pre-kind form fields still resolve, to the satchel") {
    CooldownIds.parseField("galthen add") shouldBe Some((CooldownKind.Satchel, true))
    CooldownIds.parseField("galthen rem") shouldBe Some((CooldownKind.Satchel, false))
  }

  test("form fields round-trip for every kind") {
    CooldownKind.all.foreach { kind =>
      CooldownIds.parseField(CooldownIds.field(kind, adding = true)) shouldBe Some((kind, true))
      CooldownIds.parseField(CooldownIds.field(kind, adding = false)) shouldBe Some((kind, false))
    }
  }

  test("only the two form-opening actions skip the deferral") {
    CooldownKind.all.foreach { kind =>
      CooldownIds.opensModal(CooldownIds.button(kind, A.AddForm)) shouldBe true
      CooldownIds.opensModal(CooldownIds.button(kind, A.RemoveForm)) shouldBe true
      CooldownIds.opensModal(CooldownIds.button(kind, A.Open)) shouldBe false
      CooldownIds.opensModal(CooldownIds.button(kind, A.Set)) shouldBe false
    }
  }

  test("ids belonging to other features are left alone") {
    List("boosted add", "respawn:claim:12", "notify:ml:mute:4", "fullbless", "masslog", "").foreach { id =>
      CooldownIds.handles(id) shouldBe false
    }
  }

  test("a malformed cooldown id is not claimed") {
    List("cooldown:", "cooldown:satchel", "cooldown:satchel:nosuch", "cooldown:nosuch:set",
      "cooldown:satchel:set:extra").foreach { id =>
      CooldownIds.parse(id) shouldBe None
    }
  }
}
