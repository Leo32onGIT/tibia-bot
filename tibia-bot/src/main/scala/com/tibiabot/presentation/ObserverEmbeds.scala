package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.{MiniWorldChange, ObserverStatus, ObserverToken, RaidAnnouncement}
import com.tibiabot.observer.{MiniWorldChangeCatalog, RaidCreature, RaidType}
import com.tibiabot.statistics.BossCatalogue
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.thumbnail.Thumbnail
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.utils.messages.{MessageCreateBuilder, MessageCreateData}

import java.time.Instant
import scala.jdk.CollectionConverters._

/** The `/observer` panel: a member's own Tibia Observer token status, with the
 *  Add / Remove controls. Ephemeral, so it only ever shows one member their own
 *  link. */
object ObserverEmbeds {

  private val tokenPage = "https://www.tibia.com/account/?subtopic=accountmanagement&page=tibiaobserver"

  def panel(token: Option[ObserverToken]): MessageEmbed = {
    val body = token match {
      case None =>
        s"""${Config.noEmoji} You have no **Tibia Observer** token configured.
           |
           |Press **Add** and paste the token from your [Tibia account]($tokenPage)
           |(*Account Management → Tibia Observer → Connect*). You'll get mini world change
           |alerts, and a raids channel for each of your account's worlds this server tracks —
           |pooled from every linked member.""".stripMargin
      case Some(t) =>
        s"""${statusLine(t)}
           |
           |Press **Remove** to unlink.""".stripMargin
    }
    new EmbedBuilder()
      .setTitle("Tibia Observer")
      .setColor(Embeds.BrandColor)
      .setDescription(body)
      .build()
  }

  private def statusLine(t: ObserverToken): String = t.status match {
    case ObserverStatus.Pending =>
      s"${Config.yesEmoji} Token saved — it will be verified once linking is enabled."
    case ObserverStatus.Linked =>
      val who = t.accountLabel.map(a => s" as **$a**").getOrElse("")
      val where = t.world.map(w => s" on **$w**").getOrElse("")
      s"${Config.yesEmoji} Linked$who$where."
    case ObserverStatus.NeedsRelink =>
      s"${Config.noEmoji} Your link needs renewing — press **Add** with a fresh token."
    case ObserverStatus.Error =>
      s"${Config.noEmoji} Something went wrong with your link — try **Add** again."
  }

  /** The mini world change art the block carried until 25 Sep 2026. Only read
   *  now, to recognise the block in a message posted before then. */
  private val MwcThumbnail = "https://violentbot.xyz/discord/observer/miniworldchange.png"

  /** What the block's first line says after its emoji, and so how it is told
   *  apart from the other blocks once it has no picture. */
  private val MwcLead = "Mini World Changes for **"

  /** Room for the changes. The server-save card (see ServerSaveCard) is one V2
   *  message, and Discord allows 4,000 characters of text across the whole of
   *  one. This leaves the other five blocks, about 150 each, a thousand. */
  val MaxMwcDescription = 3000

  /** The Mini World Changes block in a guild's server-save notifications message,
   *  for its world — the same world the Dream Courts block names. It opens on
   *  `Mini World Changes for <world>` behind its own emoji, then each change: its
   *  name, linked to its wiki page, with the feed's description as a small grey
   *  line under it. No picture, so the text has the card's whole width. `None`
   *  when nothing is active, so a quiet day (or a world no linked member covers,
   *  which the feed can't tell apart) just leaves the block out. */
  def serverSaveMwcEmbed(world: String, changes: List[MiniWorldChange],
                         emoji: String = Config.raidEmoji, leadEmoji: String = Config.mwcEmoji): Option[MessageEmbed] =
    if (changes.isEmpty) None
    else {
      // A ### header, the same size as the cooldown tracker's heading.
      val lead = s"### $leadEmoji $MwcLead$world**"
      val entries = changes.map { c =>
        val name = s"### $emoji **[${c.title}](${MiniWorldChangeCatalog.wikiUrl(c.title)})**"
        // `-#` only reaches the end of its line, so the body is kept to one.
        val body = c.body.trim.replaceAll("\\s*\\n\\s*", " ")
        if (body.nonEmpty) s"$name\n-# $body" else name
      }
      // Whole entries only, so a long day can never cut a link in half.
      val lengths = entries.scanLeft(lead.length)(_ + 1 + _.length).tail
      val kept = entries.zip(lengths).takeWhile(_._2 <= MaxMwcDescription).map(_._1)
      Some(new EmbedBuilder()
        .setDescription((lead :: kept).mkString("\n"))
        .setColor(Embeds.BrandColor)
        .build())
    }

