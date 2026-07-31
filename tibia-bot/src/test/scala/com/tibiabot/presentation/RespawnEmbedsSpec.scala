package com.tibiabot.presentation

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSchedule, RespawnSettings, Stamina}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import scala.jdk.CollectionConverters._

class RespawnEmbedsSpec extends AnyFunSuite with Matchers {

  private val now = ZonedDateTime.parse("2026-07-30T12:00:00Z")

  private val cultOrcs = Respawn(
    id = 1L, code = "415", name = "Cult Orcs", creature = "Orc Cult Fanatic", region = "Edron",
    world = "", mapperLink = "", threadId = "0", source = Respawn.SourceSeed, addedBy = "seed")

  private val unmapped = cultOrcs.copy(id = 2L, code = "1501", name = "Dark Grounds", creature = "")

  private val settings = RespawnSettings(
    forumChannel = "1", boardThread = "2", defaultDurationMinutes = 120, maxDurationMinutes = 240,
    queueLimit = 20, staminaMinutes = 240, warnMinutes = 10, handoverMinutes = 10)

  private def claim(userId: String, minutes: Int = 120, status: String = RespawnClaim.StatusActive,
                    position: Int = 0, character: String = "") = RespawnClaim(
    id = 10L, respawnId = 1L, userId = userId, userName = "someone", characterName = character,
    status = status, queuePosition = position, claimedAt = now, startsAt = Some(now),
    endsAt = Some(now.plusMinutes(minutes.toLong)), durationMinutes = minutes, warned = false,
    kind = RespawnClaim.KindAdHoc, limboUntil = None, offerExpiresAt = None,
    outcome = None, endedAt = None, scheduleId = None)

  // Passed explicitly rather than read from Config, which cannot initialise in
  // tests (it requires a populated environment) — the same reason UrlsSpec
  // supplies its own mappings.
  private val mappings = Map.empty[String, String]
  private val fallback = "https://example.invalid/fallback.gif"
  private def image(respawn: Respawn) = RespawnEmbeds.imageFor(respawn, mappings, fallback)

  private def fields(embed: net.dv8tion.jda.api.entities.MessageEmbed): Map[String, String] =
    embed.getFields.asScala.map(f => f.getName -> f.getValue).toMap

  test("humanDuration reads as a duration, not a minute count") {
    RespawnEmbeds.humanDuration(120) shouldBe "2h"
    RespawnEmbeds.humanDuration(90) shouldBe "1h 30m"
    RespawnEmbeds.humanDuration(45) shouldBe "45m"
  }

  test("a claimed spawn names the holder") {
    val embed = RespawnEmbeds.claimCard(cultOrcs, Some(claim("99", character = "Galarzaa")), Nil, Nil, settings, image(cultOrcs))
    embed.getTitle shouldBe "415 — Cult Orcs"
    embed.getDescription should include("Galarzaa")
    embed.getDescription should include("<@99>")
    embed.getColorRaw shouldBe RespawnEmbeds.RedColor
  }

  test("a claimant with no character is still pingable") {
    val embed = RespawnEmbeds.claimCard(cultOrcs, Some(claim("99")), Nil, Nil, settings, image(cultOrcs))
    embed.getDescription should include("<@99>")
  }

