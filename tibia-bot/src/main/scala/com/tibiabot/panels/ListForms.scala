package com.tibiabot.panels

import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelForms._
import com.tibiabot.panels.PanelIds.Panel
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.modals.Modal

/** The forms behind `/hunted` and `/allies`.
 *
 *  ==Why Add and Remove take a whole list==
 *  A war server adds an enemy guild's roster a name at a time, and Discord will
 *  not open a second form from a submitted one — so a form per name would mean a
 *  button press between every one of them. A paragraph box takes the lot in one
 *  paste, which is how the names arrive anyway: out of a spreadsheet, a Discord
 *  message, or the guild page. `TextInput.MAX_VALUE_LENGTH` is 4000 characters,
 *  comfortably more than a hundred names.
 *
 *  Remove is the same shape and far cheaper: it is decided by what is on the
 *  list, so it asks Tibia's API nothing at all.
 */
object ListForms {

  /** More than this in one paste is refused rather than silently truncated — see
   *  [[com.tibiabot.panels.NameList]] for why there is a ceiling at all. */
  val MaxNames: Int = 100

  private def kindPicker(panel: Panel, verb: String): Label =
    choice(KindField, s"$verb players or guilds?",
      "Guilds pull in their whole member list.",
      List("Players" -> "player", "Guilds" -> "guild"),
      Some("player"))

  private def namesBox(verb: String, hint: String): Label =
    label("Names", hint,
      TextInput.create(NamesField, TextInputStyle.PARAGRAPH)
        .setPlaceholder("One per line — paste a whole list if you have one")
        .setRequired(true)
        .setMaxLength(TextInput.MAX_VALUE_LENGTH)
        .build())

  def modal(panel: Panel, action: String, worlds: List[Worlds]): Option[Modal] = {
    val only: Option[Worlds] = if (worlds.sizeIs == 1) worlds.headOption else None
    val picker: List[Label] = worldPicker(worlds).toList

    val form = action match {
      case PanelIds.Add =>
        Some(s"Add to the ${panel.noun}", List(
          kindPicker(panel, "Add"),
          namesBox("Add", s"Up to $MaxNames at a time."),
          label("Reason", "Optional, and applies to every name here.",
            TextInput.create(ReasonField, TextInputStyle.SHORT)
              .setPlaceholder("Why are these being added?")
              .setRequired(false)
              .setMaxLength(200)
              .build())))

      case PanelIds.Remove =>
        Some(s"Remove from the ${panel.noun}", List(
          kindPicker(panel, "Remove"),
          namesBox("Remove", s"Up to $MaxNames at a time.")))

      case PanelIds.Info =>
        Some("Look someone up", List(
          label("Name", "A player already on the list.",
            TextInput.create(NameField, TextInputStyle.SHORT)
              .setPlaceholder("Character name")
              .setRequired(true)
              .setMaxLength(64)
              .build())))

      // The per-world display toggles that used to be `/hunted levels`,
      // `/hunted deaths` and `/hunted autodetect`.
      case PanelIds.Display =>
        val levels = only.map(w => showHideOf(if (panel == Panel.Hunted) w.showEnemiesLevels else w.showAlliesLevels))
        val deaths = only.map(w => showHideOf(if (panel == Panel.Hunted) w.showEnemiesDeaths else w.showAlliesDeaths))
        val side = if (panel == Panel.Hunted) "enemy" else "ally"
        val common = picker ++ List(
          choice(LevelsField, s"${side.capitalize} levels", s"Level-ups by $side players.", ShowHide, levels),
          choice(DeathsField, s"${side.capitalize} deaths", s"Deaths of $side players.", ShowHide, deaths))
        // Auto-detection is a hunted-only idea: there is no equivalent for allies.
        val parts =
          if (panel == Panel.Hunted)
            common :+ choice(ActivityField, "Auto-detect enemies",
              "Add players who join a hunted guild automatically.",
              OnOff, only.map(w => if (w.detectHunteds == "true") "on" else "off"))
          else common
        Some(s"${panel.noun.capitalize} display", parts)

      case _ => None
    }

    form.map { case (title, parts) => build(PanelIds.form(panel, action), title, parts) }
  }
}
