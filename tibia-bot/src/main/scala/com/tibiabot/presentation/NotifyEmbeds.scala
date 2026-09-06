package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.{BountySub, MasslogSub, MuteScale}
import com.tibiabot.notifications.NotifyIds
import com.tibiabot.tracking.MasslogDetector
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji

import java.time.Instant
import scala.jdk.CollectionConverters._

/** The DMs the two notification autoroles send, and the controls under them.
 *
 *  Every one of these messages carries its own settings: a DM arrives with no
 *  context and often at a bad moment, so the way to turn it off, quieten it or
 *  retune it has to be in the message itself rather than back in a channel the
 *  reader would have to go find.
 */
object NotifyEmbeds {

  /** The window the mass-log count is over, said in words. */
  private val windowMinutes: Long = MasslogDetector.RecentLoginSeconds / 60

  private def status(enabled: Boolean, mutedUntil: Option[Instant]): String = {
    val now = Instant.now()
    mutedUntil.filter(_.isAfter(now)) match {
      case Some(until) if enabled => s"Muted until <t:${until.getEpochSecond}:t>"
      case _ if enabled           => "Notifications are on"
      case _                      => "Notifications are off"
    }
  }

  // --- mass log ----------------------------------------------------------

  def masslogDm(world: String, guildName: String, zapCount: Int, enemiesOnline: Int, threshold: Int): MessageEmbed =
    new EmbedBuilder()
      .setColor(Embeds.NemesisPurple)
      .setTitle(s"${Config.masslogEmoji} Mass log on $world", Urls.worldUrl(world))
      .setDescription(
        s"**$zapCount** enemies have logged in within the last **$windowMinutes** minutes " +
        s"on **$world** — that's over your alert of **$threshold**.\n\n" +
        s"There are **$enemiesOnline** enemies online in total.")
      .setFooter(s"$guildName • you asked for mass log alerts on this world")
      .build()

  /** The row under a mass-log alert: be rid of it, quieten it for a while, or
   *  change what counts as a mass log.
   *
   *  Remove rather than Disable, matching a bounty alert. Switching off left a
   *  row behind that said "off" while the Mass Log role stayed the only visible
   *  sign of the subscription — two half-states for one answer. Removing deletes
   *  the row and takes the role with it, and the DM keeps the way back.
   *
   *  Enable only ever appears on a subscription already switched off, which no
   *  new alert can be: a disabled one does not send. It is here for the DMs
   *  sitting in inboxes from before Remove existed, whose Disable button still
   *  works — pressing it must not leave someone switched off with nothing
   *  offering to turn them back on. */
  def masslogControls(sub: MasslogSub): ActionRow = {
    val row = List(
      Button.danger(NotifyIds.masslogDrop(sub.id), "Remove"),
      muteButton(NotifyIds.masslogMute(sub.id), sub.mutedUntil),
      Button.primary(NotifyIds.masslogThreshold(sub.id), s"Alert at ${sub.threshold}"))
    ActionRow.of(
      (if (sub.enabled) row
       else Button.success(NotifyIds.masslogToggle(sub.id, enable = true), "Enable") :: row).asJava)
  }

  /** What Remove leaves behind: the way back.
   *
   *  The threshold rides in the button so pressing it restores the alert the
   *  reader had rather than the default — the number they chose is the whole
   *  content of the subscription, and losing it would make Remove destructive in
   *  a way a single press should not be. */
  def masslogRestoreControls(sub: MasslogSub): ActionRow =
    ActionRow.of(
      Button.success(NotifyIds.masslogAgain(sub.guildId, sub.world, sub.threshold), "Turn back on"))

  /** The ephemeral reply to pressing the Mass Log button, and to adjusting the
   *  threshold from a DM. Same controls as a real alert carries, so the settings
   *  are adjustable from here too. */
  def masslogSettings(sub: MasslogSub, world: String, headline: String): MessageEmbed =
    new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setDescription(
        s"$headline\n\n" +
        s"${Config.masslogEmoji} I'll DM you when **${sub.threshold}** or more enemies log in on " +
        s"**$world** within **$windowMinutes** minutes.")
      .setFooter(status(sub.enabled, sub.mutedUntil))
      .build()

  // --- bounty ------------------------------------------------------------

  def bountyDm(world: String, guildName: String, character: String, level: Int, vocation: String): MessageEmbed = {
    val vocationLine = if (vocation.trim.isEmpty) "" else s" — $vocation"
    new EmbedBuilder()
      .setColor(Embeds.NemesisPurple)
      .setTitle(s"${Config.bountyEmoji} $character has logged in", Urls.charUrl(character))
      .setDescription(
        s"**[$character](${Urls.charUrl(character)})** is online on **$world**.\n" +
        s"Level **$level**$vocationLine")
      .setFooter(s"$guildName • one of your tracked bounties")
      .build()
  }

