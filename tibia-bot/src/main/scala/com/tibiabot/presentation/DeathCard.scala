package com.tibiabot.presentation

import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.mediagallery.{MediaGallery, MediaGalleryItem}
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.thumbnail.Thumbnail
import net.dv8tion.jda.api.entities.{Message, MessageEmbed}
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.utils.messages.{MessageCreateBuilder, MessageCreateData, MessageEditBuilder, MessageEditData}

import java.util.regex.{Matcher, Pattern}
import scala.jdk.CollectionConverters._

/** A death in the deaths channel, as a Components V2 card (27 Sep 2026; an
 *  embed before).
 *
 *  The card is the embed's content in the same order: the character's name as
 *  its header — with the post's one button, 📷 or exiva, on the same line at
 *  the right — then the guild, when it happened and who did it, with the
 *  creature or PvP picture beside them. A screenshot, once added, goes under
 *  that as a gallery with its "added by" line, and its paging under that. The
 *  edge is the death's side colour, except a neutral with no guild, whose
 *  colour was the embed's own background and so read as no edge at all.
 *
 *  A role ping goes at the top as a line of its own: a V2 message has no text
 *  outside its components, and a mention in one still pings.
 *
 *  Everything that changes a death after it is posted — the exiva list, a
 *  screenshot added, paged through or deleted — reads the post back with
 *  [[read]], changes what it is about, and edits the whole card in. A death
 *  posted as an embed before the switch is read the same way and becomes a
 *  card on its first such edit.
 */
object DeathCard {

  /** The colour a neutral with no guild dies in: the embed background, which is
   *  no edge. A card in it gets no accent at all. */
  val NoEdgeColour: Int = 3092790

  /** What JDA reports for an embed with no colour. */
  private val DefaultColourRaw = 0x1FFFFFFF

  /** A screenshot on a death, and the line under it saying who added it and
   *  which of how many it is. */
  final case class Screenshot(url: String, caption: String)

  /** Everything a death post shows.
   *
   *  @param title       the name as markdown, linked to its character page
   *  @param description the guild, when it happened and who did it
   *  @param ping        the role mention at the top, when the post pinged */
  final case class Post(
      title: String,
      description: String,
      thumbnail: Option[String],
      colour: Int,
      ping: Option[String] = None,
      screenshot: Option[Screenshot] = None
  )

  /** A character's name between its vocation's emoji, linked to its page. */
  def title(charName: String, vocation: String): String = {
    val emoji = Emojis.vocEmoji(vocation)
    s"$emoji [$charName](${Urls.charUrl(charName)}) $emoji".trim
  }

  /** Add a screenshot: just the camera, since what it does is the icon. */
  def cameraButton(charName: String, deathTime: Long, messageId: String = "placeholder"): Button =
    Button.secondary(s"death_screenshot_${charName}_${deathTime}_$messageId", Emoji.fromUnicode("📷"))

  /** Show who to chase, on an ally's death. */
  def exivaButton(charName: String, deathTime: Long, exivaEmoji: String): Button =
    Button.secondary(s"death_exiva_${charName}_$deathTime", Emoji.fromFormatted(exivaEmoji))

  /** The row under a screenshot: paging when there is more than one, and delete
   *  for whoever may. */
  def screenshotRow(charName: String, deathTime: Long, messageId: String, index: Int, count: Int,
                    deletable: Boolean): List[Button] = {
    val paging =
      if (count > 1) List(
        Button.primary(s"prev_screenshot_${charName}_${deathTime}_${messageId}_$index", "◀"),
        Button.secondary(s"screenshot_info_${charName}_${deathTime}_$messageId", s"${index + 1}/$count").asDisabled(),
        Button.primary(s"next_screenshot_${charName}_${deathTime}_${messageId}_$index", "▶"))
      else Nil
    paging ++ Option.when(deletable)(Button.danger(s"delete_screenshot_${charName}_${deathTime}_${messageId}_$index", "🗑️"))
  }

