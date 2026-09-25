package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.Worlds
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.emoji.Emoji

import scala.jdk.CollectionConverters._

/** The card in a guild's notifications channel that sets up a world's alerts:
 *  one row per role, with the button that sets it up beside it.
 *
 *  Laid out with Discord's layout components like the cooldown tracker above it,
 *  so it is sent with `useComponentsV2`. It was an embed with the five buttons in
 *  a row underneath until 25 Sep 2026; [[worldOf]] still reads those, because the
 *  buttons on a card nobody has reposted yet must keep working.
 *
 *  The button ids are the bare ones they always were (`fullbless`, `nemesis`,
 *  `allypk`, `masslog`, `bounty`), so the handlers never needed the world in
 *  them: the card's id is kept on its world (`Worlds.roleCardMessage`), and a
 *  press looks the world up by the message it came from. The first three toggle a role
 *  that gets pinged in a channel. The last two open a form that sets up a DM
 *  subscription instead (see interactions.NotifyButtons), and the role follows
 *  whatever that settles on.
 */
object RoleCard {

  /** What the heading's grey line says, under the world's name. */
  val Lead = "Assign yourself to roles to be notified for their related events"

  private val Swords = ":crossed_swords:"

  private def configuredEmoji(buttonId: String): String = buttonId match {
    case "fullbless" => Config.inqEmoji
    case "nemesis"   => Config.bossEmoji
    case "allypk"    => Config.hazardEmoji
    case "masslog"   => Config.masslogEmoji
    case _           => Config.bountyEmoji
  }

  /** The card for one world. `level` is a String because `/repair` reads it
   *  straight out of the stored world config. `emojiOf` takes a button id and
   *  defaults to the configured emoji; a test passes its own. */
  def card(world: String, fullblessRoleId: String, nemesisRoleId: String, allyPkRoleId: String,
           masslogRoleId: String, bountyRoleId: String, level: String,
           emojiOf: String => String = configuredEmoji): Container = {
    // A world set up before bounties existed carries '0' until /repair creates
    // the role, and `<@&0>` renders as a deleted role, which reads as broken
    // rather than as not set up yet.
    val bountyMention = if (bountyRoleId == null || bountyRoleId == "0") "**Bounty**" else s"<@&$bountyRoleId>"
    val rows = List(
      (Button.success("fullbless", " "), s"<@&$fullblessRoleId>", s"If an enemy fullblesses and is over level `$level`"),
      (Button.primary("nemesis", " "), s"<@&$nemesisRoleId>", "If anyone dies to a rare boss"),
      (Button.danger("allypk", " "), s"<@&$allyPkRoleId>", "If an ally gets pked"),
      (Button.secondary("masslog", " "), s"<@&$masslogRoleId>", s"If enough enemies log in at once on **$world**"),
      (Button.secondary("bounty", " "), bountyMention, s"If a character you're watching `logs in` on **$world**")
    ).map { case (button, role, what) =>
      Section.of(button.withEmoji(Emoji.fromFormatted(emojiOf(button.getCustomId))), TextDisplay.of(s"$role\n-# $what"))
    }
    val heading = TextDisplay.of(s"### $Swords [$world](${Urls.worldUrl(world)})\n-# $Lead")
    Container.of((List[ContainerChildComponent](heading, Separator.createDivider(Separator.Spacing.SMALL)) ++ rows).asJava)
  }

  /** The world a pressed role card is for: whichever of the guild's worlds has
   *  its id on record.
   *
   *  A card posted before ids were kept (25 Sep 2026) has none on record, so its
   *  embed title is read instead. That is only there so the buttons on those
   *  cards keep working until `/repair` replaces them, and can go once none are
   *  left. */
  def worldOf(message: Message, worlds: List[Worlds]): Option[String] =
    worlds.find(_.roleCardMessage == message.getId).map(_.name)
      .orElse(if (message.isUsingComponentsV2) None else legacyWorldOf(message))

  /** The world an embed card from before the cards names in its title. Also how
   *  `/repair` finds such a card, once, to replace it. */
  def legacyWorldOf(message: Message): Option[String] =
    message.getEmbeds.asScala.headOption.flatMap(e => Option(e.getTitle)).flatMap(worldOfTitle)

  /** The world an embed card's title names: `:crossed_swords: Antica :crossed_swords:`. */
  def worldOfTitle(title: String): Option[String] =
    if (title.startsWith(Swords)) Some(title.replace(Swords, "").trim).filter(_.nonEmpty) else None
}
