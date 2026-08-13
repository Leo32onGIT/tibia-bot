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
      .setFooter(s"$guildName • you're getting this because you have the Mass Log role")
      .build()

  def masslogControls(sub: MasslogSub): ActionRow =
    ActionRow.of(
      toggleButton(sub.enabled, NotifyIds.masslogToggle(sub.id, enable = !sub.enabled)),
      muteButton(NotifyIds.masslogMute(sub.id), sub.mutedUntil),
      Button.primary(NotifyIds.masslogThreshold(sub.id), s"Alert at ${sub.threshold}")
    )

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

  def bountyControls(sub: BountySub): ActionRow =
    ActionRow.of(
      toggleButton(sub.enabled, NotifyIds.bountyToggle(sub.id, enable = !sub.enabled)),
      muteButton(NotifyIds.bountyMute(sub.id), sub.mutedUntil)
    )

  /** The ephemeral reply to pressing the Bounty button: what was just added, and
   *  everything else this user is watching on the world. */
  def bountySettings(added: BountySub, held: List[BountySub], world: String, headline: String): MessageEmbed = {
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
      .setFooter(status(added.enabled, added.mutedUntil))
      .build()
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
