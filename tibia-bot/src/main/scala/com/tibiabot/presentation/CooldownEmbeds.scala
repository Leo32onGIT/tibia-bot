package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.cooldowns.CooldownIds
import com.tibiabot.domain.{CooldownKind, CooldownStamp}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.thumbnail.Thumbnail
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

  /** What marking one of these says: a satchel is collected, a dragon head used. */
  def doneLabel(kind: CooldownKind): String = kind match {
    case CooldownKind.Satchel    => "Collected"
    case CooldownKind.DragonHead => "Used"
  }

  /** The heading both cooldown cards open with. No picture: each item below
   *  carries its own. */
  private def cardHeader: TextDisplay =
    TextDisplay.of("### ⏳ Cooldown tracker\n-# Mark an item as collected/used and I'll message you when it's ready again.")

  private def divider: Separator = Separator.createDivider(Separator.Spacing.SMALL)

  /** The tracker posted into a guild's notifications channel by `/setup` and
   *  `/repair`: each item, its cooldown, and a button that opens your own
   *  cooldowns just for you. It cannot show anybody's own — everyone sees the same
   *  message — which is why the button is there at all.
   *
   *  Laid out with Discord's layout components, so it is sent with
   *  `useComponentsV2`. `emojiOf` defaults to the configured emoji; a test passes
   *  its own. */
  def tracker(emojiOf: CooldownKind => String = emoji): Container = {
    val rows = CooldownKind.all.flatMap { kind =>
      List[ContainerChildComponent](divider, Section.of(
        Button.primary(CooldownIds.button(kind, CooldownIds.Action.Open), Emoji.fromFormatted(emojiOf(kind))),
        TextDisplay.of(s"${emojiOf(kind)} ${linkedName(kind)}\n-# ${kind.durationDays}-day cooldown")))
    }
    Container.of(((cardHeader: ContainerChildComponent) :: rows).asJava)
  }

  /** Somebody's own cooldowns, both items at once: what `/cooldowns` answers with
   *  and what the tracker's buttons open. Each item shows when every one of yours
   *  comes back, with the buttons to act on it underneath — so marking an item is
   *  one press, not one to find the list and another to use it.
   *
   *  `note` leads the card when an action has just changed something and says
   *  what. */
  def personal(stamps: CooldownKind => List[CooldownStamp], ownerName: String, note: String = "",
               emojiOf: CooldownKind => String = emoji): Container = {
    val lead: List[ContainerChildComponent] = if (note.isEmpty) Nil else List(TextDisplay.of(note))
    val items = CooldownKind.all.flatMap { kind =>
      val tracked = stamps(kind)
      val lines =
        if (tracked.isEmpty) "-# Nothing tracked yet."
        else truncate(tracked.map(stamp =>
          s"• ${whoFor(stamp, ownerName)} — ready <t:${kind.expiresAtEpoch(stamp.when)}:R>"), 1500)
      List[ContainerChildComponent](
        divider,
        Section.of(Thumbnail.fromUrl(thumbnail(kind)),
          TextDisplay.of(s"${emojiOf(kind)} ${linkedName(kind)}\n-# ${kind.durationDays}-day cooldown\n$lines")),
        personalControls(kind, tracked.size, emojiOf))
    }
    Container.of(((cardHeader: ContainerChildComponent) :: lead ++ items).asJava)
  }

  /** Under each item on somebody's own card. Collected / Used stamps your own
   *  cooldown now; the next opens the form that stamps one under a character's
   *  name. Remove narrows as the list does, as [[controls]] always has: with one
   *  cooldown it takes that one directly, with more it asks which, and Clear All
   *  only appears when there is more than one to clear. */
  private def personalControls(kind: CooldownKind, tracked: Int, emojiOf: CooldownKind => String): ActionRow = {
    val done = Button.success(CooldownIds.button(kind, CooldownIds.Action.Set), doneLabel(kind))
      .withEmoji(Emoji.fromFormatted(emojiOf(kind)))
    val forCharacter = Button.secondary(CooldownIds.button(kind, CooldownIds.Action.AddForm), "For a character…")
    val removing =
      if (tracked == 0) Nil
      else if (tracked == 1) List(Button.danger(CooldownIds.button(kind, CooldownIds.Action.RemoveAll), "Remove"))
      else List(
        Button.danger(CooldownIds.button(kind, CooldownIds.Action.RemoveForm), "Remove"),
        Button.secondary(CooldownIds.button(kind, CooldownIds.Action.RemoveAll), "Clear All"))
    ActionRow.of((done :: forCharacter :: removing).asJava)
  }

  /** Who a cooldown is for: the tag it was added under, or you. */
  private def whoFor(stamp: CooldownStamp, ownerName: String): String =
    if (stamp.tag.isEmpty) Names.user(ownerName) else s"**`${stamp.tag}`**"

  /** One line per tracked cooldown: who it is for, and when it comes back. */
  def line(stamp: CooldownStamp, ownerName: String): String = {
    val displayTag = if (stamp.tag.isEmpty) Names.user(ownerName) else s"**`${stamp.tag}`**"
    s"${emoji(stamp.kind)} can be collected by $displayTag <t:${stamp.kind.expiresAtEpoch(stamp.when)}:R>"
  }

  /** Somebody's tracked cooldowns of one kind — the list the tracker opened
   *  before it opened [[personal]], kept for redrawing one of those when a button
   *  on it is pressed. `note` is appended after a blank
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
          s"Mark it as **${doneLabel(kind)}** and I will message you when the ${kind.durationDays} day cooldown expires.")
    if (stamp.tag.nonEmpty) embed.setFooter(s"Tag: ${stamp.tag.toLowerCase}")
    embed.build()
  }

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