  /** Whether a block is the notifications message's mini world changes, told
   *  apart by what its first line says: it has moved within the message, so its
   *  place says nothing. A message from before 25 Sep 2026 has the old wording,
   *  and is told apart by the picture it carried then. */
  def isServerSaveMwcEmbed(embed: MessageEmbed): Boolean =
    Option(embed.getDescription).flatMap(_.linesIterator.nextOption()).exists(_.contains(MwcLead)) ||
      Option(embed.getThumbnail).exists(_.getUrl == MwcThumbnail)

  /** The boosted boss and creature of a posted notifications message: its first
   *  two embeds once any mini world changes are set aside. The changes sit first
   *  since 24 Sep 2026 and after the Dream Courts before that, so a message posted
   *  either way reads right. */
  def boostedEmbedsOf(embeds: List[MessageEmbed]): List[MessageEmbed] =
    embeds.filterNot(isServerSaveMwcEmbed).take(2)

  /** Guilded-neutral-death grey (`4540237`) — the raids channel reuses it for the
   *  drip lines so a broadcast reads as neutral, ambient news. */
  private val DripGrey = 4540237

  /** Boss names (and race aliases) from the boss catalogue, lower-cased — used to
   *  pick the start card's picture: a boss over an ordinary creature. */
  private lazy val bossNames: Set[String] =
    BossCatalogue.bosses.flatMap(b => b.name.toLowerCase :: b.raceName.map(_.toLowerCase).toList).toSet

  private def wikiSlug(name: String): String = name.trim.replace(" ", "_")

  /** A creature as a wiki-linked bullet, with its count qualifier (if any) outside
   *  the link — matching how the bot links creatures elsewhere. */
  private def creatureBullet(c: RaidCreature): String =
    s"• [${c.name}](https://tibia.fandom.com/wiki/${wikiSlug(c.name)})${c.qualifier.map(q => s" $q").getOrElse("")}"

  /** The thumbnail: the raid's boss if it brings one, otherwise its first creature,
   *  as the bot's usual TibiaWiki image link. */
  private def thumbnailUrl(creatures: Vector[RaidCreature]): Option[String] =
    creatures.find(c => bossNames.contains(c.name.toLowerCase)).orElse(creatures.headOption)
      .map(c => s"https://www.tibiawiki.com.br/wiki/Special:Redirect/file/${wikiSlug(c.name)}.gif")

  /** The area a raid is in: what the feed says, or the catalogue's when it says
   *  nothing. */
  private def areaOf(raid: RaidAnnouncement, raidType: Option[RaidType]): String =
    Option(raid.area).filter(_.nonEmpty).orElse(raidType.flatMap(_.area)).getOrElse("an unknown area")

  // A raid gets three posts, one per stage, always in this order. The feed only
  // says which raid it is at the start, so the first two never name it.
  //
  // Each is a Components V2 card, gold-edged as the raid embeds before it were:
  // the stage as a small grey label in bold capitals (as the server-save card's
  // blocks are labelled), the place as a header behind the `:raid:` emoji, and
  // when the next thing happens as a small grey line with its label in bold.
  // The broadcast lines stay embeds.

  /** The area stage, an hour before the raid starts: the area, and when its
   *  subarea is revealed. */
  def areaCard(raid: RaidAnnouncement, emoji: String = Config.raidEmoji): Container = {
    val reveals = raid.startDate.map(start =>
      timeLine("Subarea reveals", start.minus(com.tibiabot.observer.ObserverRaidPoller.SubareaLead)))
    stageCard(TextDisplay.of(stageText("Imminent raid", s"$emoji ${areaOf(raid, None)}", reveals.toList)))
  }

