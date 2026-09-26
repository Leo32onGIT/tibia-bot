package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.{MiniWorldChange, ObserverStatus, ObserverToken, RaidAnnouncement}
import com.tibiabot.observer.{MiniWorldChangeCatalog, ObserverAreas, ObserverMembers, ObserverPanel, RaidCreature, RaidType, WorldCoverage}
import com.tibiabot.statistics.BossCatalogue
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.mediagallery.{MediaGallery, MediaGalleryItem}
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.thumbnail.Thumbnail
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.utils.messages.{MessageCreateBuilder, MessageCreateData}

import java.time.Instant
import scala.jdk.CollectionConverters._

/** The `/observer` panel — a member's own Tibia Observer link and what it adds to
 *  this server, with the Add / Remove controls — and the Observer posts: the raid
 *  stage cards and lines, and the mini world changes block. The panel is
 *  ephemeral, so it only ever shows one member their own link. */
object ObserverEmbeds {

  /** tibia.com's Connect page, where a member gets a token (tibia-bot-resources). */
  val ConnectPicture = "https://violentbot.xyz/discord/observer/connect.png"

  /** All the text a message's components may hold between them. */
  private val TextLimit = 4000

  /** Over the worlds' checklists, in the same section. */
  private val CoverageHeading = "### 🗺️ Raid Coverage"

  /** The `/observer` reply: the card, then Add and Remove, and for a member who
   *  can manage the server (`manager`) the ℹ️ button to the members list. `yes`
   *  and `no` are the configured emoji; a test passes its own. */
  def panel(view: ObserverPanel, manager: Boolean = false, yes: String = Config.yesEmoji,
            no: String = Config.noEmoji): List[MessageTopLevelComponent] =
    List(panelCard(view, yes, no), controls(view.token, manager))

  /** What the ℹ️ button shows: the members list, then Back to the member's own
   *  card. */
  def members(view: ObserverMembers, yes: String = Config.yesEmoji, no: String = Config.noEmoji): List[MessageTopLevelComponent] =
    List(membersCard(view, yes, no), ActionRow.of(Button.secondary(BackButton, "⤶ Back")))

  val MembersButton = "observer members"
  val BackButton = "observer back"

  /** The members list: a heading, every member linked in this server with the areas
   *  they cover on each of its worlds, then per world how many raid areas are
   *  covered and what accounts from other Discords add. Members that don't fit in
   *  a message are counted in a line of their own. */
  def membersCard(view: ObserverMembers, yes: String = Config.yesEmoji, no: String = Config.noEmoji): Container = {
    val header = "### 👥 Linked Members\n-# Members of this server with a Tibia Observer token added, and the raid areas each covers."
    val entries = view.members.map { m =>
      val who = s"<@${m.userId}>"
      if (!m.working) s"$no $who\n-# Token unlinked or expired"
      else if (m.worlds.isEmpty) s"$yes $who\n-# No characters on this server's worlds"
      else (s"$yes $who" :: m.worlds.map { case (world, areas) =>
        s"-# **${world.toUpperCase(java.util.Locale.ROOT)}** · ${if (areas.isEmpty) "Nothing explored yet" else areas.mkString(", ")}"
      }).mkString("\n")
    }
    val several = view.worlds.sizeIs > 1
    val footer = view.worlds.flatMap { w =>
      s"-# ${w.raidCovered} of ${ObserverAreas.raidAreas.size} raid areas covered on ${w.world}" ::
        Option.when(w.otherAreas.nonEmpty) {
          val who = if (w.otherAccounts == 1) "1 account from another Discord covers"
            else s"${w.otherAccounts} accounts from other Discords cover"
          s"-# $who ${w.otherAreas.mkString(", ")}${if (several) s" on ${w.world}" else ""}"
        }.toList
    }.mkString("\n")
    val list =
      if (entries.isEmpty) "-# Nobody in this server has added a token yet."
      else {
        def more(n: Int) = s"-# …and $n more ${if (n == 1) "member" else "members"}"
        def text(n: Int) = (entries.take(n) ++ Option.when(n < entries.size)(more(entries.size - n))).mkString("\n")
        val fits = (entries.size to 0 by -1).find(n => header.length + footer.length + text(n).length <= TextLimit).getOrElse(0)
        text(fits)
      }
    val parts = List[ContainerChildComponent](TextDisplay.of(header), divider, TextDisplay.of(list)) ++
      (if (footer.isEmpty) Nil else List(divider, TextDisplay.of(footer)))
    Container.of(parts.asJava)
  }

