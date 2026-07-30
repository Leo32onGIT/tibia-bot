package com.tibiabot.presentation

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSettings, RespawnUserPrefs, Stamina}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

import java.time.ZonedDateTime

/** Pure embed builders for the respawn claim system. No JDA lookups, no I/O —
 *  everything they render is passed in, so the layout is unit-testable. */
object RespawnEmbeds {

  /** Green — the spawn is free to take. */
  val FreeColor: Int = 3066993
  /** The bot's red (as used for hunted activity and ally deaths) — a spawn that's
   *  taken, and a claim that's over. Named for the colour rather than either
   *  meaning, since one red serves both. */
  val RedColor: Int = 13773097
  /** The bot's yellow (see GuildActivity.activityColor) — a claim nearing its end. */
  val WarnColor: Int = 14397256

  /** Discord renders `<t:epoch:R>` as a live-updating "in 2 hours" that keeps
   *  counting down without the bot editing the message, which is why claim
   *  cards need no per-minute refresh. */
  private def relative(when: ZonedDateTime): String = s"<t:${when.toInstant.getEpochSecond}:R>"

  /** Wall-clock time in each reader's own timezone. Used for a hunt's start,
   *  where a relative "3 hours ago" tells you less than when it actually began. */
  private def clockTime(when: ZonedDateTime): String = s"<t:${when.toInstant.getEpochSecond}:t>"

  /** "2h", "45m", "1h 30m" — durations read better than a raw minute count in
   *  an embed field. */
  def humanDuration(minutes: Int): String = {
    val hours = minutes / 60
    val remainder = minutes % 60
    if (hours > 0 && remainder > 0) s"${hours}h ${remainder}m"
    else if (hours > 0) s"${hours}h"
    else s"${remainder}m"
  }

  /** How a claimant is named: their Tibia character when they gave one, always
   *  followed by the Discord mention so they can actually be pinged. */
  private def claimantLabel(claim: RespawnClaim): String =
    if (claim.characterName.nonEmpty) s"**${claim.characterName}** (<@${claim.userId}>)"
    else s"<@${claim.userId}>"

  /** The image for a spawn's thread — the main monster via the tibiawiki.com.br
   *  redirect, reusing the same URL builder and name mappings the boosted
   *  creature posts use. Falls back to a neutral sign for the many catalogue
   *  entries whose main creature isn't set yet.
   *
   *  `mappings` (Config.creatureUrlMappings) and `fallback`
   *  (Config.Respawn.fallbackImage) are passed in rather than read here, the
   *  same way [[Urls.creatureImageUrl]] takes them — it keeps this object free
   *  of config loading, which is what lets the whole presentation layer be
   *  unit-tested without a populated environment. */
  def imageFor(respawn: Respawn, mappings: Map[String, String], fallback: String): String =
    if (respawn.creature.nonEmpty) Urls.creatureImageUrl(respawn.creature, mappings)
    else fallback

  /** The claim card pinned as a spawn thread's opening message. Rewritten in
   *  place on every transition (claimed, released, queue promoted, freed)
   *  rather than posted anew, so the thread reads as one living card with the
   *  chatter below it. */
  def claimCard(respawn: Respawn, claim: Option[RespawnClaim], queue: List[RespawnClaim],
                settings: RespawnSettings, imageUrl: String): MessageEmbed = {
    val embed = new EmbedBuilder()
    embed.setTitle(respawn.displayName)
    embed.setImage(imageUrl)

    claim match {
      case Some(active) =>
        embed.setColor(RedColor)
        embed.setDescription(s"This respawn is currently being used by ${claimantLabel(active)}.")
        // Deliberately identical whether or not a handover is pending. Anything
        // conditional on limbo would cost a card edit when the offer goes out and
        // another when it resolves, and the spawn is still that person's either
        // way — so there is nothing to say.
        active.startsAt.foreach(start => embed.addField("Hunt start", clockTime(start), true))
        active.endsAt.foreach(end => embed.addField("Hunt end", relative(end), true))
      case None =>
        embed.setColor(FreeColor)
        embed.setDescription(s"This respawn is **free**.\nClaim it with `/respawn claim ${respawn.code}`.")
    }

    if (queue.nonEmpty) {
      // Only the first few, so a full 20-deep queue can't blow the 1024-char
      // field limit and drop the whole field silently.
      val shown = queue.take(10).zipWithIndex.map { case (entry, index) =>
        s"`${index + 1}.` ${claimantLabel(entry)} — ${humanDuration(entry.durationMinutes)}"
      }
      val overflow = if (queue.size > 10) s"\n…and ${queue.size - 10} more" else ""
      embed.addField(s"Queue (${queue.size}/${settings.queueLimit})", shown.mkString("\n") + overflow, false)
    }

    if (respawn.region.nonEmpty) embed.setFooter(respawn.region)
    embed.build()
  }