  /** The row under a bounty alert: be rid of this one, or just have it quiet
   *  for a while.
   *
   *  Remove where a mass-log alert offers Disable, because the two subscriptions
   *  aren't the same shape. There is one mass-log subscription per world and
   *  switching it off is the only sense in which you can be done with it; a
   *  bounty is one name among several, and being done with a name means it
   *  should stop taking up a line on the list. Mute stays either way — "not
   *  tonight" is a different answer from "not again".
   *
   *  Enable only appears on one already switched off, which no new alert can be:
   *  a disabled subscription doesn't send. It is here for the DMs sitting in
   *  people's inboxes from before Remove existed, whose Disable button still
   *  works — pressing it must not leave the bounty off with nothing anywhere
   *  offering to turn it back on. */
  def bountyControls(sub: BountySub): ActionRow = {
    val row = List(
      Button.danger(NotifyIds.bountyDrop(sub.id), "Remove"),
      muteButton(NotifyIds.bountyMute(sub.id), sub.mutedUntil))
    ActionRow.of(
      (if (sub.enabled) row
       else Button.success(NotifyIds.bountyToggle(sub.id, enable = true), "Enable") :: row).asJava)
  }

  /** What Remove leaves behind it: the way back.
   *
   *  Remove asks nothing before it deletes, which is right for a button pressed
   *  one-handed at an awkward hour — so the undo lives here instead, after the
   *  fact, where it costs a mis-tap one press rather than costing everybody else
   *  a confirmation step. It restores the cooldown that was set, not the
   *  default: getting the name back is not the same as getting the setting back.
   *
   *  The disabled marker is the fallback for an id too long to carry all that,
   *  which nothing real should reach — see NotifyIds.MaxCustomId. */
  def bountyRestoreControls(removed: BountySub): ActionRow = {
    val again = NotifyIds.bountyTrackAgain(removed.guildId, removed.world, removed.character, removed.cooldownMinutes)
    if (again.length <= NotifyIds.MaxCustomId) ActionRow.of(Button.success(again, "Track again"))
    else ActionRow.of(Button.secondary(NotifyIds.bountyRemoved, "Removed").asDisabled)
  }

  /** The ephemeral panel behind the Bounty button: everything this user is
   *  watching on the world, with whatever just happened said above it.
   *
   *  Pressing the button used to open the add form on the spot, which left the
   *  list — and anyone wanting off it — with nowhere to be seen. The list is
   *  where the button lands now, and adding is one of the two things offered
   *  from it. */
  def bountyPanel(held: List[BountySub], world: String, headline: String): MessageEmbed = {
    val list =
      if (held.isEmpty) "*You aren't watching anyone on this world.*"
      else held.map { sub =>
        val quiet =
          if (!sub.enabled) " *(off)*"
          else sub.mutedUntil.filter(_.isAfter(Instant.now())).map(until => s" *(muted until <t:${until.getEpochSecond}:t>)*").getOrElse("")
        s"${Config.bountyEmoji} **[${sub.character}](${Urls.charUrl(sub.character)})** — ${sub.cooldownMinutes}m cooldown$quiet"
      }.mkString("\n")

    new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setTitle(s"Bounties on $world")
      .setDescription(s"$headline\n\n${EmbedText.fit(list)}")
      .setFooter(listStatus(held))
      .build()
  }

  /** The panel's own two buttons. Both act on the list rather than on any one
   *  row, so neither carries a subscription id — Disable and Mute belong to a
   *  single bounty, and stay where a single bounty is being talked about: under
   *  the DM it sent.
   *
   *  Remove is greyed out rather than dropped when there is nothing to remove,
   *  so the panel doesn't change shape between one visit and the next. */
  def bountyPanelControls(world: String, held: List[BountySub]): ActionRow = {
    val remove = Button.danger(NotifyIds.bountyRemove(world), "Remove")
    ActionRow.of(
      Button.success(NotifyIds.bountyAdd(world), "Add"),
      if (held.isEmpty) remove.asDisabled else remove)
  }

  /** The panel's footer: whether anything on this list can still reach the
   *  reader at all. Which one is off, or muted until when, is on its own row. */
  private def listStatus(held: List[BountySub]): String =
    if (held.isEmpty) "Nothing tracked here yet"
    else if (!held.exists(_.enabled)) "Notifications are off"
    else "Notifications are on"

  /** Names run together the way somebody would say them. Used for what a Remove
   *  has just taken off the list: a count alone gives the reader no way to check
   *  they picked the ones they meant. */
  def nameList(names: List[String]): String = names match {
    case Nil           => ""
    case single :: Nil => s"**$single**"
    case many          => s"${many.init.map(name => s"**$name**").mkString(", ")} and **${many.last}**"
  }

  // --- shared controls ---------------------------------------------------

  /** Reads as what pressing it does, not as what the state is: a live
   *  subscription offers Disable, and pressing it leaves an Enable in its place.
   *  Both ids point at the same row, so the message can be edited in place. */
  private def toggleButton(enabled: Boolean, id: String): Button =
    if (enabled) Button.danger(id, "Disable") else Button.success(id, "Enable")

  private def muteButton(id: String, mutedUntil: Option[Instant]): Button =
    if (mutedUntil.exists(_.isAfter(Instant.now()))) Button.secondary(id, "Muted").withEmoji(Emoji.fromUnicode("🔕"))
    else Button.secondary(id, "Mute").withEmoji(Emoji.fromUnicode("🔔"))

  def muteConfirmation(minutes: Int, until: Instant): String =
    if (minutes == MuteScale.Unmute) s"${Config.yesEmoji} Unmuted — you'll hear from me again."
    else s"${Config.yesEmoji} Muted for **${MuteScale.label(minutes)}**, until <t:${until.getEpochSecond}:t>."
}
