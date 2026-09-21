package com.tibiabot.cooldowns

import com.tibiabot.domain.CooldownKind

/** Component and modal ids for the cooldown trackers.
 *
 *  Parsed in one place rather than string-matched per handler, because the id
 *  is the only thing telling a press which collectible it is about: the panel
 *  offers both kinds from one embed, and the expiry DM's buttons are pressed in
 *  a direct message with no other context to ask.
 *
 *  The ten [[LegacyButtons]] ids are the ones this feature used while the
 *  satchel was the only kind, and they all still resolve. They have to:
 *  `galthen default` sits on a notifications embed posted whenever `/setup` last
 *  ran, and `galthenRemind`/`galthenClear` sit on expiry DMs in people's inboxes
 *  indefinitely. Renaming them outright would quietly break every one already out
 *  there, and nothing re-posts them. Nine mean what they always did, on the
 *  satchel; `galthen default` is the exception and opens the panel — see
 *  [[Action.Panel]]. Nothing new is posted with any of them.
 */
object CooldownIds {

  val Prefix = "cooldown:"

  sealed trait Action
  object Action {
    /** Answer with the tracker panel itself, ephemerally.
     *
     *  Nothing new is posted with this — the panel is posted by `/setup` and
     *  answered by `/cooldowns`. It exists for `galthen default`, the single
     *  button on every notifications embed posted before this change: those
     *  embeds are never re-posted, so mapping it here is what gives a guild that
     *  has not re-run `/setup` a way to reach the second tracker. Its kind is
     *  meaningless and arbitrary; the panel names both. */
    case object Panel extends Action
    /** The panel's per-kind button: answers with that kind's list, ephemerally. */
    case object Open extends Action
    /** Collect now, on an embed whose footer names the tag (or none). */
    case object Set extends Action
    case object Remove extends Action
    case object RemoveAll extends Action
    /** The two-step guard that used to sit over Clear All on a multi-entry list.
     *  Nothing posted now carries it — the panel's list offers Clear All
     *  directly, as the notifications-channel route always did — but presses are
     *  still answered, for the `/galthen` replies it was posted on. */
    case object Lock extends Action
    case object Unlock extends Action
    /** Collect again, from under the expiry DM. */
    case object Remind extends Action
    /** Leave the expiry DM alone; just drop its buttons. */
    case object Dismiss extends Action
    case object AddForm extends Action
    case object RemoveForm extends Action
  }

  private val actionIds: List[(Action, String)] = List(
    Action.Panel      -> "panel",
    Action.Open       -> "open",
    Action.Set        -> "set",
    Action.Remove     -> "rem",
    Action.RemoveAll  -> "remall",
    Action.Lock       -> "lock",
    Action.Unlock     -> "unlock",
    Action.Remind     -> "remind",
    Action.Dismiss    -> "dismiss",
    Action.AddForm    -> "addform",
    Action.RemoveForm -> "remform"
  )

  private val actionById: Map[String, Action] = actionIds.map(_.swap).toMap
  private val idByAction: Map[Action, String] = actionIds.toMap

  def button(kind: CooldownKind, action: Action): String = s"$Prefix${kind.id}:${idByAction(action)}"

  /** What each pre-kind id meant, which was always the satchel. */
  val LegacyButtons: Map[String, (CooldownKind, Action)] = Map(
    "galthen default"  -> (CooldownKind.Satchel, Action.Panel),
    "galthenSet"       -> (CooldownKind.Satchel, Action.Set),
    "galthenRemove"    -> (CooldownKind.Satchel, Action.Remove),
    "galthenRemoveAll" -> (CooldownKind.Satchel, Action.RemoveAll),
    "galthenLock"      -> (CooldownKind.Satchel, Action.Lock),
    "galthenUnLock"    -> (CooldownKind.Satchel, Action.Unlock),
    "galthenRemind"    -> (CooldownKind.Satchel, Action.Remind),
    "galthenClear"     -> (CooldownKind.Satchel, Action.Dismiss),
    "galthenAdd"       -> (CooldownKind.Satchel, Action.AddForm),
    "galthenButtonRem" -> (CooldownKind.Satchel, Action.RemoveForm)
  )

  def parse(componentId: String): Option[(CooldownKind, Action)] =
    LegacyButtons.get(componentId).orElse {
      componentId.split(':').toList match {
        case "cooldown" :: kind :: action :: Nil =>
          for {
            k <- CooldownKind.parse(kind)
            a <- actionById.get(action)
          } yield (k, a)
        case _ => None
      }
    }

  def handles(componentId: String): Boolean = parse(componentId).isDefined

  /** A press answering with a form cannot be deferred — `replyModal` has to be
   *  the interaction's first response. Mirrors NotifyIds.opensModal. */
  def opensModal(componentId: String): Boolean =
    parse(componentId).exists { case (_, action) =>
      action == Action.AddForm || action == Action.RemoveForm
    }

  // --- modals ------------------------------------------------------------

  /** The form's own id, and the id of the single text field inside it. Kept
   *  apart because the handler dispatches on the field — that is where the typed
   *  tag arrives — while the form id is only ever shown as a title. */
  def modal(kind: CooldownKind, adding: Boolean): String =
    s"$Prefix${kind.id}:modal:${if (adding) "add" else "rem"}"

  def field(kind: CooldownKind, adding: Boolean): String =
    s"$Prefix${kind.id}:form:${if (adding) "add" else "rem"}"

  /** The pre-kind field ids, for a form opened from a button posted before this
   *  change and submitted after it. */
  val LegacyFields: Map[String, (CooldownKind, Boolean)] = Map(
    "galthen add" -> (CooldownKind.Satchel, true),
    "galthen rem" -> (CooldownKind.Satchel, false)
  )

  /** `(kind, adding)` for a submitted form's text field. */
  def parseField(fieldId: String): Option[(CooldownKind, Boolean)] =
    LegacyFields.get(fieldId).orElse {
      fieldId.split(':').toList match {
        case "cooldown" :: kind :: "form" :: "add" :: Nil => CooldownKind.parse(kind).map((_, true))
        case "cooldown" :: kind :: "form" :: "rem" :: Nil => CooldownKind.parse(kind).map((_, false))
        case _                                             => None
      }
    }
}