  test("a free spawn is green and says nothing about slash commands") {
    val embed = RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings, image(cultOrcs))
    embed.getColorRaw shouldBe RespawnEmbeds.FreeColor
    embed.getDescription should include("free")
    // The card carries a Claim button, so telling people to type a command
    // instead would be pointing away from the thing right under the text.
    embed.getDescription should not include "/respawn"
    // Nothing to show for a spawn nobody is on.
    fields(embed).keys should contain noneOf ("Hunt start", "Hunt end", "Duration", "Time left")
  }

  test("the card carries no settings blurb — duration and queue limit aren't news to the reader") {
    val free = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings, image(cultOrcs)))
    val taken = fields(RespawnEmbeds.claimCard(cultOrcs, Some(claim("1")), Nil, Nil, settings, image(cultOrcs)))
    free.keys should not contain "Options"
    taken.keys should not contain "Options"
  }

  test("a claim shows start and end as clock times, and nothing restating them") {
    val embed = RespawnEmbeds.claimCard(cultOrcs, Some(claim("99", minutes = 90)), Nil, Nil, settings, image(cultOrcs))
    // Start and end as short time: the clock time a hunt runs to is what someone
    // deciding whether to queue needs, and it is the same for every reader.
    fields(embed)("Hunt start") should endWith(":t>")
    fields(embed)("Hunt end") should endWith(":t>")
    // No Duration: it is start to end, both of which are right there, and the
    // part still worth reading is Time left.
    fields(embed).keys should not contain "Duration"
  }

  test("a claim shows how long is left, relative, off the same instant as the end") {
    val active = claim("99", minutes = 90)
    val embed = RespawnEmbeds.claimCard(cultOrcs, Some(active), Nil, Nil, settings, image(cultOrcs))
    // Relative so it counts itself down client-side rather than by card edits.
    // In limbo it passes the end and reads "5 minutes ago", which is the truth:
    // their time is up and the spawn is waiting on the next person.
    fields(embed)("Time left") shouldBe s"<t:${active.endsAt.get.toInstant.getEpochSecond}:R>"
  }

  test("a card renders identically whether or not a handover is pending") {
    // This is what lets a handover cost zero Discord edits: if limbo changed the
    // card at all, the offer going out and being answered would each need one.
    val holding = claim("99", character = "Galarzaa")
    val handingOver = holding.copy(limboUntil = Some(now.plusMinutes(10)))
    val before = RespawnEmbeds.claimCard(cultOrcs, Some(holding), Nil, Nil, settings, image(cultOrcs))
    val during = RespawnEmbeds.claimCard(cultOrcs, Some(handingOver), Nil, Nil, settings, image(cultOrcs))
    during.getDescription shouldBe before.getDescription
    during.getColorRaw shouldBe before.getColorRaw
    fields(during) shouldBe fields(before)
  }

  test("the handover offer says what happens if it's ignored") {
    val offer = claim("7", minutes = 90)
    val text = RespawnEmbeds.handoverOffer(cultOrcs, offer, "Violent Bot Dev", now.plusMinutes(10))
    text should include("415 — Cult Orcs")
    text should include("Violent Bot Dev")
    text should include("1h 30m")
    // Silence costing the queue place is the whole point of confirming, so it
    // must be stated rather than discovered.
    text should include("lose your place")
  }

  test("a lapsed offer explains the spot is gone rather than going silent") {
    RespawnEmbeds.handoverLapsed(cultOrcs) should include("415 — Cult Orcs")
    RespawnEmbeds.handoverLapsed(cultOrcs) should include("moved on")
  }

  test("the expiry warning counts down live and points at the Leave button") {
    val text = RespawnEmbeds.expiryWarning(cultOrcs, claim("7"))
    // A relative timestamp keeps counting down on its own; a baked-in "8m" is
    // wrong the moment it's read.
    text should include(":R>")
    text should include("leave button")
    // Extending is deliberately not suggested — it encourages holding spawns
    // longer than needed.
    text should not include "/respawn extend"
    // DM'd, so it must not carry a mention that would ping a shared thread.
    text should not include "<@"
  }

  test("a claim that ended says so plainly") {
    RespawnEmbeds.claimEnded(cultOrcs) should include("415 — Cult Orcs")
    RespawnEmbeds.claimEnded(cultOrcs) should include("has ended")
  }

  test("DM embeds carry the colour they're given, defaulting to the brand") {
    RespawnEmbeds.dmEmbed("t", "b").getColorRaw shouldBe Embeds.BrandColor
    RespawnEmbeds.dmEmbed("t", "b", "", RespawnEmbeds.WarnColor).getColorRaw shouldBe RespawnEmbeds.WarnColor
    RespawnEmbeds.dmEmbed("t", "b", "", RespawnEmbeds.RedColor).getColorRaw shouldBe RespawnEmbeds.RedColor
  }

  test("the queue is only shown when someone is waiting, and is numbered") {
    fields(RespawnEmbeds.claimCard(cultOrcs, Some(claim("1")), Nil, Nil, settings, image(cultOrcs))).keys.exists(_.startsWith("Queue")) shouldBe false

    val queue = List(claim("2", status = RespawnClaim.StatusQueued, position = 1),
      claim("3", status = RespawnClaim.StatusQueued, position = 2))
    val shown = fields(RespawnEmbeds.claimCard(cultOrcs, Some(claim("1")), queue, Nil, settings, image(cultOrcs)))("Queue (2/20)")
    shown should include("`1.`")
    shown should include("<@2>")
    shown should include("<@3>")
  }

  test("a long queue is truncated so the field can't exceed Discord's limit") {
    val queue = (1 to 20).map(i => claim(i.toString, status = RespawnClaim.StatusQueued, position = i)).toList
    val shown = fields(RespawnEmbeds.claimCard(cultOrcs, Some(claim("0")), queue, Nil, settings, image(cultOrcs)))("Queue (20/20)")
    shown should include("…and 10 more")
    shown.length should be < 1024
  }

  test("the image is the main monster via the tibiawiki redirect") {
    val embed = RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings, image(cultOrcs))
    Option(embed.getImage).map(_.getUrl).getOrElse("") should include("tibiawiki.com.br")
    Option(embed.getImage).map(_.getUrl).getOrElse("") should include("Orc_Cult_Fanatic")
  }

  test("a spawn with no creature mapped falls back rather than rendering a broken image") {
    // Most of the seed catalogue starts with no creature set, so this is the
    // common case, not an edge case.
    image(unmapped) shouldBe fallback
  }

  test("the claimed list summarises holder, deadline and queue depth") {
    val embed = RespawnEmbeds.activeClaimsList(List((cultOrcs, claim("7"), 3)))
    embed.getDescription should include("415 — Cult Orcs")
    embed.getDescription should include("<@7>")
    embed.getDescription should include("queue: 3")
  }

  test("an empty claimed list says so instead of rendering blank") {
    RespawnEmbeds.activeClaimsList(Nil).getDescription should include("No respawns are claimed")
  }

  test("the stamina gauge shows what's left and when it refills") {
    val tank = Stamina("7", usedMinutes = 90, budgetMinutes = 240, resetAt = now)
    val embed = RespawnEmbeds.staminaEmbed(tank, List((cultOrcs, claim("7"))), now.plusHours(6))
    embed.getDescription should include("2h 30m")
    embed.getDescription should include("Refills at server save")
    fields(embed)("Reserved by") should include("415 — Cult Orcs")
  }

  test("a disabled stamina budget says so rather than showing a nonsense number") {
    val tank = Stamina("7", usedMinutes = 0, budgetMinutes = 0, resetAt = now)
    RespawnEmbeds.staminaEmbed(tank, Nil, now).getDescription should include("disabled")
  }

  test("the server settings panel names every rule a moderator can change") {
    val fs = fields(RespawnEmbeds.serverSettingsEmbed(settings))
    fs.keys should contain allOf ("Default claim", "Maximum claim", "Queue limit",
      "Daily stamina", "Default reminder", "Handover window")
    fs("Default claim") shouldBe "2h"
    fs("Queue limit") shouldBe "20"
  }

  test("the server settings panel spells out the disabled cases rather than showing 0") {
    val off = settings.copy(staminaMinutes = 0, warnMinutes = 0)
    val fs = fields(RespawnEmbeds.serverSettingsEmbed(off))
    fs("Daily stamina") shouldBe "unlimited"
    fs("Default reminder") shouldBe "off"
  }

  test("the spawn moderator panel names the holder and what force leave will do") {
    val embed = RespawnEmbeds.spawnModeratorPanel(cultOrcs, Some(claim("99", character = "Galarzaa")), 2)
    embed.getDescription should include("Galarzaa")
    embed.getDescription should include("<@99>")
    fields(embed)("Hunt length") shouldBe "2h"
    fields(embed)("Waiting") shouldBe "2"
    // The consequence matters: it hands the spawn on rather than just freeing it.
    Option(embed.getFooter).map(_.getText).getOrElse("") should include("refunds")
  }

  test("the spawn moderator panel copes with a spawn nobody is on") {
    val embed = RespawnEmbeds.spawnModeratorPanel(cultOrcs, None, 0)
    embed.getDescription should include("Nobody is on this respawn")
    fields(embed) shouldBe empty
  }

  test("the spawn moderator panel hides the queue count when nobody is waiting") {
    fields(RespawnEmbeds.spawnModeratorPanel(cultOrcs, Some(claim("99")), 0)).keys should not contain "Waiting"
  }

  test("the claim log shows each hunt's span, length and reason") {
    val held = claim("99", minutes = 120, character = "Galarzaa").copy(
      startsAt = Some(now.minusHours(3)),
      endedAt = Some(now.minusHours(2)),
      outcome = Some(RespawnClaim.Outcome.Released))
    val text = RespawnEmbeds.claimHistoryEmbed(cultOrcs, List(held)).getDescription
    text should include("Galarzaa")
    // Short date AND time: log entries span days, so a bare clock time would be
    // ambiguous.
    text should include(s"<t:${now.minusHours(3).toInstant.getEpochSecond}:f>")
    text should include(s"<t:${now.minusHours(2).toInstant.getEpochSecond}:f>")
    // Held against booked is usually the thing being questioned.
    text should include("held 1h of 2h")
    text should include("left early")
  }

  test("the claim log distinguishes a queue entry that never started") {
    val neverStarted = claim("7", minutes = 60).copy(
      startsAt = None, endsAt = None,
      endedAt = Some(now),
      outcome = Some(RespawnClaim.Outcome.LeftQueue))
    val text = RespawnEmbeds.claimHistoryEmbed(cultOrcs, List(neverStarted)).getDescription
    text should include("never started")
    // "held 0m of 1h" would read as though they took it and did nothing.
    text should include("booked 1h")
    text should not include "held"
    text should include("left the queue")
  }

  test("a claim log entry with no recorded reason still renders") {
    // Claims that ended before the outcome column existed.
    val old = claim("7").copy(startsAt = Some(now.minusHours(1)), endedAt = Some(now), outcome = None)
    RespawnEmbeds.claimHistoryEmbed(cultOrcs, List(old)).getDescription should include("ended")
  }

  test("an empty claim log says so rather than rendering blank") {
    val embed = RespawnEmbeds.claimHistoryEmbed(cultOrcs, Nil)
    embed.getTitle should include("415 — Cult Orcs")
    embed.getDescription should include("No finished claims")
  }

  // --- pressing Schedule on a respawn you have already booked ---------------

  private def booking(userId: String = "99", minutes: Int = 120,
                      days: Int = RespawnSchedule.EveryDay) =
    RespawnSchedule(5L, 1L, userId, "someone", "", now.plusHours(2), RespawnSchedule.Daily,
      minutes, active = true, createdAt = now, daysOfWeek = days)

  private def reserved(userId: String, at: ZonedDateTime, minutes: Int = 120,
                       requester: Option[String] = None) =
    claim(userId, minutes, RespawnClaim.StatusReserved).copy(
      startsAt = Some(at), endsAt = Some(at.plusMinutes(minutes.toLong)),
      requesterUserId = requester)

  test("the booking panel shows the whole evening, not just your own slot") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, booking(),
      List(reserved("99", now.plusHours(2)), reserved("77", now.plusHours(4))),
      holder = None, now, image(cultOrcs))

    embed.getTitle shouldBe "415 — Cult Orcs"
    embed.getDescription should include("Free right now")
    embed.getDescription should include("every day")
    val booked = fields(embed)("Booked")
    // Yours is marked rather than filtered out, so the order still reads as the
    // evening it is.
    booked should include("➤")
    booked should include("<@99>")
    booked should include("<@77>")
    booked.linesIterator.count(_.startsWith("➤")) shouldBe 1
  }

  test("the booking panel says who is on the respawn now, which is a different question") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, booking(),
      List(reserved("99", now.plusHours(2))), Some(claim("55")), now, image(cultOrcs))
    embed.getDescription should include("<@55>")
    embed.getDescription should include("Your booking is every day")
  }

  test("a slot somebody is waiting on an answer for says so") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, booking(),
      List(reserved("99", now.plusHours(2)), reserved("77", now.plusHours(4), requester = Some("12"))),
      holder = None, now, image(cultOrcs))
    fields(embed)("Booked") should include("asked")
  }

  test("being asked for a slot reads differently from being asked about a booking over it") {
    val slot = reserved("99", now.plusHours(2))
    val deadline = now.plusMinutes(60)

    val pressed = RespawnEmbeds.slotRequest(cultOrcs, slot, deadline, None)
    pressed should include("would like")

    // The times have to add up: what they want is their own window, which merely
    // runs across this slot.
    val booked = RespawnEmbeds.slotRequest(cultOrcs, slot, deadline, Some((now.plusHours(1), 180)))
    booked should include("wants to book")
    booked should include("3h")
    booked should include("runs over your slot")
  }

  test("the asker is a mention, so it reads as their name in a DM they aren't in") {
    val slot = reserved("99", now.plusHours(2), requester = Some("42"))
    List(None, Some((now.plusHours(1), 180))).foreach { wanted =>
      val text = RespawnEmbeds.slotRequest(cultOrcs, slot, now.plusMinutes(60), wanted)
      text should include("<@42>")
      text should include("the slot goes to them")
    }
  }

  test("a request with no asker recorded still reads as a sentence") {
    val text = RespawnEmbeds.slotRequest(cultOrcs, reserved("99", now.plusHours(2)),
      now.plusMinutes(60), None)
    text should include("Someone")
    text should not include "<@"
  }

  test("the slot reminder says when and how long, and stops there") {
    val text = RespawnEmbeds.slotReminder(cultOrcs, reserved("99", now.plusMinutes(6)))
    text should include("415 — Cult Orcs")
    text should include("2h")
    text should include(":R>")
    // No instructions to act on: it claims itself, and the one useful action was
    // buried at the end of a sentence about doing nothing.
    text should not include "cancel it"
  }

  test("a refused request says when the slot they wanted runs to") {
    val slot = reserved("99", now.plusHours(2), requester = Some("42"))
      .copy(requestedStartsAt = Some(now.plusHours(2)), requestedDurationMinutes = Some(60))
    val text = RespawnEmbeds.slotRequestDeclined(cultOrcs, slot)

    text should include("has confirmed they are hunting")
    // Both ends: when a slot you can't have frees up is the useful part.
    text should include(s"<t:${now.plusHours(2).toInstant.getEpochSecond}:f>")
    text should include(s"until <t:${now.plusHours(4).toInstant.getEpochSecond}:f>")
    text should include("Your booking wasn't made")
    // Queueing is not the answer to a hunt that hasn't started yet.
    text should not include "queue"
  }

  test("a Request-button refusal says nothing about a booking, having made none") {
    val text = RespawnEmbeds.slotRequestDeclined(cultOrcs, reserved("99", now.plusHours(2)))
    text should include("stays theirs")
    text should not include "booking wasn't made"
  }

  test("a booking that folds into a hunt already running says what changed") {
    val text = RespawnEmbeds.slotMerged(cultOrcs, now.plusHours(2))
    text should include("already on")
    text should include("runs until")
    text should include(s"<t:${now.plusHours(2).toInstant.getEpochSecond}:t>")
  }

  test("a granted slot is the window that was asked for, not the one given up") {
    val granted = RespawnEmbeds.slotRequestGranted(cultOrcs, now.plusHours(1), 180)
    granted should include("3h")
    granted should include("no need to claim it")

    // And when it no longer fits, that is said plainly rather than silently
    // dropped — the time really is free, it just isn't theirs.
    val blocked = RespawnEmbeds.slotRequestBlocked(cultOrcs, now.plusHours(1), 180)
    blocked should include("given up")
    blocked should include("hasn't been booked for you")
  }
}
