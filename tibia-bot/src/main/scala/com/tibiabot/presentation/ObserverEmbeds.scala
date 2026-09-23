package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.{MiniWorldChange, ObserverStatus, ObserverToken, RaidAnnouncement}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed

/** The `/observer` panel: a member's own Tibia Observer token status, with the
 *  Add / Remove controls. Ephemeral, so it only ever shows one member their own
 *  link. */
object ObserverEmbeds {

  private val tokenPage = "https://www.tibia.com/account/?subtopic=accountmanagement&page=tibiaobserver"

  def panel(token: Option[ObserverToken]): MessageEmbed = {
    val body = token match {
      case None =>
        s"""${Config.noEmoji} You have no **Tibia Observer** token configured.
           |
           |Press **Add** and paste the token from your [Tibia account]($tokenPage)
           |(*Account Management → Tibia Observer → Connect*). You'll get mini world change
           |alerts, and a raids channel for each world this server tracks — pooled from every
           |linked member.""".stripMargin
      case Some(t) =>
        s"""${statusLine(t)}
           |
           |Press **Remove** to unlink.""".stripMargin
    }
    new EmbedBuilder()
      .setTitle("Tibia Observer")
      .setColor(Embeds.BrandColor)
      .setDescription(body)
      .build()
  }

  private def statusLine(t: ObserverToken): String = t.status match {
    case ObserverStatus.Pending =>
      s"${Config.yesEmoji} Token saved — it will be verified once linking is enabled."
    case ObserverStatus.Linked =>
      val who = t.accountLabel.map(a => s" as **$a**").getOrElse("")
      val where = t.world.map(w => s" on **$w**").getOrElse("")
      s"${Config.yesEmoji} Linked$who$where."
    case ObserverStatus.NeedsRelink =>
      s"${Config.noEmoji} Your link needs renewing — press **Add** with a fresh token."
    case ObserverStatus.Error =>
      s"${Config.noEmoji} Something went wrong with your link — try **Add** again."
  }

  /** The Mini World Changes section appended to the boosted server-save DM, for a
   *  member with a linked Observer token. `None` when nothing is active, so the DM
   *  is unchanged for a quiet day. */
  def mwcEmbed(changes: List[MiniWorldChange]): Option[MessageEmbed] =
    if (changes.isEmpty) None
    else {
      val body = changes
        .take(12)
        .map(c => s"### ${c.title} ${Config.indentEmoji}*${c.world}*\n${c.body}")
        .mkString("\n\n")
        .take(4000)
      Some(new EmbedBuilder()
        .setTitle("Mini World Changes")
        .setColor(Embeds.BrandColor)
        .setDescription(body)
        .build())
    }

  /** One raid announcement for the raids channel. `category` is the stage the feed
   *  reported it at; `startDate` renders as a live relative timestamp. */
  def raidEmbed(raid: RaidAnnouncement): MessageEmbed = {
    val stage = raid.category match {
      case "areaRevealed"    => "Area revealed"
      case "subareaRevealed" => "Subarea revealed"
      case "raidStarted"     => "Raid started"
      case other             => other
    }
    val where = raid.subarea.filter(_.nonEmpty).map(s => s"${raid.area} · $s").getOrElse(raid.area)
    val starts = raid.startDate.map(d => s" — starts <t:${d.getEpochSecond}:R>").getOrElse("")
    new EmbedBuilder()
      .setColor(Embeds.AutomaticColor)
      .setTitle(s"$where — ${raid.world}")
      .setDescription(s"$stage$starts")
      .build()
  }

  /** Add is offered when there is no token; Remove when there is one. The other is
   *  shown disabled so the panel always reads as a pair (as `/boosted` does). The
   *  world is asked for inside the Add form, not here. */
  def controls(token: Option[ObserverToken]): ActionRow =
    if (token.isDefined)
      ActionRow.of(
        Button.success("observer add", "Add").asDisabled,
        Button.danger("observer remove", "Remove"))
    else
      ActionRow.of(
        Button.success("observer add", "Add"),
        Button.danger("observer remove", "Remove").asDisabled)
}
