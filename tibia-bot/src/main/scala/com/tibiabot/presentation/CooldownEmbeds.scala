package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.cooldowns.CooldownIds
import com.tibiabot.domain.{CooldownKind, CooldownStamp}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji

import scala.jdk.CollectionConverters._

/** The collectible cooldown embeds — the tracker panel, each kind's list and
 *  the expiry DM — and the button rows that go under them, as NotifyEmbeds also
 *  keeps its own.
 *
 *  Everything to do with how a kind *looks* lives here rather than on
 *  [[com.tibiabot.domain.CooldownKind]], which stays Config-free like the rest
 *  of `domain`. This is also where the divergence the old GalthenEmbeds
 *  documented gets resolved: the four call sites used to disagree about the
 *  satchel emoji (`Config.satchelEmoji` versus a hardcoded id) and about the
 *  colour (178877, 13773097 and 9855533 against the brand colour), because each
 *  built its own embed. They now all build them here.
 */
object CooldownEmbeds {

  /** TibiaWiki (pt) serves an item's sprite off its page name. */
  private def wikiFile(page: String): String =
    s"https://www.tibiawiki.com.br/wiki/Special:Redirect/file/$page.gif"

  private def wikiPage(page: String): String =
    s"https://www.tibiawiki.com.br/wiki/$page"

  /** The item page each kind is named for. Underscored rather than spaced:
   *  MediaWiki canonicalises a title that way, so the URL needs no escaping. */
  private def page(kind: CooldownKind): String = kind match {
    case CooldownKind.Satchel    => "Galthen's_Satchel"
    case CooldownKind.DragonHead => "Jade_Dragon_Head_(Full_Power)"
  }

  /** The item's own name, as the wiki writes it — `CooldownKind.label` is the
   *  shorter thing a button is allowed to say. */
  def itemName(kind: CooldownKind): String = kind match {
    case CooldownKind.Satchel    => "Galthen's Satchel"
    case CooldownKind.DragonHead => "Jade Dragon Head"
  }

  def emoji(kind: CooldownKind): String = kind match {
    case CooldownKind.Satchel    => Config.satchelEmoji
    case CooldownKind.DragonHead => Config.dragonHeadEmoji
  }

  def thumbnail(kind: CooldownKind): String = wikiFile(page(kind))

  /** The item's name, linked to its wiki page. */
  def linkedName(kind: CooldownKind): String = s"**[${itemName(kind)}](${wikiPage(page(kind))})**"

  /** The tracker panel: one embed, one button per kind.
   *
   *  Posted into a guild's notifications channel by `/setup` and `/repair`, and
   *  answered ephemerally by `/cooldowns` — the same embed both times, which is
   *  why it is built in one place. Kooldown-Aid rather than either tracked item,
   *  since the panel belongs to neither. */
  def panel(): MessageEmbed =
    new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setDescription("This is a **Cooldown Tracker.**\nManage your cooldowns here:")
      .setThumbnail(wikiFile("Kooldown-Aid"))
      .build()

  /** The prompt shown when someone has nothing of this kind tracked. */
  def empty(kind: CooldownKind): MessageEmbed =
    new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setThumbnail(thumbnail(kind))
      .setDescription(
        s"This is a ${linkedName(kind)} cooldown tracker.\nMark the ${emoji(kind)} as " +
          s"**Collected** and I will message you when the ${kind.durationDays} day cooldown expires.")
      .build()

  /** One line per tracked cooldown: who it is for, and when it comes back. */
  def line(stamp: CooldownStamp, ownerName: String): String = {
    val displayTag = if (stamp.tag.isEmpty) Names.user(ownerName) else s"**`${stamp.tag}`**"
    s"${emoji(stamp.kind)} can be collected by $displayTag <t:${stamp.kind.expiresAtEpoch(stamp.when)}:R>"
  }

