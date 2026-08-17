package com.tibiabot.notifications

/** Component and modal ids for the two DM subscriptions.
 *
 *  Parsed in one place rather than string-matched at each handler, because these
 *  ids travel further than most: the controls under a notification DM are
 *  pressed in a direct message, where there is no guild to ask which server the
 *  press is about. What identifies the subscription is the row id in the id
 *  itself — shorter than carrying guild and world along, and it lets the handler
 *  check that the presser owns what they are about to change.
 *
 *  The two autorole buttons keep their bare ids. They are pressed on an embed
 *  that has been sitting in a notifications channel since whenever `/setup` ran,
 *  and renaming them would quietly break every one already posted.
 */
object NotifyIds {

  val Prefix = "notify:"

  /** The autorole buttons under the notifications embed. `masslog` predates this
   *  feature; `bounty` is new and matches it deliberately. */
  val MasslogButton = "masslog"
  val BountyButton = "bounty"

  sealed trait Control { def id: Long }
  /** Turn a mass-log subscription off (`enable = false`) or back on. */
  final case class MasslogToggle(id: Long, enable: Boolean) extends Control
  final case class MasslogMute(id: Long) extends Control
  final case class MasslogThreshold(id: Long) extends Control
  final case class BountyToggle(id: Long, enable: Boolean) extends Control
  final case class BountyMute(id: Long) extends Control

  def masslogToggle(id: Long, enable: Boolean): String = s"${Prefix}ml:${if (enable) "on" else "off"}:$id"
  def masslogMute(id: Long): String = s"${Prefix}ml:mute:$id"
  def masslogThreshold(id: Long): String = s"${Prefix}ml:threshold:$id"
  def bountyToggle(id: Long, enable: Boolean): String = s"${Prefix}bt:${if (enable) "on" else "off"}:$id"
  def bountyMute(id: Long): String = s"${Prefix}bt:mute:$id"

  def handlesButton(componentId: String): Boolean =
    componentId == MasslogButton || componentId == BountyButton || componentId.startsWith(Prefix)

  def parseControl(componentId: String): Option[Control] =
    componentId.split(':').toList match {
      case "notify" :: "ml" :: "on" :: id :: Nil        => id.toLongOption.map(MasslogToggle(_, enable = true))
      case "notify" :: "ml" :: "off" :: id :: Nil       => id.toLongOption.map(MasslogToggle(_, enable = false))
      case "notify" :: "ml" :: "mute" :: id :: Nil      => id.toLongOption.map(MasslogMute)
      case "notify" :: "ml" :: "threshold" :: id :: Nil => id.toLongOption.map(MasslogThreshold)
      case "notify" :: "bt" :: "on" :: id :: Nil        => id.toLongOption.map(BountyToggle(_, enable = true))
      case "notify" :: "bt" :: "off" :: id :: Nil       => id.toLongOption.map(BountyToggle(_, enable = false))
      case "notify" :: "bt" :: "mute" :: id :: Nil      => id.toLongOption.map(BountyMute)
      case _                                            => None
    }

  /** A press that answers with a modal cannot be deferred — `replyModal` has to
   *  be the interaction's first response. Everything else here rewrites the
   *  message it was pressed on, so it defers an edit. Mirrors
   *  RespawnButtonId.ackFor, for the same three-second reason. */
  def opensModal(componentId: String): Boolean =
    componentId == MasslogButton || componentId == BountyButton ||
      parseControl(componentId).exists {
        case _: MasslogMute | _: MasslogThreshold | _: BountyMute => true
        case _                                                    => false
      }

  // --- modals ------------------------------------------------------------

  sealed trait Form
  /** Subscribing to mass-log DMs on `world`; the guild comes from the event,
   *  since this form is always opened from a message in one. */
  final case class MasslogForm(world: String) extends Form
  final case class BountyForm(world: String) extends Form
  final case class ThresholdForm(id: Long) extends Form
  final case class MuteForm(id: Long, bounty: Boolean) extends Form

  def masslogForm(world: String): String = s"${Prefix}form:masslog:$world"
  def bountyForm(world: String): String = s"${Prefix}form:bounty:$world"
  def thresholdForm(id: Long): String = s"${Prefix}form:threshold:$id"
  def muteForm(id: Long, bounty: Boolean): String = s"${Prefix}form:mute:${if (bounty) "bt" else "ml"}:$id"

  def handlesModal(modalId: String): Boolean = modalId.startsWith(s"${Prefix}form:")

  def parseForm(modalId: String): Option[Form] =
    modalId.split(':').toList match {
      case "notify" :: "form" :: "masslog" :: world :: Nil   => Some(MasslogForm(world))
      case "notify" :: "form" :: "bounty" :: world :: Nil    => Some(BountyForm(world))
      case "notify" :: "form" :: "threshold" :: id :: Nil    => id.toLongOption.map(ThresholdForm)
      case "notify" :: "form" :: "mute" :: "bt" :: id :: Nil => id.toLongOption.map(MuteForm(_, bounty = true))
      case "notify" :: "form" :: "mute" :: "ml" :: id :: Nil => id.toLongOption.map(MuteForm(_, bounty = false))
      case _                                                 => None
    }

  /** Field names inside those modals. */
  val ThresholdField = "threshold"
  val CharacterField = "character"
  val CooldownField = "cooldown"
  val MuteField = "mute"
}
