package com.tibiabot.interactions

import com.tibiabot.domain.{BulkListOutcome, Worlds}
import com.tibiabot.panels.PanelIds.Panel
import com.tibiabot.panels.{ListForms, NameList, PanelForms, PanelIds}
import com.tibiabot.presentation.{Embeds, PanelReplies}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Submissions from the panel forms: this is where a setting is actually written
 *  and where a pasted list is actually added.
 *
 *  Always deferred by BotListener before it gets here - a bulk add is a hundred
 *  API lookups and nothing else here is instant either - so every reply goes
 *  through the hook.
 *
 *  ==Blank means unchanged==
 *  A form on a guild with several worlds cannot know which world's values to show
 *  until one is picked, so its boxes open empty and empty is taken to mean "leave
 *  it alone" rather than "set it to nothing". On a single-world guild the same
 *  boxes open pre-filled, so leaving one untouched submits the value it already
 *  had, which comes to the same thing.
 */
object PanelModals extends StrictLogging {

  def handles(modalId: String): Boolean = PanelIds.handlesForm(modalId)

  def handle(event: ModalInteractionEvent)(implicit ec: ExecutionContext): Unit =
    PanelIds.parse(event.getModalId) match {
      case None =>
        reply(event, s"${Config.noEmoji} That form is out of date - run the command again.")
      case Some((panel, action)) =>
        val guild = event.getGuild
        if (guild == null) reply(event, s"${Config.noEmoji} That only works inside a server.")
        else if (panel == Panel.Settings) applySetting(event, action)
        else applyList(event, panel, action)
    }

  // --- /settings -----------------------------------------------------------

  private def applySetting(event: ModalInteractionEvent, action: String): Unit = {
    val guildId = event.getGuild.getId
    val worlds = BotApp.worldsData.getOrElse(guildId, List())
    worldOf(event, worlds) match {
      case None => reply(event, s"${Config.noEmoji} Pick a world first.")
      case Some(world) =>
        val service = BotApp.worldSettingsService
        val name = world.name
        val embeds: List[MessageEmbed] = action match {
          case PanelIds.Fullbless =>
            number(event, PanelForms.LevelField).map(level => service.fullblessLevel(event, name, level)).toList

          case PanelIds.Exiva =>
            choice(event, PanelForms.OptionField).map(v => service.exivaList(event, name, v)).toList

          case PanelIds.Layout =>
            choice(event, PanelForms.OptionField).map(v => service.onlineListConfig(event, name, v)).toList

          case PanelIds.Neutral =>
            List(
              choice(event, PanelForms.LevelsField).map(v => service.deathsLevelsHideShow(event, name, v, "neutrals", "levels")),
              choice(event, PanelForms.DeathsField).map(v => service.deathsLevelsHideShow(event, name, v, "neutrals", "deaths")),
              choice(event, PanelForms.ActivityField).map(v => service.deathsLevelsHideShow(event, name, v, "neutrals", "activity"))
            ).flatten

          case PanelIds.ChannelFilter =>
            List(
              number(event, PanelForms.LevelsField).map(v => service.minLevel(event, name, v, "levels")),
              number(event, PanelForms.DeathsField).map(v => service.minLevel(event, name, v, "deaths"))
            ).flatten

          case PanelIds.OnlineFilter =>
            List(
              number(event, PanelForms.EnemiesField).map(v => service.onlineMinLevel(event, name, v, "enemies")),
              number(event, PanelForms.AlliesField).map(v => service.onlineMinLevel(event, name, v, "allies")),
              number(event, PanelForms.NeutralsField).map(v => service.onlineMinLevel(event, name, v, "neutrals"))
            ).flatten

          case _ => Nil
        }
        if (embeds.isEmpty) reply(event, s"${Config.noEmoji} Nothing was changed - every box was left blank.")
        else embeds.foreach(embed => event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue())
    }
  }

  // --- /hunted and /allies -------------------------------------------------