  /** Wraps DM copy in the same brand-coloured embed the rest of the bot's
   *  messages use, so a handover offer doesn't arrive as bare text while
   *  everything else is an embed. The spawn's creature goes in as a thumbnail
   *  rather than a full image: a DM is a notification, not a card to look at. */
  def dmEmbed(title: String, body: String, thumbnailUrl: String = "",
              color: Int = Embeds.BrandColor): MessageEmbed = {
    val embed = new EmbedBuilder().setColor(color).setTitle(title).setDescription(body)
    if (thumbnailUrl.nonEmpty) embed.setThumbnail(thumbnailUrl)
    embed.build()
  }

  /** The server's rules, shown to a moderator opening Config from the board and
   *  again after they change something. */
  def serverSettingsEmbed(settings: RespawnSettings): MessageEmbed =
    new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setTitle("Server respawn settings")
      .setDescription("These apply to everyone here. Members can set their own claim length " +
        "and reminder time, which override the defaults below.")
      .addField("Default claim", humanDuration(settings.defaultDurationMinutes), true)
      .addField("Maximum claim", humanDuration(settings.maxDurationMinutes), true)
      .addField("Queue limit", settings.queueLimit.toString, true)
      .addField("Daily stamina",
        if (settings.staminaMinutes <= 0) "unlimited" else humanDuration(settings.staminaMinutes), true)
      .addField("Default reminder",
        if (settings.warnMinutes <= 0) "off" else s"${humanDuration(settings.warnMinutes)} before the end", true)
      .addField("Handover window", humanDuration(settings.handoverMinutes), true)
      .build()

  /** The moderator panel for one spawn: who holds it, and what can be done to
   *  them. Rendered instead of going straight to a duration form, because the
   *  actions here affect somebody else's hunt. */
  def spawnModeratorPanel(respawn: Respawn, holder: Option[RespawnClaim], queueSize: Int): MessageEmbed = {
    val embed = new EmbedBuilder().setColor(Embeds.BrandColor).setTitle(respawn.displayName)
    holder match {
      case Some(claim) =>
        val ends = claim.endsAt.map(relative).getOrElse("unknown")
        embed.setDescription(s"Held by ${claimantLabel(claim)}, ending $ends.")
        embed.addField("Hunt length", humanDuration(claim.durationMinutes), true)
        if (queueSize > 0) embed.addField("Waiting", queueSize.toString, true)
        embed.setFooter("Force leave hands it to whoever is next, and refunds their unused stamina.")
      case None =>
        embed.setDescription("Nobody is on this respawn right now.")
    }
    embed.build()
  }

  /** Confirmation of a member's own settings, shown after the Config modal. */
  def userPrefsEmbed(prefs: RespawnUserPrefs, settings: RespawnSettings): MessageEmbed = {
    def shown(value: Option[Int], guildValue: Int, off: String): String = value match {
      case Some(0)       => off
      case Some(minutes) => humanDuration(minutes)
      // Naming the guild's value makes it clear the setting is following the
      // server rather than being unset in some broken way.
      case None          => s"${humanDuration(guildValue)} (server default)"
    }
    new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setTitle("Your respawn settings")
      .setDescription("These apply to your own claims on this server.")
      .addField("Default claim length",
        shown(prefs.defaultDurationMinutes, settings.defaultDurationMinutes, "—"), true)
      .addField("Remind me before the end",
        shown(prefs.warnMinutes, settings.warnMinutes, "off"), true)
      .setFooter(s"Claims can run up to ${humanDuration(settings.maxDurationMinutes)} on this server.")
      .build()
  }

  /** The handover offer, sent by DM with Claim/Cancel buttons.
   *
   *  Deliberately explicit that doing nothing loses the spot. The whole point of
   *  the confirmation step is that a spawn isn't handed to someone who has
   *  walked away, so people should know that silence costs them their place
   *  rather than discovering it afterwards. */
  def handoverOffer(respawn: Respawn, claim: RespawnClaim, guildName: String,
                    expiresAt: ZonedDateTime): String =
    s"**${respawn.displayName}** is ready for you in **$guildName**.\n" +
      s"Press **Claim** to take it for ${humanDuration(claim.durationMinutes)}.\n" +
      s"This offer expires ${relative(expiresAt)} — if you don't answer you lose your place in the queue."

