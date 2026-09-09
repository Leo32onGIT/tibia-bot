package com.tibiabot.panels

import com.tibiabot.Config
import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import com.tibiabot.presentation.Embeds
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji

import scala.jdk.CollectionConverters._

/** What `/settings`, `/hunted`, `/allies` and `/admin` actually answer with: an
 *  ephemeral message naming what the panel covers, and a button per thing you can
 *  do to it.
 *
 *  Ephemeral on purpose. These are one person's administrative errands, the reply
 *  is only ever useful to whoever ran the command, and Discord only lets the
 *  invoker press an ephemeral message's buttons — so the panel cannot be
 *  hijacked by somebody else in the channel.
 */
object Panels {

  private def row(buttons: List[Button]): ActionRow = ActionRow.of(buttons.asJava)

  /** Discord allows five buttons to a row, so anything longer is split rather
   *  than silently rejected. A panel may ask for fewer per row where the split
   *  itself carries meaning — see [[adminButtons]]. */
  private def rows(buttons: List[Button], perRow: Int = 5): List[ActionRow] =
    buttons.grouped(perRow).map(row).toList

  // --- /settings -----------------------------------------------------------

  /** Two of these come from config rather than being unicode picked here: the
   *  server's own exiva and blessing icons, so a setting is labelled with the
   *  same symbol the feature it configures already uses elsewhere.
   *
   *  A `def`, deliberately. As a `val` it read Config while this object was being
   *  initialised, which made merely touching Panels — from any test, for any
   *  reason — require a fully configured environment, and fail with an
   *  initialiser error where it did not have one. Read it when a button is
   *  actually being drawn instead. */
  private def settingsLabels: Map[String, (String, String)] = Map(
    PanelIds.Fullbless     -> ("Fullbless" -> Config.inqEmoji),
    PanelIds.Exiva         -> ("Exiva Lists" -> Config.exivaEmoji),
    PanelIds.ChannelFilter -> ("Channel Filters" -> "📊"),
    PanelIds.Layout        -> ("Online Layout" -> "📈"),
    PanelIds.OnlineFilter  -> ("Online Filters" -> "📋"),
    PanelIds.Neutral       -> ("Neutrals" -> "⚪"),
    PanelIds.CommandLog    -> ("Command Log" -> "🖥️")
  )

  def settingsButtons: List[ActionRow] =
    rows(PanelIds.settingsActions.map { action =>
      val (label, emoji) = settingsLabels(action)
      // fromFormatted rather than fromUnicode: these are a mix now, and the
      // custom ones arrive as "<:name:id>", which fromUnicode would take
      // literally. It reads plain unicode just as happily.
      Button.secondary(PanelIds.button(Panel.Settings, action), label)
        .withEmoji(Emoji.fromFormatted(emoji))
    })

  /** Names every world the panel can configure, so somebody with one world can
   *  see that at a glance and somebody with six knows the forms will ask which. */
  def settingsEmbed(worlds: List[Worlds]): MessageEmbed = {
    val worldList =
      if (worlds.isEmpty) "_No worlds are set up yet — run `/setup` first._"
      else worlds.map(w => s"**${w.name}**").sorted.mkString(", ")
    new EmbedBuilder()
      .setTitle("Server settings")
      .setDescription(
        "Pick what you want to change. Each one opens a form showing what it is " +
          "set to now, so you can check a setting without changing it.\n\n" +
          s"Tracking: $worldList")
      .setColor(Embeds.BrandColor)
      .build()
  }

  // --- /hunted and /allies -------------------------------------------------

  /** Label and emoji per button. An empty label means the button is drawn as the
   *  emoji alone — Discord has a factory for exactly that, and the two whose
   *  meaning the icon already carries do not need the word beside it. */
  private def listLabels(panel: Panel): Map[String, (String, String)] = Map(
    PanelIds.Add     -> ("Add" -> "➕"),
    PanelIds.Remove  -> ("Remove" -> "➖"),
    PanelIds.Config  -> ("Config" -> "⚙️"),
    PanelIds.Info    -> ("" -> "🔍"),
    PanelIds.Clear   -> ("Clear All" -> "🗑️")
  )

  def listButtons(panel: Panel): List[ActionRow] = {
    val labels = listLabels(panel)
    rows(PanelIds.listActions(panel).map { action =>
      val (label, emoji) = labels(action)
      val id = PanelIds.button(panel, action)
      val icon = Emoji.fromUnicode(emoji)
      if (label.isEmpty) {
        if (action == PanelIds.Clear) Button.danger(id, icon) else Button.secondary(id, icon)
      } else {
        val button = if (action == PanelIds.Clear) Button.danger(id, label) else Button.secondary(id, label)
        button.withEmoji(icon)
      }
    })
  }