  /** The card. A heading, the member's link, then the raid-area coverage of the
   *  worlds in `view` under a heading of its own — each world a label with a
   *  checklist of the areas under it — and one line under them saying what to make
   *  of it. Sections have dividers between them. */
  def panelCard(view: ObserverPanel, yes: String = Config.yesEmoji, no: String = Config.noEmoji): Container = {
    val status = view.token.map(_.status)
    val intro = status match {
      case None | Some(ObserverStatus.Pending) => "Link your Tibia account to add raid alerts and mini world changes for this server."
      case _                                   => "Raid alerts and mini world changes, pooled from every linked member."
    }
    val header = s"### 🔭 Tibia Observer\n-# $intro"
    val statusText = view.token match {
      case None    => s"$no You haven't linked a Tibia Observer token.\n$HowToLink"
      case Some(t) => linkText(t, view.worlds.map(_.world), yes, no)
    }
    val footer = status match {
      case None                                                    => Some("Link your account to add the areas you've explored.")
      case Some(ObserverStatus.Linked)                             => Some("The areas in bold are the ones your account covers.")
      case Some(ObserverStatus.NeedsRelink | ObserverStatus.Error) => Some("Add a fresh token to count your explored areas again.")
      case Some(ObserverStatus.Pending)                            => None
    }
    // With no token that works, the picture of where to get one goes under how to.
    val linkPart: List[ContainerChildComponent] =
      if (status.forall(_ == ObserverStatus.NeedsRelink))
        List(TextDisplay.of(statusText), MediaGallery.of(MediaGalleryItem.fromUrl(ConnectPicture)))
      else List(TextDisplay.of(statusText))
    val coverage = coverageTexts(view.worlds, footer, header.length + statusText.length + CoverageHeading.length, yes, no) match {
      case Nil   => Nil
      case texts => (CoverageHeading :: texts).map(TextDisplay.of)
    }
    val sections = List(List(TextDisplay.of(header)), linkPart) ++ Option.when(coverage.nonEmpty)(coverage)
    Container.of(sections.zipWithIndex.flatMap { case (s, i) => if (i == 0) s else divider :: s }.asJava)
  }

  private def divider: Separator = Separator.createDivider(Separator.Spacing.SMALL)

  /** How to get a token and add it, under the status when the member has none that
   *  works. Joined explicitly: a multi-line literal takes the checkout's line
   *  endings. */
  private val HowToLink = List(
    "-# Click the **Add** button below and enter the token from your Tibia Account.",
    "-# Account Management → Tibia Observer → Connect").mkString("\n")

  /** A member's link: working, with the worlds it covers here; unlinked on
   *  Observer's side or expired, with how to add a fresh token; or stored before
   *  linking went live. */
  private def linkText(t: ObserverToken, worlds: List[String], yes: String, no: String): String = t.status match {
    // Not the account's name in Observer: that's nothing players use or recognise.
    case ObserverStatus.Linked =>
      val here = if (worlds.isEmpty) "None of your worlds is set up here"
        else s"Covering ${listed(worlds.map(w => s"**$w**"))} for this server"
      s"$yes Observer Token linked\n-# $here"
    case ObserverStatus.NeedsRelink =>
      s"$no Your Observer token has been unlinked or has expired.\n$HowToLink"
    case ObserverStatus.Error =>
      s"$no Something went wrong with your link — try **Add** again."
    case ObserverStatus.Pending =>
      s"$yes Token saved — it will be verified once linking is enabled."
  }

  private def listed(xs: List[String]): String =
    if (xs.sizeIs <= 1) xs.mkString else s"${xs.init.mkString(", ")} and ${xs.last}"

  /** One world's coverage: a label saying how many raid areas are covered, then
   *  every area with `yes` or `no`, the member's own in bold. */
  def worldCoverageText(w: WorldCoverage, yes: String = Config.yesEmoji, no: String = Config.noEmoji): String = {
    val areas = ObserverAreas.raidAreas
    val lines = ServerSaveCard.label(s"${w.world} · ${w.covered.size} of ${areas.size} raid areas") ::
      areas.map(area => w.covered.get(area) match {
        case Some(true)  => s"$yes **$area**"
        case Some(false) => s"$yes $area"
        case None        => s"$no $area"
      })
    lines.mkString("\n")
  }

  /** The coverage texts, as many worlds as fit in what the message has left after
   *  `used` characters, then the footer. Should a guild have set up more worlds than
   *  fit, the ones left out are named in a line of their own. Nothing when there are
   *  no worlds to show. */
  private[presentation] def coverageTexts(worlds: List[WorldCoverage], footer: Option[String], used: Int,
                                          yes: String, no: String): List[String] =
    if (worlds.isEmpty) Nil
    else {
      val texts = worlds.map(w => w.world -> worldCoverageText(w, yes, no))
      val footerLine = footer.map(f => s"-# $f").toList
      def more(rest: List[String]) = s"-# ${rest.size} more ${if (rest.sizeIs == 1) "world" else "worlds"} didn't fit: ${rest.mkString(", ")}"
      def lines(n: Int) = texts.take(n).map(_._2) ++ Option.when(n < texts.size)(more(texts.drop(n).map(_._1))) ++ footerLine
      val fits = (texts.size to 0 by -1).find(n => used + lines(n).map(_.length).sum <= TextLimit).getOrElse(0)
      lines(fits)
    }

