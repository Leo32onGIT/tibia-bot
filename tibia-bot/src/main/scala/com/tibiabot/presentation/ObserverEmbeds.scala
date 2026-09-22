package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.{ObserverStatus, ObserverToken}
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
           |(*Account Management → Tibia Observer → Connect*) to receive mini world
           |change notifications.""".stripMargin
      case Some(t) =>
        s"""${statusLine(t)}
           |
           |Press **Remove** to unlink, or **Add** to replace it with a new token.""".stripMargin
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
      val where = t.world.map(w => s" on **$w**").getOrElse("")
      s"${Config.yesEmoji} Linked$where."
    case ObserverStatus.NeedsRelink =>
      s"${Config.noEmoji} Your link needs renewing — press **Add** with a fresh token."
    case ObserverStatus.Error =>
      s"${Config.noEmoji} Something went wrong with your link — try **Add** again."
  }

  /** Add is offered when there is no token; Remove when there is one. The other is
   *  shown disabled so the panel always reads as a pair (as `/boosted` does). */
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