  /** The Tag button under a Look up reply.
   *
   *  Tagging lives here rather than on the panel: the panel's five buttons fit
   *  one row and a sixth pushed Clear All onto a row of its own. It reads better
   *  here anyway — you have just looked somebody up, and the button acts on the
   *  player in front of you rather than asking for a name again.
   */
  def lookupButtons(panel: Panel, name: String, currentTag: String): ActionRow = {
    val tag = ListTags.find(currentTag)
    val label = tag.map(t => s"Tag: ${t.label}").getOrElse("Tag")
    val icon = tag.map(_.emoji).getOrElse("🏷️")
    ActionRow.of(Button.secondary(PanelIds.buttonFor(panel, PanelIds.TagOne, name), label)
      .withEmoji(Emoji.fromUnicode(icon)))
  }
  /** The fallback when a list somehow draws nothing at all. Not reachable from
   *  the list builders, which always draw at least a "nothing on it" embed for
   *  each half — this exists so a panel can never answer with silence. */
  def emptyListEmbed(panel: Panel): MessageEmbed =
    new EmbedBuilder()
      .setTitle(s"${panel.noun.capitalize}")
      .setDescription("Nothing on it yet.")
      .setColor(Embeds.BrandColor)
      .build()

  /** Replaces the panel's buttons while Clear is waiting for an answer, so the
   *  only things on screen are the two answers to the question just asked. */
  def clearConfirmButtons(panel: Panel): ActionRow =
    ActionRow.of(
      Button.danger(PanelIds.button(panel, PanelIds.ClearConfirm), "Yes, clear it"),
      Button.secondary(PanelIds.button(panel, PanelIds.Cancel), "Cancel"))

  def clearConfirmEmbed(panel: Panel, players: Int, guilds: Int): MessageEmbed =
    new EmbedBuilder()
      .setDescription(
        s"${Config.noEmoji} This clears **$players** ${plural(players, "player", "players")} and " +
          s"**$guilds** ${plural(guilds, "guild", "guilds")} from the ${panel.noun}.\n\nThis cannot be undone.")
      .setColor(Embeds.BrandColor)
      .build()

  private def plural(n: Int, one: String, many: String): String = if (n == 1) one else many

  // --- /admin --------------------------------------------------------------

  /** Six buttons, and the only panel drawn three to a row rather than five: the
   *  split is the grouping. The top row acts on one particular server and needs
   *  its id — so the list that gives you one leads. The bottom row is the three
   *  bot-wide refreshes, which act everywhere and ask for nothing.
   *
   *  Leaving a server is the only red button. Reposting boosted touches every
   *  guild the bot is in, but all it reposts is a message that reposts itself at
   *  the next server save anyway, so it is not destructive the way leaving is. */
  private val adminLabels: Map[String, (String, String)] = Map(
    PanelIds.GuildList   -> ("Server list" -> "🗒️"),
    PanelIds.Message     -> ("Message" -> "✉️"),
    PanelIds.Leave       -> ("Leave" -> "🚪"),
    PanelIds.Dreamscar   -> ("Dreamscar" -> "🌙"),
    PanelIds.WorldList   -> ("Worlds" -> "🌍"),
    PanelIds.BoostedPost -> ("Repost boosted" -> "📢")
  )

  def adminButtons: List[ActionRow] =
    rows(PanelIds.adminActions.map { action =>
      val (label, emoji) = adminLabels(action)
      val id = PanelIds.button(Panel.Admin, action)
      val button = if (action == PanelIds.Leave) Button.danger(id, label) else Button.secondary(id, label)
      button.withEmoji(Emoji.fromUnicode(emoji))
    }, perRow = 3)

  /** Names the one number the creator wants at a glance. It is also the size of
   *  what Server list is about to print, which is worth knowing before pressing
   *  it — that reply is one ephemeral message per three thousand characters. */
  def adminEmbed(guilds: Int): MessageEmbed =
    new EmbedBuilder()
      .setTitle("Bot creator tools")
      .setDescription(
        s"In **$guilds** ${plural(guilds, "server", "servers")}.\n\n" +
          "The top row acts on one server and needs its id — **Server list** has them. " +
          "The bottom row refreshes something everywhere and takes no input.")
      .setColor(Embeds.BrandColor)
      .build()
}