  /** Somebody's tracked cooldowns of one kind. `note` is appended after a blank
   *  line when a form has just changed something and says so — which is the one
   *  case this is called with nothing left to list, and why the heading is
   *  conditional: a "Cooldowns:" heading over no cooldowns reads as a bug. */
  def list(kind: CooldownKind, stamps: List[CooldownStamp], ownerName: String, note: String = ""): MessageEmbed = {
    val body = truncate(stamps.map(line(_, ownerName)))
    val embed = new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setThumbnail(thumbnail(kind))
      .setDescription(if (note.isEmpty) body else if (body.isEmpty) note else s"$body\n\n$note")
    if (stamps.nonEmpty) embed.setTitle(s"${kind.label} Cooldowns:")
    embed.build()
  }

  /** The DM that goes out when a cooldown runs down. The footer carries the tag
   *  because the Collected button under it has nowhere else to read it from —
   *  the row it was sent for is deleted in the same sweep. */
  def expired(stamp: CooldownStamp, ownerName: String): MessageEmbed = {
    val kind = stamp.kind
    val displayTag = if (stamp.tag.isEmpty) Names.user(ownerName) else s"**`${stamp.tag}`**"
    val embed = new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setThumbnail(thumbnail(kind))
      .setDescription(
        s"${emoji(kind)} cooldown for $displayTag expired <t:${kind.expiresAtEpoch(stamp.when)}:R>\n\n" +
          s"Mark it as **Collected** and I will message you when the ${kind.durationDays} day cooldown expires.")
    if (stamp.tag.nonEmpty) embed.setFooter(s"Tag: ${stamp.tag.toLowerCase}")
    embed.build()
  }

  private def panelButton(kind: CooldownKind): Button =
    Button.primary(CooldownIds.button(kind, CooldownIds.Action.Open), kind.label)
      .withEmoji(Emoji.fromFormatted(emoji(kind)))

  /** The panel itself: one button per kind, in `CooldownKind.all` order. */
  def panelControls(): ActionRow = ActionRow.of(CooldownKind.all.map(panelButton).asJava)

  /** The one button offered when nothing of this kind is tracked yet. */
  def collectRow(kind: CooldownKind): ActionRow =
    ActionRow.of(
      Button.success(CooldownIds.button(kind, CooldownIds.Action.Set), "Collected")
        .withEmoji(Emoji.fromFormatted(emoji(kind))))

  /** The controls under a list, which narrow as there is less to act on: with a
   *  single entry there is nothing to pick between, so Remove takes it directly
   *  rather than opening a form asking which, and no second entry is left for
   *  Clear All to mean. With none left, only Add is offered — the previous code
   *  went on showing Remove over an empty list. */
  def controls(kind: CooldownKind, tracked: Int): ActionRow = {
    val add = Button.success(CooldownIds.button(kind, CooldownIds.Action.AddForm), "Add Cooldown")
      .withEmoji(Emoji.fromFormatted(emoji(kind)))
    if (tracked == 0) ActionRow.of(add)
    else if (tracked == 1)
      ActionRow.of(add, Button.danger(CooldownIds.button(kind, CooldownIds.Action.RemoveAll), "Remove"))
    else
      ActionRow.of(
        add,
        Button.danger(CooldownIds.button(kind, CooldownIds.Action.RemoveForm), "Remove"),
        Button.secondary(CooldownIds.button(kind, CooldownIds.Action.RemoveAll), "Clear All"))
  }

  /** Join `lines` with newlines and cap the result at `limit` characters,
   *  cutting back to the last whole line so an entry is never split mid-way. */
  def truncate(lines: Seq[String], limit: Int = 4050): String = {
    val joined = lines.mkString("\n")
    if (joined.length > limit) {
      val truncated = joined.substring(0, limit)
      val lastNewLine = truncated.lastIndexOf("\n")
      if (lastNewLine >= 0) truncated.substring(0, lastNewLine) else truncated
    } else joined
  }
}
