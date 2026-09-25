package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.{MiniWorldChange, ObserverStatus, ObserverToken, RaidAnnouncement}
import com.tibiabot.observer.{MiniWorldChangeCatalog, RaidCreature, RaidType}
import com.tibiabot.statistics.BossCatalogue
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed

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

  /** The bot's own mini world change art, hosted with its other Discord assets. */
  private val MwcThumbnail = "https://violentbot.xyz/discord/observer/miniworldchange.png"

  /** Room for the changes. The server-save card (see ServerSaveCard) is one V2
   *  message, and Discord allows 4,000 characters of text across the whole of
   *  one. This leaves the other five blocks, about 150 each, a thousand. */
  val MaxMwcDescription = 3000

  /** The Mini World Changes embed in a guild's server-save notifications message,
   *  for its world — the same world the Dream Courts embed names. Each change is its
   *  name, linked to its wiki page, with the feed's description as a small grey line
   *  under it. `None` when nothing is active, so a quiet day (or a world no linked
   *  member covers, which the feed can't tell apart) just leaves the embed out. */
  def serverSaveMwcEmbed(world: String, changes: List[MiniWorldChange],
                         emoji: String = Config.raidEmoji): Option[MessageEmbed] =
    if (changes.isEmpty) None
    else {
      val lead =
        if (changes.size == 1) s"The mini world change for **$world** is:"
        else s"The mini world changes for **$world** are:"
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
        .setThumbnail(MwcThumbnail)
        .setColor(Embeds.BrandColor)
        .build())
    }

  /** Whether an embed is the notifications message's mini world changes, told
   *  apart by its own thumbnail: it has moved within the message, so its place
   *  says nothing. */
  def isServerSaveMwcEmbed(embed: MessageEmbed): Boolean =
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
   *  pick the imminent embed's thumbnail: a boss over an ordinary creature. */
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

  /** The imminent-raid post, at the area stage — an hour before the raid starts.
   *
   *  "Imminent Raid" over the area as a grey line, and when its subarea is
   *  revealed: half an hour before the start. An account with limited discoveries
   *  learns nothing more until the raid starts, so this is usually all there is.
   *  When a better-explored account's feed already names the raid, its name
   *  (linked to its wiki page) is the title, and its creatures and picture come too. */
  def areaEmbed(raid: RaidAnnouncement, raidType: Option[RaidType],
                emoji: String = Config.raidEmoji): MessageEmbed = {
    val title = raidType.map(_.name).getOrElse("Imminent Raid")
    val reveals = raid.startDate.map(start =>
      s"**Subarea reveals:** <t:${start.minus(com.tibiabot.observer.ObserverRaidPoller.SubareaLead).getEpochSecond}:R>")
    stageEmbed(title, raidType, s"-# ${areaOf(raid, raidType)}" :: reveals.toList, emoji)
  }

  /** The post at the subarea stage — half an hour before the raid starts. It goes
   *  out again at the start, now naming the raid, when no earlier post could; and
   *  for a raid first seen once it has started, it is the only one before its lines.
   *
   *  "Subarea Revealed" over the subarea as a grey line (its area when there is
   *  none), and when the raid starts (or started). Like the area post, the raid's
   *  name is the title when the feed already gives it. */
  def subareaEmbed(raid: RaidAnnouncement, raidType: Option[RaidType], at: java.time.Instant,
                   emoji: String = Config.raidEmoji): MessageEmbed = {
    val subarea = raid.subarea.orElse(raidType.flatMap(_.subarea)).filter(_.nonEmpty)
    val title = raidType.map(_.name).getOrElse("Subarea Revealed")
    val when = raid.startDate.map { start =>
      if (start.isAfter(at)) s"**Raid starts:** <t:${start.getEpochSecond}:R>"
      else s"**Raid started:** <t:${start.getEpochSecond}:R>"
    }
    stageEmbed(title, raidType, s"-# ${subarea.getOrElse(areaOf(raid, raidType))}" :: when.toList, emoji)
  }

  /** A stage post: the `:raid:` emoji and its title (the raid's name linked to its
   *  wiki page, when known), its lines, and — when the raid is known — its creatures
   *  as wiki-linked bullets with a thumbnail of its boss or lead creature. */
  private def stageEmbed(title: String, raidType: Option[RaidType], lines: List[String],
                         emoji: String): MessageEmbed = {
    val creatures = raidType.map(_.creatures).getOrElse(Vector.empty)
    val creatureBlock = if (creatures.nonEmpty) s"\n\n**Creatures:**\n${creatures.map(creatureBullet).mkString("\n")}" else ""
    val builder = new EmbedBuilder()
      .setColor(Embeds.AutomaticColor)
      .setTitle(s"$emoji $title", raidType.flatMap(_.link).orNull)
      .setDescription((lines.mkString("\n") + creatureBlock).take(4000))
    thumbnailUrl(creatures).foreach(builder.setThumbnail)
    builder.build()
  }

  /** One raid broadcast line, dripped as the raid unfolds: just the in-world text in
   *  bold, in the neutral grey. Short and concise by design — no title, no footer. */
  def raidLineEmbed(message: String): MessageEmbed =
    new EmbedBuilder()
      .setColor(DripGrey)
      .setDescription(s"**${message.take(3990)}**")
      .build()

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