  /** The subarea stage, 15 minutes before the raid starts: the subarea (its area
   *  when there is none), and when the raid starts. */
  def subareaCard(raid: RaidAnnouncement, emoji: String = Config.raidEmoji): Container = {
    val starts = raid.startDate.map(start => timeLine("Raid starts", start))
    val where = raid.subarea.filter(_.nonEmpty).getOrElse(areaOf(raid, None))
    stageCard(TextDisplay.of(stageText("Subarea revealed", s"$emoji $where", starts.toList)))
  }

  /** The start, when the feed says which raid it is: its name as the header,
   *  linked to its wiki page, over the subarea in bold and when it started, with a
   *  picture of its boss or lead creature beside them. Under a divider, its
   *  creatures as wiki-linked bullets. Its broadcast lines follow. A raid the
   *  catalogue doesn't know has the subarea as its header, as the posts before it
   *  do, and no picture or creatures. */
  def startedCard(raid: RaidAnnouncement, raidType: Option[RaidType],
                  emoji: String = Config.raidEmoji): Container = {
    val started = raid.startDate.map(start => timeLine("Raid started", start)).toList
    val where = raid.subarea.orElse(raidType.flatMap(_.subarea)).filter(_.nonEmpty).getOrElse(areaOf(raid, raidType))
    raidType match {
      case None =>
        stageCard(TextDisplay.of(stageText("Raid started", s"$emoji $where", started)))
      case Some(rt) =>
        val name = rt.link.fold(rt.name)(url => s"[${rt.name}]($url)")
        val text = TextDisplay.of(stageText("Raid started", s"$emoji $name", s"-# **$where**" :: started))
        val top = thumbnailUrl(rt.creatures).fold[ContainerChildComponent](text)(url =>
          Section.of(Thumbnail.fromUrl(url), text))
        val creatures =
          if (rt.creatures.isEmpty) Nil
          else List(Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of(s"${ServerSaveCard.label("Creatures")}\n${rt.creatures.map(creatureBullet).mkString("\n")}"))
        stageCard(top :: creatures: _*)
    }
  }

  /** A stage card's text: its label, its header, then its lines. */
  private def stageText(label: String, header: String, lines: List[String]): String =
    (ServerSaveCard.label(label) :: s"### $header" :: lines).mkString("\n")

  /** When the next thing happens, as a countdown on a small grey line. */
  private def timeLine(what: String, at: Instant): String = s"-# **$what:** <t:${at.getEpochSecond}:R>"

  private def stageCard(parts: ContainerChildComponent*): Container =
    Container.of(parts.asJava).withAccentColor(Int.box(Embeds.AutomaticColor))

  /** A stage card as the message the raids channel is sent. */
  def stageMessage(card: Container): MessageCreateData =
    new MessageCreateBuilder().useComponentsV2().setComponents(card).build()

  /** One raid broadcast line, dripped as the raid unfolds: just the in-world text in
   *  bold, in the neutral grey. Short and concise by design — no title, no footer. */
  def raidLineEmbed(message: String): MessageEmbed =
    new EmbedBuilder()
      .setColor(DripGrey)
      .setDescription(s"**${message.take(3990)}**")
      .build()

  /** A broadcast line as the message the raids channel is sent: its embed alone. */
  def raidLineMessage(message: String): MessageCreateData =
    MessageCreateData.fromEmbeds(raidLineEmbed(message))

  /** Add is offered when there is no token; Remove when there is one. The other is
   *  shown disabled so the panel always reads as a pair (as `/boosted` does). The
   *  world is asked for inside the Add form, not here. */
  def controls(token: Option[ObserverToken]): ActionRow =
    if (token.isDefined)
      ActionRow.of(
        Button.success("observer add", "Add").asDisabled,
        Button.danger("observer remove", "Remove"))
    else
      ActionRow.of(
        Button.success("observer add", "Add"),
        Button.danger("observer remove", "Remove").asDisabled)
}