  /** Sent by DM once someone accepts their handover offer. */
  def handoverAccepted(respawn: Respawn, claim: RespawnClaim): String = {
    val ends = claim.endsAt.map(relative).getOrElse("soon")
    s"**${respawn.displayName}** is yours — your claim ends $ends."
  }

  /** Sent by DM when an offer lapsed unanswered, so losing the spot isn't a
   *  silent surprise. */
  def handoverLapsed(respawn: Respawn): String =
    s"You didn't answer in time, so **${respawn.displayName}** has moved on to the next person " +
      "and you've been taken out of its queue."

  /** The "your time is nearly up" nudge, sent by DM rather than posted in the
   *  spawn's thread — it is aimed at one person, and a thread ping turns a
   *  shared card into a stream of notices nobody else needs. */
  /** Deliberately says nothing about `/respawn extend`: stretching a claim is the
   *  exception, not the expected reply to this, and offering it up invites people
   *  to hold spawns longer than they need. Leaving early is the useful action, so
   *  that is the one that gets a button. */
  def expiryWarning(respawn: Respawn, claim: RespawnClaim): String = {
    val ends = claim.endsAt.map(relative).getOrElse("shortly")
    s"Your claim on **${respawn.displayName}** ends $ends.\n" +
      "Click the leave button below if you have left the respawn already."
  }

  /** Sent by DM once a claim has actually run out, so its holder knows the spawn
   *  isn't theirs any more without going to look. */
  def claimEnded(respawn: Respawn): String =
    s"Your claim on **${respawn.displayName}** has ended."

  /** `/respawn list` — everything currently held, most-urgent first. */
  def activeClaimsList(claims: List[(Respawn, RespawnClaim, Int)]): MessageEmbed = {
    val embed = new EmbedBuilder().setColor(Embeds.BrandColor).setTitle("Claimed respawns")
    if (claims.isEmpty) {
      embed.setDescription("No respawns are claimed right now.")
    } else {
      val lines = claims.map { case (respawn, claim, queueSize) =>
        val ends = claim.endsAt.map(relative).getOrElse("unknown")
        val queueNote = if (queueSize > 0) s" · queue: $queueSize" else ""
        s"**${respawn.displayName}** — ${claimantLabel(claim)}, ends $ends$queueNote"
      }
      embed.setDescription(truncateLines(lines))
    }
    embed.build()
  }

  /** `/respawn status <spawn>` and the reply to a successful claim. */
  def statusEmbed(respawn: Respawn, claim: Option[RespawnClaim], queue: List[RespawnClaim],
                  settings: RespawnSettings, imageUrl: String, threadMention: Option[String]): MessageEmbed = {
    val embed = new EmbedBuilder(claimCard(respawn, claim, queue, settings, imageUrl))
    threadMention.foreach(mention => embed.appendDescription(s"\n\n$mention"))
    embed.build()
  }

  /** `/respawn stamina` — the fuel gauge, plus what's currently draining it. */
  def staminaEmbed(stamina: Stamina, openClaims: List[(Respawn, RespawnClaim)],
                   nextReset: ZonedDateTime): MessageEmbed = {
    val embed = new EmbedBuilder().setColor(Embeds.BrandColor).setTitle("Claim stamina")
    if (stamina.unlimited) {
      embed.setDescription("Stamina is **disabled** on this server — claim as much as you like.")
    } else {
      embed.setDescription(
        s"**${humanDuration(stamina.remainingMinutes)}** left of " +
          s"${humanDuration(stamina.budgetMinutes)}.\nRefills at server save ${relative(nextReset)}.")
      if (openClaims.nonEmpty) {
        val lines = openClaims.map { case (respawn, claim) =>
          val state = if (claim.isActive) "holding" else s"queued #${claim.queuePosition}"
          s"**${respawn.displayName}** — $state, ${humanDuration(claim.durationMinutes)}"
        }
        embed.addField("Reserved by", truncateLines(lines), false)
      }
    }
    embed.build()
  }

