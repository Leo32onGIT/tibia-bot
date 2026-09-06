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

  /** A control names either one subscription, by the row id it carries, or a
   *  world — the bounty panel's own two buttons act on whatever the presser
   *  happens to be watching there, which is not known until they press. */
  sealed trait Control
  /** Turn a mass-log subscription off (`enable = false`) or back on. */
  /** Kept for the DMs already sitting in people's inboxes, whose Disable button
   *  still points here. New alerts carry [[MasslogDrop]] instead. */
  final case class MasslogToggle(id: Long, enable: Boolean) extends Control
  final case class MasslogMute(id: Long) extends Control
  final case class MasslogThreshold(id: Long) extends Control
  /** Be rid of this subscription: the row goes, and the role with it. Named to
   *  match [[BountyDrop]], and for the same reason — the subscription it means
   *  is the one that just woke the reader up, so nothing has to be picked. */
  final case class MasslogDrop(id: Long) extends Control
  /** Put back what a [[MasslogDrop]] just removed.
   *
   *  Carries state rather than a key, like [[BountyTrackAgain]] and for the same
   *  reason: after the delete there is no row to point at, and a DM has no guild
   *  of its own to fall back on. A guild id is digits, a world is letters, and a
   *  threshold is a number — none of them the colon this splits on. */
  final case class MasslogAgain(guildId: String, world: String, threshold: Int) extends Control
  final case class BountyToggle(id: Long, enable: Boolean) extends Control
  final case class BountyMute(id: Long) extends Control
  /** Stop watching, from under the alert itself. Named apart from
   *  [[BountyRemove]] because that one carries a world and opens a picker;
   *  this one already knows which bounty is meant — it is the one that just
   *  woke the reader up. */
  final case class BountyDrop(id: Long) extends Control
  /** Put back what a [[BountyDrop]] just removed.
   *
   *  The whole subscription rides in the id, because after the delete there is
   *  no row left to point at — and a DM has no guild of its own to fall back on.
   *  It is the only id here carrying state rather than a key, and it can: all
   *  four parts are short, and a character name is letters, spaces, apostrophes
   *  and hyphens — never the colon this splits on. */
  final case class BountyTrackAgain(guildId: String, world: String, character: String, cooldownMinutes: Int) extends Control
  /** The bounty panel's buttons: track somebody new on `world`, or stop
   *  watching somebody already tracked there. */
  final case class BountyAdd(world: String) extends Control
  final case class BountyRemove(world: String) extends Control

  def masslogToggle(id: Long, enable: Boolean): String = s"${Prefix}ml:${if (enable) "on" else "off"}:$id"
  def masslogMute(id: Long): String = s"${Prefix}ml:mute:$id"
  def masslogThreshold(id: Long): String = s"${Prefix}ml:threshold:$id"
  def masslogDrop(id: Long): String = s"${Prefix}ml:drop:$id"
  def masslogAgain(guildId: String, world: String, threshold: Int): String =
    s"${Prefix}ml:back:$guildId:$world:$threshold"
  def bountyToggle(id: Long, enable: Boolean): String = s"${Prefix}bt:${if (enable) "on" else "off"}:$id"
  def bountyMute(id: Long): String = s"${Prefix}bt:mute:$id"
  def bountyDrop(id: Long): String = s"${Prefix}bt:drop:$id"
  def bountyTrackAgain(guildId: String, world: String, character: String, cooldownMinutes: Int): String =
    s"${Prefix}bt:back:$guildId:$world:$cooldownMinutes:$character"

  /** The id on the disabled marker that stands in when a way back won't fit. It
   *  is never pressed — Discord sends nothing for a disabled button — but every
   *  button needs one, and this keeps it out of the way of ids that mean
   *  something. */
  val bountyRemoved: String = s"${Prefix}bt:removed"

  /** Discord's cap on a component id. Only [[bountyTrackAgain]] can approach it,
   *  and its worst case — a 20-digit guild, the longest world and a 29-character
   *  name — still lands under 90. The check exists so that a longer one would
   *  lose its button rather than the message losing its edit. */
  val MaxCustomId: Int = 100
  def bountyAdd(world: String): String = s"${Prefix}bt:add:$world"
  def bountyRemove(world: String): String = s"${Prefix}bt:rm:$world"

  def handlesButton(componentId: String): Boolean =
    componentId == MasslogButton || componentId == BountyButton || componentId.startsWith(Prefix)

  def parseControl(componentId: String): Option[Control] =
    componentId.split(':').toList match {
      case "notify" :: "ml" :: "on" :: id :: Nil        => id.toLongOption.map(MasslogToggle(_, enable = true))
      case "notify" :: "ml" :: "off" :: id :: Nil       => id.toLongOption.map(MasslogToggle(_, enable = false))
      case "notify" :: "ml" :: "mute" :: id :: Nil      => id.toLongOption.map(MasslogMute)
      case "notify" :: "ml" :: "threshold" :: id :: Nil => id.toLongOption.map(MasslogThreshold)
      case "notify" :: "ml" :: "drop" :: id :: Nil      => id.toLongOption.map(MasslogDrop)
      case "notify" :: "ml" :: "back" :: guild :: world :: threshold :: Nil =>
        threshold.toIntOption.map(MasslogAgain(guild, world, _))
      case "notify" :: "bt" :: "on" :: id :: Nil        => id.toLongOption.map(BountyToggle(_, enable = true))
      case "notify" :: "bt" :: "off" :: id :: Nil       => id.toLongOption.map(BountyToggle(_, enable = false))
      case "notify" :: "bt" :: "mute" :: id :: Nil      => id.toLongOption.map(BountyMute)
      case "notify" :: "bt" :: "drop" :: id :: Nil      => id.toLongOption.map(BountyDrop)
      case "notify" :: "bt" :: "back" :: guild :: world :: cooldown :: character :: Nil =>
        cooldown.toIntOption.map(BountyTrackAgain(guild, world, character, _))
      case "notify" :: "bt" :: "add" :: world :: Nil    => Some(BountyAdd(world))
      case "notify" :: "bt" :: "rm" :: world :: Nil     => Some(BountyRemove(world))
      case _                                            => None
    }

  /** A press that answers with a modal cannot be deferred — `replyModal` has to
   *  be the interaction's first response. Everything else is deferred as an
   *  edit: those either rewrite the row they were pressed on or, in the Bounty
   *  button's case, answer with an ephemeral of their own, and a deferred edit
   *  leaves the message it was pressed on alone either way. Mirrors
   *  RespawnButtonId.ackFor, for the same three-second reason. */
  def opensModal(componentId: String): Boolean =
    componentId == MasslogButton ||
      parseControl(componentId).exists {
        case _: MasslogMute | _: MasslogThreshold | _: BountyMute => true
        case _: BountyAdd | _: BountyRemove                       => true
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
  /** Picking which of this user's bounties on `world` to stop watching. */
  final case class RemoveForm(world: String) extends Form

  def masslogForm(world: String): String = s"${Prefix}form:masslog:$world"
  def bountyForm(world: String): String = s"${Prefix}form:bounty:$world"
  def thresholdForm(id: Long): String = s"${Prefix}form:threshold:$id"
  def muteForm(id: Long, bounty: Boolean): String = s"${Prefix}form:mute:${if (bounty) "bt" else "ml"}:$id"
  def removeForm(world: String): String = s"${Prefix}form:remove:$world"

  def handlesModal(modalId: String): Boolean = modalId.startsWith(s"${Prefix}form:")

  def parseForm(modalId: String): Option[Form] =
    modalId.split(':').toList match {
      case "notify" :: "form" :: "masslog" :: world :: Nil   => Some(MasslogForm(world))
      case "notify" :: "form" :: "bounty" :: world :: Nil    => Some(BountyForm(world))
      case "notify" :: "form" :: "threshold" :: id :: Nil    => id.toLongOption.map(ThresholdForm)
      case "notify" :: "form" :: "mute" :: "bt" :: id :: Nil => id.toLongOption.map(MuteForm(_, bounty = true))
      case "notify" :: "form" :: "mute" :: "ml" :: id :: Nil => id.toLongOption.map(MuteForm(_, bounty = false))
      case "notify" :: "form" :: "remove" :: world :: Nil    => Some(RemoveForm(world))
      case _                                                 => None
    }

  /** Field names inside those modals. */
  val ThresholdField = "threshold"
  val CharacterField = "character"
  val CooldownField = "cooldown"
  val MuteField = "mute"
  val RemoveField = "remove"
}
