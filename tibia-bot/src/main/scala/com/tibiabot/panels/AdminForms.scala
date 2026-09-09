package com.tibiabot.panels

import com.tibiabot.panels.PanelForms._
import com.tibiabot.panels.PanelIds.Panel
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.modals.Modal

/** The two forms behind `/admin`: leaving a server, and sending its owner a
 *  message. Every other button on that panel acts on the press.
 *
 *  ==Why the server is typed rather than picked==
 *  A select menu holds twenty-five options and the bot is in far more guilds than
 *  that, so there is no picker that could offer them all. The id is typed, and
 *  the Server list button is what it is typed from. Nothing here checks it —
 *  [[com.tibiabot.admin.AdminService]] resolves it and answers "no server with
 *  that id" itself, so the same guard covers every caller rather than only this
 *  form.
 *
 *  Both bodies are paragraph boxes. Each ends up quoted into an embed the guild's
 *  owner reads, and a reason worth giving rarely fits on one line — the
 *  subcommands these replace took a single-line option and gave no way to break
 *  one.
 */
object AdminForms {

  /** Discord snowflakes are numeric and do not reach twenty digits; anything
   *  longer is a paste of something else. */
  private val IdMaxLength = 20

  private def idBox(verb: String): Label =
    label("Server id", s"The id of the server to $verb. Server list has them.",
      TextInput.create(GuildIdField, TextInputStyle.SHORT)
        .setPlaceholder("e.g. 867319250708463628")
        .setRequired(true)
        .setMaxLength(IdMaxLength)
        .build())

  private def bodyBox(id: String, text: String, description: String, placeholder: String): Label =
    label(text, description,
      TextInput.create(id, TextInputStyle.PARAGRAPH)
        .setPlaceholder(placeholder)
        .setRequired(true)
        .setMaxLength(1000)
        .build())

  /** None for an action with no form — the four buttons that act on the press. */
  def modal(action: String): Option[Modal] = {
    val form = action match {
      case PanelIds.Leave =>
        Some("Leave a server", List(
          idBox("leave"),
          bodyBox(ReasonField, "Reason",
            "Posted to their command log before the bot goes.",
            "Why is the bot leaving?")))

      case PanelIds.Message =>
        Some("Message a server", List(
          idBox("message"),
          bodyBox(MessageField, "Message",
            "Posted to their command log, from the bot's creator.",
            "What should they be told?")))

      case _ => None
    }
    form.map { case (title, parts) => build(PanelIds.form(Panel.Admin, action), title, parts) }
  }
}