  /** The pinned, locked board post explaining the system. Its content is fixed,
   *  so `/repair` recreating it produces the same post. */
  def boardPost(settings: RespawnSettings): MessageEmbed = {
    val staminaLine =
      if (settings.staminaMinutes <= 0) "There is no daily limit on this server."
      else s"You get **${humanDuration(settings.staminaMinutes)}** of claim time per day. " +
        "It refills at server save (10:00 CET/CEST). Claiming reserves the full duration up front, " +
        "and releasing early gives the unused time back."

    new EmbedBuilder()
      .setColor(Embeds.BrandColor)
      .setTitle("📅 Respawn claims")
      .setDescription(
        "Every claimed respawn gets its own post in this channel showing who's on it and when they're done.\n\n" +
          "**Claiming**\n" +
          s"`/respawn claim <spawn>` takes a free respawn for ${humanDuration(settings.defaultDurationMinutes)} " +
          "(or pass your own duration).\n" +
          "`/respawn release` ends your claim early and hands it to whoever is next.\n" +
          "`/respawn extend <minutes>` adds time, up to " +
          s"${humanDuration(settings.maxDurationMinutes)} in total.\n\n" +
          "**Queueing**\n" +
          "If a respawn is taken, hit **Next** on its post to line up behind the current hunter. " +
          s"Up to ${settings.queueLimit} people can wait. When the claim ahead of you ends I'll **DM you** " +
          s"with a **Claim** button — press it within ${humanDuration(settings.handoverMinutes)} and the " +
          "respawn is yours. Ignore it or press **Cancel** and you drop out of the queue and it goes to the " +
          "next person.\nUntil you answer, the respawn stays with its previous hunter, so nobody else can " +
          "take it out from under you.\n\n" +
          "**Stamina**\n" + staminaLine + "\n" +
          "Holding two respawns at once is fine — they just both draw from the same tank.\n\n" +
          "**Buttons on this post**\n" +
          "**Claim** takes any respawn by code or name — use it for one that has no post yet.\n" +
          "**Config** sets your own default claim length and how long before the end you want reminding.\n\n" +
          "**Finding things**\n" +
          "`/respawn list` shows everything currently claimed, `/respawn status <spawn>` shows one, " +
          "and `/respawn stamina` shows what you have left.")
      .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Sign_(Library).gif")
      .setFooter("This post is read-only. Use the slash commands or the buttons on each respawn post.")
      .build()
  }

  /** A spawn's recent claim history, for `/respawn log`.
   *
   *  Absolute dates rather than relative ones: an audit is read to work out what
   *  happened at a particular time, and "3 days ago" is the wrong shape for that.
   *  Held time is shown alongside the booked length, since the gap between them
   *  is usually the thing being questioned. */
  def claimHistoryEmbed(respawn: Respawn, history: List[RespawnClaim]): MessageEmbed = {
    val embed = new EmbedBuilder().setColor(Embeds.BrandColor)
      .setTitle(s"Claim history — ${respawn.displayName}")
    if (history.isEmpty) {
      embed.setDescription("No finished claims on this respawn yet.")
    } else {
      val lines = history.map { claim =>
        val who = if (claim.characterName.nonEmpty) s"**${claim.characterName}** (<@${claim.userId}>)"
                  else s"<@${claim.userId}>"
        val when = claim.startsAt.orElse(claim.endedAt).orElse(Some(claim.claimedAt))
          .map(t => s"<t:${t.toInstant.getEpochSecond}:f>").getOrElse("unknown")
        val held = for {
          start <- claim.startsAt
          end <- claim.endedAt
        } yield java.time.Duration.between(start, end).toMinutes.toInt
        // A queue entry that never started has no held time, and saying "0m of 2h"
        // would read as though they took it and did nothing.
        val length = held match {
          case Some(minutes) => s"held ${humanDuration(math.max(0, minutes))} of ${humanDuration(claim.durationMinutes)}"
          case None          => s"booked ${humanDuration(claim.durationMinutes)}"
        }
        val why = claim.outcome.map(RespawnClaim.Outcome.label).getOrElse("ended")
        s"$when — $who\n\u2003$length · $why"
      }
      embed.setDescription(truncateLines(lines))
      embed.setFooter(s"${history.size} most recent")
    }
    embed.build()
  }

  /** Keep a rendered list inside Discord's 4096-character description limit,
   *  dropping whole lines rather than cutting one mid-mention. */
  private def truncateLines(lines: List[String], limit: Int = 3900): String = {
    val kept = lines.foldLeft((List.empty[String], 0)) { case ((acc, length), line) =>
      if (length + line.length + 1 > limit) (acc, length)
      else (line :: acc, length + line.length + 1)
    }._1.reverse
    val omitted = lines.size - kept.size
    if (omitted > 0) (kept :+ s"…and $omitted more").mkString("\n") else kept.mkString("\n")
  }
}
