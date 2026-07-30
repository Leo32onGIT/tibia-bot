package com.tibiabot.presentation

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSchedule, RespawnSettings, RespawnUserPrefs, Stamina}
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

  /** Wall-clock time in each reader's own timezone, for a hunt's start and end —
   *  a relative "3 hours ago" tells you less than the time it actually happened. */
  private def clockTime(when: ZonedDateTime): String = s"<t:${when.toInstant.getEpochSecond}:t>"

  /** Short date *and* time, for the audit log — its entries span days, where a
   *  bare clock time would be ambiguous. */
  private def dateTime(when: ZonedDateTime): String = s"<t:${when.toInstant.getEpochSecond}:f>"

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
                reservations: List[RespawnClaim], settings: RespawnSettings,
                imageUrl: String): MessageEmbed = {
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
        active.endsAt.foreach(end => embed.addField("Hunt end", clockTime(end), true))
        // A relative timestamp, so "in 40 minutes" counts itself down in every
        // reader's client without the bot editing the card. It also says what a
        // Duration field used to: the length is start to end, which is now two
        // fields along, and the time left is the part anyone still cares about.
        active.endsAt.foreach(end => embed.addField("Time left", relative(end), true))
      case None =>
        embed.setColor(FreeColor)
        embed.setDescription(s"This respawn is **free**.")
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

    if (reservations.nonEmpty) {
      // Booked slots that haven't started. Shown whether or not the spawn is free
      // right now, because the point of booking ahead is that people can plan
      // around it — and, from phase 2, ask for it.
      val shown = reservations.take(3).map { slot =>
        val when = slot.startsAt.map(dateTime).getOrElse("?")
        // Somebody is waiting on an answer — worth showing, or a second person
        // finds the Request button gone with no explanation.
        val pending = if (slot.requestPending) " · *asked*" else ""
        s"$when · ${humanDuration(slot.durationMinutes)} — ${claimantLabel(slot)}$pending"
      }
      val overflow = if (reservations.size > 3) s"\n…and ${reservations.size - 3} more" else ""
      embed.addField("Booked", shown.mkString("\n") + overflow, false)
    }

    if (respawn.region.nonEmpty) embed.setFooter(respawn.region)
    embed.build()
  }

  /** What somebody already holding a booking on a spawn sees when they press
   *  Schedule on it.
   *
   *  The whole spawn rather than just their own row: the reason they can't book
   *  again is that the times around theirs belong to other people, and a list of
   *  one booking says nothing about that. Their own line is marked rather than
   *  filtered out, so the ordering still reads as the evening it is.
   *
   *  `mine` is the schedule behind their booking, which is what carries the
   *  repeat — the occurrence rows know only their own start.
   */
  def bookingPanel(respawn: Respawn, mine: RespawnSchedule, reservations: List[RespawnClaim],
                   holder: Option[RespawnClaim], now: ZonedDateTime, imageUrl: String): MessageEmbed = {
    val embed = new EmbedBuilder().setColor(Embeds.BrandColor).setTitle(respawn.displayName)
    if (imageUrl.nonEmpty) embed.setThumbnail(imageUrl)

    val state = holder match {
      case Some(active) =>
        val until = active.endsAt.map(end => s" until ${clockTime(end)}").getOrElse("")
        s"Being hunted by ${claimantLabel(active)}$until."
      case None => "Free right now."
    }
    val yours = mine.nextStartAtOrAfter(now)
      .map(start => s"Your booking is ${mine.repeatLabel} at ${clockTime(start)} " +
        s"for ${humanDuration(mine.durationMinutes)}.")
      .getOrElse(s"Your booking on this respawn has been and gone.")
    embed.setDescription(s"$state $yours")

    if (reservations.nonEmpty) {
      // No cap: this is somebody looking at one spawn's evening on purpose, so
      // the three-line summary the claim card uses would hide the very slots they
      // are trying to book around. truncateLines keeps it inside the field limit.
      val lines = reservations.map { slot =>
        val when = slot.startsAt.map(dateTime).getOrElse("?")
        val pending = if (slot.requestPending) " · *asked*" else ""
        // An arrow rather than a highlight — an embed has no way to shade a line,
        // and bolding alone doesn't survive a list where every name is bold.
        val marker = if (slot.userId == mine.userId) "➤ " else ""
        s"$marker$when · ${humanDuration(slot.durationMinutes)} — ${claimantLabel(slot)}$pending"
      }
      embed.addField("Booked", truncateLines(lines, 1000), false)
    }

    embed.setFooter("Cancel yours to free it up. Only one booking per respawn each.")
    embed.build()
  }

  /** DM'd to a slot's owner when somebody asks for it.
   *
   *  Says plainly that silence hands the slot over — the deadline is the whole
   *  mechanism, and somebody should not discover it by losing a hunt. */
  def slotRequest(respawn: Respawn, slot: RespawnClaim, deadline: ZonedDateTime,
                  wanted: Option[(ZonedDateTime, Int)] = None): String = {
    val window = (slot.startsAt, slot.endsAt) match {
      case (Some(start), Some(end)) => s"${clockTime(start)}–${clockTime(end)}"
      case (Some(start), None)      => clockTime(start)
      case _                        => "your booked slot"
    }
    // Two ways to be asked, and they read differently. Pressing Request is
    // somebody wanting the slot itself; booking over it is somebody planning a
    // hunt of their own that happens to run across it, and saying so is what
    // makes the times in the message add up.
    //
    // The asker is a mention rather than a name: Discord resolves it by id, so it
    // reads as their current display name in a DM they are not part of, and it
    // notifies nobody — they are not a recipient of this channel. "Someone" is
    // the fallback for a row written before the id was recorded.
    val asker = slot.requesterUserId.map(id => s"<@$id>").getOrElse("Someone")
    val opening = wanted match {
      case Some((start, minutes)) =>
        s"**$asker** wants to book **${respawn.displayName}** from ${clockTime(start)} " +
          s"for ${humanDuration(minutes)}, which runs over your slot at $window."
      case None =>
        s"**$asker** would like **${respawn.displayName}** at $window."
    }
    s"$opening\nIf you don't answer by ${relative(deadline)} the slot goes to them."
  }

  /** DM'd to whoever asked, once the owner says they are hunting after all.
   *
   *  Both ends of the window, because the useful thing to know about a slot you
   *  can't have is when it frees up. */
  def slotRequestDeclined(respawn: Respawn, slot: RespawnClaim): String = {
    val when = (slot.startsAt, slot.bookedEnd) match {
      case (Some(start), Some(end)) => s" at ${dateTime(start)} until ${dateTime(end)}"
      case (Some(start), None)      => s" at ${dateTime(start)}"
      case _                        => ""
    }
    val booking = if (slot.requestedSlot.isDefined) " Your booking wasn't made." else ""
    s"<@${slot.userId}> has confirmed they are hunting **${respawn.displayName}**$when, " +
      s"so it stays theirs.$booking"
  }

  /** DM'd to whoever asked, once the slot passes to them. The window is passed in
   *  rather than read off the slot: what they get is what they asked for, which
   *  is the slot itself only when they asked for it with the Request button. */
  def slotRequestGranted(respawn: Respawn, start: ZonedDateTime, minutes: Int): String =
    s"**${respawn.displayName}** is yours at ${dateTime(start)} for " +
      s"${humanDuration(minutes)} — it'll start on its own, no need to claim it."

  /** DM'd to whoever asked when the slot was given up but their own window has
   *  since been booked around them. Says what happened rather than just failing:
   *  the time really is free now, and they can take it the ordinary way. */
  def slotRequestBlocked(respawn: Respawn, start: ZonedDateTime, minutes: Int): String =
    s"The slot you asked about on **${respawn.displayName}** has been given up, but the " +
      s"${humanDuration(minutes)} you wanted from ${dateTime(start)} now runs over somebody " +
      "else's booking, so it hasn't been booked for you.\n" +
      "Book a shorter slot, or claim it on the night."

  /** DM'd shortly before a booked slot begins, so its owner can be there for it —
   *  or free it up if they can't. */
  def slotReminder(respawn: Respawn, slot: RespawnClaim): String = {
    val start = slot.startsAt.map(relative).getOrElse("shortly")
    s"Your booked slot on **${respawn.displayName}** starts $start for " +
      s"${humanDuration(slot.durationMinutes)}."
  }

  /** DM'd when a booked slot starts. */
  def slotStarted(respawn: Respawn, claim: RespawnClaim): String = {
    val ends = claim.endsAt.map(clockTime).getOrElse("soon")
    s"Your booked slot on **${respawn.displayName}** has started and runs until $ends."
  }

  /** DM'd when a booked slot arrives to find somebody already hunting. */
  def slotOccupied(respawn: Respawn, holder: Option[RespawnClaim]): String = {
    val who = holder.map(c => s" by <@${c.userId}>").getOrElse("")
    val until = holder.flatMap(_.endsAt).map(end => s" until ${clockTime(end)}").getOrElse("")
    s"**${respawn.displayName}** was already being hunted$who$until when your slot came round, " +
      "so you're first in the queue and I'll offer it to you the moment they finish."
  }

  /** DM'd when a booked slot can't start because the tank is spent. */
  def slotNoStamina(respawn: Respawn, needed: Int, stamina: Stamina,
                    resetsAt: ZonedDateTime): String =
    s"Your booked slot on **${respawn.displayName}** needed " +
      s"**${humanDuration(needed)}** but you have " +
      s"**${humanDuration(stamina.remainingMinutes)}** of stamina left, so it was skipped.\n" +
      s"Your tank refills at server save ${relative(resetsAt)}."

  /** Standing bookings, for the Schedule panel and `/respawn schedules`.
   *
   *  `everyones` switches from "yours" to the whole server, which is what a
   *  moderator sees — and names the owner, since otherwise the list is a wall of
   *  spawns with no way to tell whose is whose. */
  def schedulesEmbed(entries: List[(RespawnSchedule, Respawn)], now: ZonedDateTime,
                     everyones: Boolean = false): MessageEmbed = {
    val embed = new EmbedBuilder().setColor(Embeds.BrandColor)
      .setTitle(if (everyones) "Booked slots on this server" else "Your booked slots")
    if (entries.isEmpty) {
      embed.setDescription(
        if (everyones) "Nobody has booked a slot yet."
        else "You have no booked slots. Use **Schedule** on a respawn's post to book one.")
    } else {
      embed.setDescription(truncateLines(entries.map { case (schedule, respawn) =>
        val who = if (everyones) s" — <@${schedule.userId}>" else ""
        // A spent one-off can still be listed for the moment between its slot
        // passing and the sweep retiring it, so there may be no next slot.
        val next = schedule.nextStartAtOrAfter(now)
          .map(start => s", next ${dateTime(start)}").getOrElse(", done")
        s"**${respawn.displayName}**$who\n\u2003${schedule.repeatLabel}$next " +
          s"for ${humanDuration(schedule.durationMinutes)}"
      }))
    }
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
                  reservations: List[RespawnClaim], settings: RespawnSettings, imageUrl: String,
                  threadMention: Option[String]): MessageEmbed = {
    val embed = new EmbedBuilder(claimCard(respawn, claim, queue, reservations, settings, imageUrl))
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
        // The end shown is when the hunt actually stopped, not when it was booked
        // to — a claim released early or taken over ends before its deadline, and
        // the real one is what an audit is looking for.
        val span = (claim.startsAt, claim.endedAt) match {
          case (Some(start), Some(end)) => s"${dateTime(start)} \u2192 ${dateTime(end)}"
          case (Some(start), None)      => s"${dateTime(start)} \u2192 ?"
          // A queue entry that never reached the front has no hunt at all.
          case (None, Some(end))        => s"never started, ended ${dateTime(end)}"
          case (None, None)             => s"queued ${dateTime(claim.claimedAt)}, never started"
        }
        val held = for {
          start <- claim.startsAt
          end <- claim.endedAt
        } yield java.time.Duration.between(start, end).toMinutes.toInt
        // Saying "0m of 2h" for something that never started would read as though
        // they took it and did nothing.
        val length = held match {
          case Some(minutes) => s"held ${humanDuration(math.max(0, minutes))} of ${humanDuration(claim.durationMinutes)}"
          case None          => s"booked ${humanDuration(claim.durationMinutes)}"
        }
        val why = claim.outcome.map(RespawnClaim.Outcome.label).getOrElse("ended")
        s"$who\n\u2003$span\n\u2003$length \u00b7 $why"
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
