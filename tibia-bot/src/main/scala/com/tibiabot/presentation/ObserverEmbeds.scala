package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.{MiniWorldChange, ObserverStatus, ObserverToken, RaidAnnouncement}
import com.tibiabot.observer.{RaidCreature, RaidType}
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
           |alerts, and a raids channel for each world this server tracks — pooled from every
           |linked member.""".stripMargin
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

  /** The Mini World Changes section appended to the boosted server-save DM, for a
   *  member with a linked Observer token. `None` when nothing is active, so the DM
   *  is unchanged for a quiet day. */
  def mwcEmbed(changes: List[MiniWorldChange]): Option[MessageEmbed] =
    if (changes.isEmpty) None
    else {
      val body = changes
        .take(12)
        .map(c => s"### ${c.title} ${Config.indentEmoji}*${c.world}*\n${c.body}")
        .mkString("\n\n")
        .take(4000)
      Some(new EmbedBuilder()
        .setTitle("Mini World Changes")
        .setColor(Embeds.BrandColor)
        .setDescription(body)
        .build())
    }

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

  /** The imminent-raid heads-up, posted once when a raid is first sighted (at
   *  whichever stage a member's exploration reveals it — area or subarea). The
   *  detailed one: the `:raid:` emoji and the raid's name (linked to its wiki page)
   *  as the title, a thumbnail of its boss or lead creature, the subarea, when it is
   *  due to start, and its creatures as wiki-linked bullets — all from the catalogue,
   *  so complete regardless of the stage that revealed it. Without a catalogue entry
   *  it falls back to the area. */
  def imminentEmbed(raid: RaidAnnouncement, raidType: Option[RaidType]): MessageEmbed = {
    val area = raidType.flatMap(_.area).getOrElse(raid.area)
    val subarea = raidType.flatMap(_.subarea).orElse(raid.subarea).filter(_.nonEmpty)
    val locName = subarea.getOrElse(area)
    val title = s"${Config.raidEmoji} ${raidType.map(_.name).getOrElse(locName)}"
    val when = raid.startDate match {
      case Some(d) if d.isAfter(java.time.Instant.now()) => s"Starts <t:${d.getEpochSecond}:R>"
      case Some(d)                                       => s"Started <t:${d.getEpochSecond}:R>"
      case None                                          => "Starting soon"
    }
    val creatures = raidType.map(_.creatures).getOrElse(Vector.empty)
    val creatureBlock = if (creatures.nonEmpty) s"\n\n**Creatures:**\n${creatures.map(creatureBullet).mkString("\n")}" else ""
    val builder = new EmbedBuilder()
      .setColor(Embeds.AutomaticColor)
      .setTitle(title, raidType.flatMap(_.link).orNull)
      .setDescription(s"$locName\n$when$creatureBlock".take(4000))
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
