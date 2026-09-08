package com.tibiabot.panels

import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelForms._
import com.tibiabot.panels.PanelIds.Panel
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.modals.Modal

/** The six forms behind `/settings`.
 *
 *  Each was a whole top-level command once — `/fullbless`, `/exiva`, `/online`,
 *  `/neutral` and `/filter` — and between them they cost nine rows of the command
 *  picker. `filter` is two forms rather than one because world plus five level
 *  floors is six components and Discord allows five; splitting it by what the
 *  floor applies to (a channel, or an online list) is the seam that reads.
 */
object SettingsForms {

  /** None when there is nothing to configure — no worlds set up yet.
   *
   *  @param commandLog the command log's current channel id, for the one form
   *                    that is about the server rather than a world. Only pass a
   *                    channel that still exists — see PanelForms.channelPicker.
   */
  def modal(action: String, worlds: List[Worlds], commandLog: Option[String] = None): Option[Modal] = {
    if (worlds.isEmpty) return None
    // With one world its values are known now, so every box opens filled in.
    // With several the world is picked in the form, so nothing can be.
    val only: Option[Worlds] = if (worlds.sizeIs == 1) worlds.headOption else None
    val picker: List[Label] = worldPicker(worlds).toList

    val form = action match {
      case PanelIds.Fullbless =>
        Some("Fullbless level", picker :+ number(LevelField, "Fullbless level",
          "Enemy fullblesses at or above this level poke the role.",
          only.map(_.fullblessLevel), "250"))

      case PanelIds.Exiva =>
        Some("Exiva lists", picker :+ choice(OptionField, "Exiva list on deaths",
          "Whether a death post lists who can exiva the killer.",
          ShowHide, only.map(w => showHideOf(w.exivaList))))

      case PanelIds.Layout =>
        Some("Online list layout", picker :+ choice(OptionField, "Online list",
          "One channel for everyone, or a channel per side.",
          SeparateCombine, only.map(_.onlineCombined).map(v => if (v == "combine") "combine" else "separate")))

      case PanelIds.Neutral =>
        Some("Neutral players", picker ++ List(
          choice(LevelsField, "Neutral levels", "Level-ups by players you don't track.",
            ShowHide, only.map(w => showHideOf(w.showNeutralLevels))),
          choice(DeathsField, "Neutral deaths", "Deaths of players you don't track.",
            ShowHide, only.map(w => showHideOf(w.showNeutralDeaths)))))

      case PanelIds.ChannelFilter =>
        Some("Channel level filters", picker ++ List(
          number(LevelsField, "Levels channel", "Hide level-ups below this level.",
            only.map(_.levelsMin), "8"),
          number(DeathsField, "Deaths channel", "Hide deaths below this level.",
            only.map(_.deathsMin), "8")))

      case PanelIds.OnlineFilter =>
        Some("Online list filters", picker ++ List(
          number(EnemiesField, "Enemies list", "Hide enemies below this level; 0 shows everyone.",
            only.map(_.onlineEnemiesMin), "0"),
          number(AlliesField, "Allies list", "Hide allies below this level; 0 shows everyone.",
            only.map(_.onlineAlliesMin), "0"),
          number(NeutralsField, "Neutrals list", "Hide neutrals below this level; 0 shows everyone.",
            only.map(_.onlineNeutralsMin), "0")))

      // No world picker: there is one command log per server, not one per world.
      // It sits on this panel anyway because that is where somebody looking for
      // "where does the bot post" goes — a command of its own would be a
      // thirty-fifth row of the picker for a thing set once.
      case PanelIds.CommandLog =>
        Some("Command log", List(channelPicker("Command log channel",
          "Change where the bot posts its logs:", commandLog)))

      case _ => None
    }

    form.map { case (title, parts) => build(PanelIds.form(Panel.Settings, action), title, parts) }
  }
}