  /** The post as the message's components: the ping, then the card.
   *
   *  @param titleButton the button on the name's line, if the post has one
   *  @param row         the screenshot's paging and delete, at the card's foot */
  def components(post: Post, titleButton: Option[Button], row: List[Button]): List[MessageTopLevelComponent] = {
    val titleText = TextDisplay.of(s"### ${post.title}")
    val head: ContainerChildComponent = titleButton.fold[ContainerChildComponent](titleText)(button => Section.of(button, titleText))
    val bodyText = TextDisplay.of(post.description)
    val body: ContainerChildComponent =
      post.thumbnail.filter(_.nonEmpty).fold[ContainerChildComponent](bodyText)(url => Section.of(Thumbnail.fromUrl(url), bodyText))
    val shot: List[ContainerChildComponent] = post.screenshot.toList.flatMap { s =>
      MediaGallery.of(MediaGalleryItem.fromUrl(s.url)) :: Option.when(s.caption.nonEmpty)(TextDisplay.of(s"-# ${s.caption}")).toList
    }
    val foot: List[ContainerChildComponent] = if (row.isEmpty) Nil else List(ActionRow.of(row.asJava))
    val card = Container.of((head :: body :: shot ::: foot).asJava)
    val edged = if (post.colour == NoEdgeColour) card else card.withAccentColor(Int.box(post.colour))
    post.ping.filter(_.nonEmpty).map(TextDisplay.of).toList ::: List(edged)
  }

  /** A new death, as the message it is sent as. */
  def create(post: Post, titleButton: Option[Button]): MessageCreateData =
    new MessageCreateBuilder().useComponentsV2().setComponents(components(post, titleButton, Nil).asJava).build()

  /** The post rewritten, as an edit. The text and embeds are cleared, which is
   *  what turns a death posted as an embed into a card in place. */
  def edit(post: Post, titleButton: Option[Button], row: List[Button]): MessageEditData =
    new MessageEditBuilder()
      .setContent("")
      .setEmbeds(java.util.Collections.emptyList())
      .useComponentsV2()
      .setComponents(components(post, titleButton, row).asJava)
      .build()

  /** A death post read back: a card, or an embed posted before the switch.
   *  `charName` is who died, which an embed's title only carried as plain text
   *  beside its link. None for a message that is neither. */
  def read(message: Message, charName: String): Option[Post] =
    if (message.isUsingComponentsV2) readComponents(message.getComponents.asScala.toList)
    else message.getEmbeds.asScala.headOption.map(embed => readEmbed(embed, message.getContentRaw, charName))

  /** A death posted as an embed, with the message's text (its ping, if any). */
  private[presentation] def readEmbed(embed: MessageEmbed, content: String, charName: String): Post = {
      val plain = Option(embed.getTitle).getOrElse(charName)
      val title = Option(embed.getUrl).filter(_ => charName.nonEmpty && plain.contains(charName)).fold(plain) { url =>
        plain.replaceFirst(Pattern.quote(charName), Matcher.quoteReplacement(s"[$charName]($url)"))
      }
      val colour = embed.getColorRaw
      Post(
        title = title,
        description = Option(embed.getDescription).getOrElse(""),
        thumbnail = Option(embed.getThumbnail).flatMap(t => Option(t.getUrl)),
        colour = if (colour == DefaultColourRaw) NoEdgeColour else colour,
        ping = Option(content).map(_.trim).filter(_.nonEmpty),
        screenshot = Option(embed.getImage).flatMap(i => Option(i.getUrl)).map(url =>
          Screenshot(url, Option(embed.getFooter).flatMap(f => Option(f.getText)).getOrElse("")))
      )
  }

  /** A death posted as a card: the message's components, as [[components]]
   *  lays them out. */
  private[presentation] def readComponents(top: List[MessageTopLevelComponent]): Option[Post] = {
    val ping = top.collectFirst { case text: TextDisplay => text.getContent }
    top.collectFirst { case card: Container => card }.flatMap { card =>
      val kids = card.getComponents.asScala.toList
      def textOf(part: Any): Option[String] = part match {
        case text: TextDisplay => Some(text.getContent)
        case section: Section => section.getContentComponents.asScala.collectFirst { case text: TextDisplay => text.getContent }
        case _ => None
      }
      for {
        title <- kids.headOption.flatMap(textOf)
        body <- kids.lift(1)
      } yield {
        val thumbnail = body match {
          case section: Section => section.getAccessory match {
            case picture: Thumbnail => Option(picture.getUrl)
            case _ => None
          }
          case _ => None
        }
        val gallery = kids.indexWhere(_.isInstanceOf[MediaGallery])
        val screenshot = kids.lift(gallery).collect { case g: MediaGallery => g }
          .flatMap(_.getItems.asScala.headOption)
          .map(item => Screenshot(item.getUrl, kids.lift(gallery + 1).flatMap(textOf).map(_.stripPrefix("-# ")).getOrElse("")))
        Post(
          title = title.stripPrefix("### "),
          description = textOf(body).getOrElse(""),
          thumbnail = thumbnail,
          colour = Option(card.getAccentColorRaw).map(_.intValue).getOrElse(NoEdgeColour),
          ping = ping,
          screenshot = screenshot
        )
      }
    }
  }
}