  /** The mini world change art the block carried until 25 Sep 2026. Only read
   *  now, to recognise the block in a message posted before then. */
  private val MwcThumbnail = "https://violentbot.xyz/discord/observer/miniworldchange.png"

  /** The block's title, and so how it is told apart from the other blocks once
   *  it has no picture: its first line has it, both today's heading and the
   *  `Mini World Changes for <world>` one it had until 26 Sep 2026. */
  private val MwcTitle = "Mini World Changes"

  /** What leads the heading. A standard emoji, like the tracker's ⏳, so it takes
   *  none of the bot's own emoji slots. It was the bot's `:mwc:` until 26 Sep 2026. */
  private val MwcEmoji = "🌐"

  /** Room for the changes. The server-save card (see ServerSaveCard) is one V2
   *  message, and Discord allows 4,000 characters of text across the whole of
   *  one. This leaves the other five blocks, about 150 each, a thousand. */
  val MaxMwcDescription = 3000

  /** The Mini World Changes block in a guild's server-save notifications message,
   *  for its world — the same world the Dream Courts block names. It opens the way
   *  the cooldown tracker and role card above it do: its heading, a small grey line
   *  saying which world and for how long, and a divider (a blank line here, which
   *  ServerSaveCard turns into one). Then each change: its name, linked to its wiki
   *  page, with the feed's description as a small grey line under it. No picture,
   *  so the text has the card's whole width. `None` when nothing is active, so a
   *  quiet day (or a world no linked member covers, which the feed can't tell
   *  apart) just leaves the block out. */
  def serverSaveMwcEmbed(world: String, changes: List[MiniWorldChange],
                         emoji: String = Config.raidEmoji): Option[MessageEmbed] =
    if (changes.isEmpty) None
    else {
      val heading = s"### $MwcEmoji $MwcTitle\n-# Active on **$world** until the next server save."
      val entries = changes.map { c =>
        val name = s"### $emoji **[${c.title}](${MiniWorldChangeCatalog.wikiUrl(c.title)})**"
        // `-#` only reaches the end of its line, so the body is kept to one.
        val body = c.body.trim.replaceAll("\\s*\\n\\s*", " ")
        if (body.nonEmpty) s"$name\n-# $body" else name
      }
      // Whole entries only, so a long day can never cut a link in half.
      val lengths = entries.scanLeft(heading.length + 1)(_ + 1 + _.length).tail
      val kept = entries.zip(lengths).takeWhile(_._2 <= MaxMwcDescription).map(_._1)
      Some(new EmbedBuilder()
        .setDescription(s"$heading\n\n${kept.mkString("\n")}")
        .setColor(Embeds.BrandColor)
        .build())
    }

  /** Whether a block is the notifications message's mini world changes, told
   *  apart by what its first line says: it has moved within the message, so its
   *  place says nothing. A message from before 25 Sep 2026 has the old wording,
   *  and is told apart by the picture it carried then. */
  def isServerSaveMwcEmbed(embed: MessageEmbed): Boolean =
    Option(embed.getDescription).flatMap(_.linesIterator.nextOption()).exists(_.contains(MwcTitle)) ||
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
   *  linked to its wiki page, over the subarea in bold behind a map and when it
   *  started, with a picture of its boss or lead creature beside them. Under a
   *  divider, its creatures as wiki-linked bullets. Its broadcast lines follow. A
   *  raid the catalogue doesn't know has the subarea as its header, as the posts
   *  before it do, and no picture or creatures. */
  def startedCard(raid: RaidAnnouncement, raidType: Option[RaidType],
                  emoji: String = Config.raidEmoji): Container = {
    val started = raid.startDate.map(start => timeLine("Raid started", start)).toList
    val where = raid.subarea.orElse(raidType.flatMap(_.subarea)).filter(_.nonEmpty).getOrElse(areaOf(raid, raidType))
    raidType match {
      case None =>
        stageCard(TextDisplay.of(stageText("Raid started", s"$emoji $where", started)))
      case Some(rt) =>
        val name = rt.link.fold(rt.name)(url => s"[${rt.name}]($url)")
        val text = TextDisplay.of(stageText("Raid started", s"$emoji $name", s"-# **🗺️ $where**" :: started))
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

  /** Add is offered when there is no token, or one that needs replacing — the
   *  panel tells its member to press it; Remove whenever there is one. A button
   *  not offered is shown disabled so the panel always reads as a pair (as
   *  `/boosted` does). A member who can manage the server also gets ℹ️, the
   *  members list; nobody else sees it at all. */
  def controls(token: Option[ObserverToken], manager: Boolean = false): ActionRow = {
    val replaceable = token.forall(t => t.status == ObserverStatus.NeedsRelink || t.status == ObserverStatus.Error)
    val buttons = List(
      Button.success("observer add", "Add").withDisabled(!replaceable),
      Button.danger("observer remove", "Remove").withDisabled(token.isEmpty)) ++
      Option.when(manager)(Button.secondary(MembersButton, Emoji.fromUnicode("ℹ️")))
    ActionRow.of(buttons.asJava)
  }
}
