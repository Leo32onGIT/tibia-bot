package com.tibiabot.panels

import com.tibiabot.domain.Worlds
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.{SelectOption, StringSelectMenu}
import net.dv8tion.jda.api.components.textinput.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.modals.Modal

import scala.jdk.CollectionConverters._

/** The forms behind the panel buttons, and the small vocabulary they share.
 *
 *  ==Where the current value comes from==
 *  A form both shows a setting and takes a new one, which needs the world
 *  decided before the form is built. With one world tracked — what most servers
 *  have — there is nothing to ask, so the world field is left out entirely and
 *  every box opens already filled in with what the setting is now.
 *
 *  With several, the world is picked inside the form, which is necessarily
 *  before anything could know which world's values to show. Those boxes are
 *  optional instead, and blank means leave it as it is; the panel's own message
 *  carries the current values for every world, so they are still one glance away
 *  rather than hidden behind a form. Message-level select menus would let the
 *  world be chosen first, but nothing in this bot routes those yet, and a form
 *  that can also be read is worth more than the extra hop it would save.
 */
object PanelForms {

  val WorldField = "world"
  val LevelField = "level"
  val OptionField = "option"
  val LevelsField = "levels"
  val DeathsField = "deaths"
  val ActivityField = "activity"
  val EnemiesField = "enemies"
  val AlliesField = "allies"
  val NeutralsField = "neutrals"
  val NamesField = "names"
  val KindField = "kind"
  val ReasonField = "reason"
  val NameField = "name"

  /** Discord rejects the whole modal if a label passes 45 characters or its
   *  description 100 — as RespawnModals found, it fails rather than trimming. */
  private def clamp(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max - 1).trim + "\u2026"

  def label(text: String, description: String, child: net.dv8tion.jda.api.components.label.LabelChildComponent): Label =
    Label.of(clamp(text, Label.LABEL_MAX_LENGTH), clamp(description, Label.DESCRIPTION_MAX_LENGTH), child)

  /** The world picker, for a guild with more than one. Returns None when there
   *  is nothing to choose, which is what lets the rest of the form pre-fill. */
  def worldPicker(worlds: List[Worlds]): Option[Label] =
    if (worlds.sizeIs <= 1) None
    else Some(label("Which world?", "The world this setting applies to.",
      StringSelectMenu.create(WorldField)
        .setPlaceholder("Pick a world")
        .addOptions(worlds.map(w => SelectOption.of(w.name, w.name)).sortBy(_.getLabel).asJava)
        .setRequiredRange(1, 1)
        .build()))

  /** A show/hide style picker, pre-selected when the current value is known.
   *
   *  `current` is None for a multi-world guild, where the world is being chosen
   *  in this same form — the menu is then optional, and picking nothing leaves
   *  the setting alone. */
  def choice(id: String, text: String, description: String,
             options: List[(String, String)], current: Option[String]): Label = {
    val menu = StringSelectMenu.create(id)
      .setPlaceholder(if (current.isDefined) "Leave as it is" else "Leave unchanged")
      .addOptions(options.map { case (optionLabel, value) =>
        SelectOption.of(optionLabel, value).withDefault(current.contains(value))
      }.asJava)
      .setRequired(false)
      .build()
    label(text, description, menu)
  }

  /** A number box, pre-filled when the current value is known. */
  def number(id: String, text: String, description: String, current: Option[Int],
             placeholder: String): Label = {
    val input = TextInput.create(id, TextInputStyle.SHORT)
      .setPlaceholder(placeholder)
      .setRequired(false)
      .setMaxLength(4)
    current.foreach(value => input.setValue(value.toString))
    label(text, description, input.build())
  }

  val ShowHide: List[(String, String)] = List("Show" -> "show", "Hide" -> "hide")
  val OnOff: List[(String, String)] = List("On" -> "on", "Off" -> "off")
  val SeparateCombine: List[(String, String)] = List("Separate channels" -> "separate", "One channel" -> "combine")

  /** "true"/"false" is how these are stored; the forms speak show/hide. */
  def showHideOf(stored: String): String = if (stored == "true") "show" else "hide"

  def build(id: String, title: String, parts: List[Label]): Modal =
    Modal.create(id, clamp(title, 45)).addComponents(parts.asJava).build()
}