  private def applyList(event: ModalInteractionEvent, panel: Panel, action: String)
                       (implicit ec: ExecutionContext): Unit = {
    val guild = event.getGuild
    val hunted = panel == Panel.Hunted
    val service = BotApp.huntedAlliedService
    action match {
      case PanelIds.Add | PanelIds.Remove =>
        val kind = choice(event, PanelForms.KindField).getOrElse("player")
        val parsed = NameList.parse(text(event, PanelForms.NamesField))
        val (names, overflow) = NameList.take(parsed, ListForms.MaxNames)
        if (names.isEmpty) reply(event, s"${Config.noEmoji} No names in that - one per line.")
        else if (action == PanelIds.Add) {
          val reason = text(event, PanelForms.ReasonField)
          service.addMany(guild, hunted, kind, names, reason, event.getUser.getId)
            .map(outcome => finishBulk(event, panel, kind, adding = true, outcome, overflow))
            .recover { case NonFatal(ex) =>
              logger.error(s"Bulk add failed for guild ${guild.getId}", ex)
              reply(event, s"${Config.noEmoji} Something went wrong partway through - check the list and try again.")
            }
        } else {
          val outcome = service.removeMany(guild, hunted, kind, names)
          finishBulk(event, panel, kind, adding = false, outcome, overflow)
        }

      case PanelIds.Info =>
        val name = text(event, PanelForms.NameField)
        val embed =
          if (hunted) service.infoHunted(event, "player", name)
          else service.infoAllies(event, "player", name)
        event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()

      case PanelIds.Config =>
        applyDisplay(event, panel)

      case _ =>
        reply(event, s"${Config.noEmoji} That isn't available here.")
    }
  }

  private def finishBulk(event: ModalInteractionEvent, panel: Panel, kind: String,
                         adding: Boolean, outcome: BulkListOutcome, overflow: List[String]): Unit = {
    val full = outcome.copy(skipped = overflow)
    // One post for the batch rather than one per name - see logBulk.
    if (adding) BotApp.huntedAlliedService.logBulk(event.getGuild, panel == Panel.Hunted, adding = true,
      event.getUser.getName, full)
    event.getHook.sendMessageEmbeds(PanelReplies.bulkEmbed(panel, kind, adding, full)).setEphemeral(true).queue()
  }

  private def applyDisplay(event: ModalInteractionEvent, panel: Panel): Unit = {
    val worlds = BotApp.worldsData.getOrElse(event.getGuild.getId, List())
    worldOf(event, worlds) match {
      case None => reply(event, s"${Config.noEmoji} Pick a world first.")
      case Some(world) =>
        val service = BotApp.worldSettingsService
        val side = if (panel == Panel.Hunted) "enemies" else "allies"
        val embeds = List(
          choice(event, PanelForms.LevelsField).map(v => service.deathsLevelsHideShow(event, world.name, v, side, "levels")),
          choice(event, PanelForms.DeathsField).map(v => service.deathsLevelsHideShow(event, world.name, v, side, "deaths")),
          // Hunted only; the allies form does not draw this box at all.
          choice(event, PanelForms.ActivityField).filter(_ => panel == Panel.Hunted)
            .map(v => service.detectHunted(event, world.name, v))
        ).flatten
        if (embeds.isEmpty) reply(event, s"${Config.noEmoji} Nothing was changed - every box was left blank.")
        else embeds.foreach(embed => event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue())
    }
  }

  // --- reading the form ----------------------------------------------------

  /** Which world the form applies to.
   *
   *  A single-world guild has no picker - there was nothing to ask - so the world
   *  is that one. Otherwise it is whatever was picked, matched case-insensitively
   *  against what the guild actually tracks so a stale form cannot write to a
   *  world that has since been removed.
   */
  private def worldOf(event: ModalInteractionEvent, worlds: List[Worlds]): Option[Worlds] =
    if (worlds.sizeIs == 1) worlds.headOption
    else selected(event, PanelForms.WorldField).headOption
      .flatMap(picked => worlds.find(_.name.equalsIgnoreCase(picked)))

  /** A text box's contents, or empty. */
  private def text(event: ModalInteractionEvent, id: String): String =
    Option(event.getValue(id)).map(_.getAsString.trim).getOrElse("")

  /** What was picked in a select. Not interchangeable with [[text]]: a select's
   *  answer refuses `getAsString` and throws rather than coming back empty, which
   *  RespawnModals found the hard way. */
  private def selected(event: ModalInteractionEvent, id: String): List[String] =
    Option(event.getValue(id))
      .map(_.getAsStringList.asScala.toList).getOrElse(Nil)
      .map(_.trim).filter(_.nonEmpty)

  /** A select left alone is None, which every caller reads as "leave it". */
  private def choice(event: ModalInteractionEvent, id: String): Option[String] =
    selected(event, id).headOption

  /** A number box left alone is None. A box filled with something that is not a
   *  number is also None rather than zero: zero is a real setting for the online
   *  filters (it turns them off), so guessing it from a typo would silently
   *  change something nobody asked to change.
   */
  private def number(event: ModalInteractionEvent, id: String): Option[Int] =
    text(event, id) match {
      case "" => None
      case raw => scala.util.Try(raw.trim.toInt).toOption
    }

  private def reply(event: ModalInteractionEvent, text: String): Unit =
    event.getHook.sendMessageEmbeds(Embeds.response(text)).setEphemeral(true).queue()
}
