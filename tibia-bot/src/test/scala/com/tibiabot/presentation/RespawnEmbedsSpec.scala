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
    id = 10L, respawnId = 1L, userId = userId, userName = s"hunter$userId", characterName = character,
    status = status, queuePosition = position, claimedAt = now, startsAt = Some(now),
    endsAt = Some(now.plusMinutes(minutes.toLong)), durationMinutes = minutes, warned = false,
    kind = RespawnClaim.KindAdHoc, limboUntil = None, offerExpiresAt = None,
    outcome = None, endedAt = None, scheduleId = None)

  // Passed explicitly rather than read from Config, which cannot initialise in
  // tests (it requires a populated environment) — the same reason UrlsSpec
  // supplies its own mappings.
  private val mappings = Map.empty[String, String]
  private val fallback = "https://example.invalid/fallback.gif"
  private val indent = "▹"
  /** The Book panel has no fields any more — every part of it is a paragraph of
   *  the description, and both lists carry a bold title of their own. */
  private val AllBookings = "**All Bookings**"
  private val YourBookings = "**Your Bookings**"

  /** The lines under `heading`, up to the blank line that ends its block.
   *
   *  By line rather than by paragraph, since a title need not begin one. */
  private def section(embed: net.dv8tion.jda.api.entities.MessageEmbed, heading: String): String =
    embed.getDescription.linesIterator.toList.dropWhile(_ != heading) match {
      case Nil => fail(s"no '$heading' line in:\n${embed.getDescription}")
      case _ :: rest => rest.takeWhile(_.nonEmpty).mkString("\n")
    }
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
    embed.getDescription should include("hunter99")
    embed.getColorRaw shouldBe RespawnEmbeds.RedColor
  }

  test("the card names the holder once, by the name people call them") {
    // The account name is a moderator's business, and the moderator panel still
    // carries it — a card read by everybody hunting here says who to go and ask.
    val held = claim("99").copy(nickname = "Beams")
    val embed = RespawnEmbeds.claimCard(cultOrcs, Some(held), Nil, Nil, settings, image(cultOrcs))
    embed.getDescription should include("**@Beams**")
    embed.getDescription should not include "hunter99"
  }

  test("a booked row on the card names its owner the same way") {
    val booking = claim("77", status = RespawnClaim.StatusReserved)
      .copy(nickname = "Beams", startsAt = Some(now.plusHours(3)))
    val booked = fields(
      RespawnEmbeds.claimCard(cultOrcs, None, Nil, List(booking), settings, image(cultOrcs)))("Booked")
    booked should include("**@Beams**")
    booked should not include "hunter77"
  }

  test("a claimant with no character is still pingable") {
    val embed = RespawnEmbeds.claimCard(cultOrcs, Some(claim("99")), Nil, Nil, settings, image(cultOrcs))
    embed.getDescription should include("hunter99")
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
    // The length is deliberately absent: it is on the card they are being offered,
    // and the question here is only whether they still want it.
    text should not include "1h 30m"
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
    // The reminder carries no buttons any more, so nothing may point at one.
    text should not include "leave button"
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
    shown should include("hunter2")
    shown should include("hunter3")
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

  test("a card lists ten booked slots before it starts summarising") {
    val slots = (1 to 14).toList.map(n =>
      claim(s"user$n", 60, RespawnClaim.StatusReserved).copy(startsAt = Some(now.plusHours(n.toLong))))
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, slots, settings, image(cultOrcs)))("Booked")

    booked.linesIterator.count(_.contains("hunteruser")) shouldBe 10
    // Same marker the Book panel uses, so the two lists read as one system.
    booked.linesIterator.filter(_.contains("hunteruser")).foreach(_ should startWith("▹"))
    booked should include("and 4 more")
    booked.length should be <= 1024
  }

  // A booking's slot row is only written once its start comes within the
  // look-ahead, so a booking made for later in the week has nothing but the rule
  // behind it for days. The card has to show it anyway: booking from the
  // dashboard's week grid and finding the thread unchanged reads as the booking
  // having failed.
  test("a booking with no slot row yet is still on the card") {
    val thursday = RespawnSchedule(7L, 1L, "99", "hunter99", "Galarzaa", now.plusDays(4),
      RespawnSchedule.Daily, 90, active = true, createdAt = now, daysOfWeek = RespawnSchedule.OneOff)
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings,
      image(cultOrcs), List(thursday), now))("Booked")

    booked should include("Galarzaa")
    booked should include(s"<t:${now.plusDays(4).toInstant.getEpochSecond}:s>")
    booked should include("1h 30m")
  }

  test("a repeating booking with no row yet shows its next evening, once, and says it repeats") {
    val weekly = RespawnSchedule(8L, 1L, "99", "hunter99", "", now.plusDays(3),
      RespawnSchedule.Daily, 60, active = true, createdAt = now,
      daysOfWeek = RespawnSchedule.maskOf(List(now.plusDays(3).getDayOfWeek)))
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings,
      image(cultOrcs), List(weekly), now))("Booked")

    // One line, not every occurrence from here to eternity.
    booked.linesIterator.count(_.contains("hunter99")) shouldBe 1
    booked should include(weekly.repeatLabel)
  }

  // A recurring rule was already one row. This is the same booking made by hand
  // — seven separate one-off rules for the same hour on seven days — which is
  // the shape a card actually fills up with, since nothing stops somebody
  // booking a week an evening at a time.
  test("one person's identical bookings are one row, counted") {
    val evenings = (2 to 8).toList.map(day =>
      RespawnSchedule(20L + day, 1L, "77", "hunter77", "", now.plusDays(day.toLong),
        RespawnSchedule.Daily, 150, active = true, createdAt = now,
        daysOfWeek = RespawnSchedule.OneOff))
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings,
      image(cultOrcs), evenings, now))("Booked")

    booked.linesIterator.count(_.contains("hunter77")) shouldBe 1
    // The soonest of them, since that is the one anybody is planning around.
    booked should include(s"<t:${now.plusDays(2).toInstant.getEpochSecond}:s>")
    booked should include("+6 repeats")
    // Nothing left over for the row cap's note to own up to.
    booked should not include "more"
  }

  test("a single extra booking is a repeat, not repeats") {
    val pair = List(3, 4).map(day =>
      RespawnSchedule(30L + day, 1L, "77", "hunter77", "", now.plusDays(day.toLong),
        RespawnSchedule.Daily, 150, active = true, createdAt = now,
        daysOfWeek = RespawnSchedule.OneOff))
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings,
      image(cultOrcs), pair, now))("Booked")

    booked should include("+1 repeat")
    booked should not include "repeats"
  }

  // Same person, different evening — two different things to somebody reading
  // the list to find an evening that is still free. Grouping by person alone
  // would fold one into the other and say the free one was taken.
  test("bookings at different hours stay their own rows") {
    val early = RespawnSchedule(41L, 1L, "77", "hunter77", "", now.plusDays(2).withHour(9),
      RespawnSchedule.Daily, 150, active = true, createdAt = now, daysOfWeek = RespawnSchedule.OneOff)
    val late = RespawnSchedule(42L, 1L, "77", "hunter77", "", now.plusDays(3).withHour(21),
      RespawnSchedule.Daily, 150, active = true, createdAt = now, daysOfWeek = RespawnSchedule.OneOff)
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings,
      image(cultOrcs), List(early, late), now))("Booked")

    booked.linesIterator.count(_.contains("hunter77")) shouldBe 2
    booked should not include "repeat"
  }

  test("two people booking the same hour are two rows") {
    val theirs = List("77", "88").map(user =>
      RespawnSchedule(50L + user.toLong, 1L, user, s"hunter$user", "", now.plusDays(2),
        RespawnSchedule.Daily, 150, active = true, createdAt = now,
        daysOfWeek = RespawnSchedule.OneOff))
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings,
      image(cultOrcs), theirs, now))("Booked")

    booked.linesIterator.size shouldBe 2
    booked should not include "repeat"
  }

  // The repeat label and the asked marker are each true of one row only, so a
  // count behind either would be claiming it of the rows it swallowed.
  test("a row with a label of its own is not folded into the plain ones") {
    val weekly = RespawnSchedule(61L, 1L, "77", "hunter77", "", now.plusDays(2),
      RespawnSchedule.Daily, 150, active = true, createdAt = now,
      daysOfWeek = RespawnSchedule.maskOf(List(now.plusDays(2).getDayOfWeek)))
    val oneOff = RespawnSchedule(62L, 1L, "77", "hunter77", "", now.plusDays(9),
      RespawnSchedule.Daily, 150, active = true, createdAt = now, daysOfWeek = RespawnSchedule.OneOff)
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, Nil, settings,
      image(cultOrcs), List(weekly, oneOff), now))("Booked")

    booked.linesIterator.size shouldBe 2
    booked should include(weekly.repeatLabel)
    // No count behind either of them.
    booked should not include "+"
  }

  test("rows and rules are one list, in time order") {
    val tonight = reserved("77", now.plusHours(3))
    val thursday = RespawnSchedule(9L, 1L, "99", "hunter99", "", now.plusDays(4),
      RespawnSchedule.Daily, 60, active = true, createdAt = now, daysOfWeek = RespawnSchedule.OneOff)
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, List(tonight), settings,
      image(cultOrcs), List(thursday), now))("Booked")

    booked.linesIterator.toList.map(line => line.contains("hunter77")) shouldBe List(true, false)
  }

  test("a long list is cut to fit the field, and owns up to it once") {
    // Ten rows of very long names would run past Discord's 1024, which is
    // rejected outright rather than trimmed — it would take the whole card edit
    // with it. One note has to cover both the row cap and the character cut,
    // or the field admits to "3 more" on one line and "5 more" on the next.
    val wordy = "A Very Long Character Name Indeed That Goes On" * 2
    val slots = (1 to 14).toList.map(n =>
      claim(s"user$n", 60, RespawnClaim.StatusReserved, character = wordy)
        .copy(startsAt = Some(now.plusHours(n.toLong))))
    val booked = fields(RespawnEmbeds.claimCard(cultOrcs, None, Nil, slots, settings, image(cultOrcs)))("Booked")

    booked.length should be <= 1024
    booked.linesIterator.count(_.contains("more")) shouldBe 1
    // The count covers everything missing, not just what the row cap dropped.
    val shown = booked.linesIterator.count(_.contains("hunteruser"))
    booked should include(s"and ${14 - shown} more")
  }

  test("the stamina bar never disagrees with the number beside it") {
    // Full and empty are the two the eye checks against the words, so they are
    // exact; everything between is proportional.
    RespawnEmbeds.staminaBar(240, 240, 12) shouldBe "█" * 12
    RespawnEmbeds.staminaBar(0, 240, 12) shouldBe "░" * 12
    RespawnEmbeds.staminaBar(120, 240, 12) shouldBe "█" * 6 + "░" * 6

    // A tank with minutes left must not read as empty, nor an all-but-full one
    // as full — that is the picture contradicting the caption.
    RespawnEmbeds.staminaBar(1, 240, 12) shouldBe "█" + "░" * 11
    RespawnEmbeds.staminaBar(239, 240, 12) shouldBe "█" * 11 + "░"

    // Unlimited has nothing to draw.
    RespawnEmbeds.staminaBar(0, 0, 12) shouldBe ""
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
      "Daily stamina", "Handover window")
    fs("Default claim") shouldBe "2h"
    fs("Queue limit") shouldBe "20"
  }

  test("the panel does not show the reminder default, which is no longer a guild setting") {
    // It survives as the fallback for members who have not set their own, but
    // there is nowhere left to change it — and a panel of settings is a poor
    // place to print a number that cannot be edited from it.
    fields(RespawnEmbeds.serverSettingsEmbed(settings)).keys should not contain "Default reminder"
  }

  test("the server settings panel spells out the disabled cases rather than showing 0") {
    val fs = fields(RespawnEmbeds.serverSettingsEmbed(settings.copy(staminaMinutes = 0)))
    fs("Daily stamina") shouldBe "unlimited"
  }

  test("the spawn moderator panel names the holder and what force leave will do") {
    val embed = RespawnEmbeds.spawnModeratorPanel(cultOrcs, Some(claim("99", character = "Galarzaa")), 2)
    embed.getDescription should include("Galarzaa")
    embed.getDescription should include("hunter99")
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

  test("the bookings list reads as a timetable, soonest first") {
    val evening = RespawnSchedule(1L, 1L, "99", "hunter99", "", now.plusHours(9),
      RespawnSchedule.Daily, 120, active = true, createdAt = now)
    val morning = evening.copy(id = 2L, anchorAt = now.plusHours(2), durationMinutes = 60,
      daysOfWeek = RespawnSchedule.OneOff)
    val embed = RespawnEmbeds.schedulesEmbed(List(evening -> cultOrcs, morning -> unmapped), now)

    val text = embed.getDescription
    // Sorted by when, not by the order they were handed over.
    text.indexOf(unmapped.code) should be < text.indexOf(cultOrcs.code)
    // Start and end both shown, so the length needs no arithmetic.
    text should include(s"<t:${now.plusHours(9).toInstant.getEpochSecond}:t>")
    text should include(s"<t:${now.plusHours(11).toInstant.getEpochSecond}:t>")
    // A repeat says which days; a one-off carries its date instead.
    text should include("every day")
    text should include(s"<t:${now.plusHours(2).toInstant.getEpochSecond}:s>")
    // "next" was noise — the time is obviously the next one.
    text should not include "next"
  }

  test("a moderator's booking list names the owner of each line") {
    val schedule = RespawnSchedule(1L, 1L, "77", "hunter77", "", now.plusHours(2),
      RespawnSchedule.Daily, 120, active = true, createdAt = now)
    val embed = RespawnEmbeds.schedulesEmbed(List(schedule -> cultOrcs), now, everyones = true)
    embed.getDescription should include("hunter77")
  }

  test("an empty bookings list points at the button that makes one") {
    val embed = RespawnEmbeds.schedulesEmbed(Nil, now)
    embed.getDescription should include("**Book**")
  }

  test("a hunt taken away names who has it now") {
    // The refund itself is unchanged — the notice simply no longer spells it
    // out, so there is nothing about stamina to assert here.
    val text = RespawnEmbeds.claimReassignedFrom(cultOrcs, "hunter42")
    text should include("hunter42")
    text should not include "<@"
    text should include(cultOrcs.displayName)
    RespawnEmbeds.claimReassignedTo(cultOrcs, claim("42")) should include(":R>")
  }

  // --- pressing Schedule on a respawn you have already booked ---------------

  private def booking(userId: String = "99", minutes: Int = 120,
                      days: Int = RespawnSchedule.EveryDay) =
    RespawnSchedule(5L, 1L, userId, s"hunter$userId", "", now.plusHours(2), RespawnSchedule.Daily,
      minutes, active = true, createdAt = now, daysOfWeek = days)

  private def reserved(userId: String, at: ZonedDateTime, minutes: Int = 120,
                       requester: Option[String] = None, requesterNickname: Option[String] = None) =
    claim(userId, minutes, RespawnClaim.StatusReserved).copy(
      startsAt = Some(at), endsAt = Some(at.plusMinutes(minutes.toLong)),
      requesterUserId = requester,
      // Named the same way the claimant is, since that is what gets rendered.
      requesterUserName = requester.map(id => s"hunter$id"),
      requesterNickname = requesterNickname)

  test("the booking panel shows the whole evening, not just your own slot") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, List(booking()), "99",
      List(reserved("99", now.plusHours(2)), reserved("77", now.plusHours(4))),
      holder = None, now, image(cultOrcs))

    embed.getTitle shouldBe "415 — Cult Orcs"
    embed.getDescription should include("**Free** right now")
    embed.getDescription should include("every day")
    val booked = section(embed, AllBookings)
    // Yours is marked rather than filtered out, so the order still reads as the
    // evening it is.
    booked should include("▸")
    booked should include("hunter99")
    booked should include("hunter77")
    booked.linesIterator.count(_.startsWith("▸")) shouldBe 1
    // Somebody else's row is indented rather than left bare, so the two kinds of
    // line begin at the same place.
    booked.linesIterator.count(_.startsWith(indent)) shouldBe 1
  }

  test("the booking panel names people the way the card does — once") {
    // The same rows in another surface, read by the same people, so the two say
    // a name the same way. The moderator panel is the one that keeps the account.
    val holder = claim("55").copy(nickname = "Beams")
    val theirs = reserved("77", now.plusHours(4)).copy(nickname = "Rook")
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "99", List(theirs),
      holder = Some(holder), now, image(cultOrcs))

    embed.getDescription should include("**@Beams**")
    embed.getDescription should not include "hunter55"
    val booked = section(embed, AllBookings)
    booked should include("**@Rook**")
    booked should not include "hunter77"
  }

  test("the panel a moderator sees is the same panel, minus any bookings of their own") {
    // Same builder, same shape — only the buttons under it differ, which is the
    // only part that actually differs.
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55",
      List(reserved("99", now.plusHours(2)), reserved("77", now.plusHours(4))),
      holder = None, now, image(cultOrcs))

    embed.getTitle shouldBe "415 — Cult Orcs"
    // No section at all where they have booked nothing — see the test below.
    embed.getDescription should not include YourBookings
    // Everyone else's bookings are still listed, and none is marked as theirs.
    val booked = section(embed, AllBookings)
    booked should include("hunter99")
    booked should include("hunter77")
    booked should not include "▸"
    // Every line starts with a marker, so the times share a left edge.
    booked.linesIterator.foreach(_ should startWith(indent))
  }

  test("having booked nothing here is said by there being no section, not by a sentence") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "99",
      List(reserved("77", now.plusHours(4))), holder = None, now, image(cultOrcs))

    embed.getDescription should not include YourBookings
    embed.getDescription should not include "nothing booked"
    // The panel is still the two things it was opened for: the state, and what
    // is already spoken for.
    embed.getDescription should startWith("###")
    section(embed, AllBookings) should include("hunter77")
    // One break, between the state and the list — no leftover blank where the
    // section used to be.
    embed.getDescription.linesIterator.count(_.isEmpty) shouldBe 1
  }

  test("a booking that has been and gone still says so, having been made") {
    // The absence that stayed: they booked this spawn and are looking for the
    // row, which is a different question from never having booked.
    val past = booking(days = RespawnSchedule.OneOff).copy(anchorAt = now.minusDays(2))
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, List(past), "99", Nil,
      holder = None, now, image(cultOrcs))

    section(embed, YourBookings) shouldBe "> Your booking on this respawn has been and gone."
  }

  test("the booking panel says who is on the respawn now, which is a different question") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, List(booking()), "99",
      List(reserved("99", now.plusHours(2))), Some(claim("55")), now, image(cultOrcs))
    embed.getDescription should include("hunter55")
    // Marked like a row: one booking is still a list of one.
    section(embed, YourBookings) shouldBe
      s"▸ Your booking is **every day** at <t:${now.plusHours(2).toInstant.getEpochSecond}:t> for **2h**."
    // The state leads behind its glyph, one line, with the reader's own titled
    // underneath it.
    embed.getDescription should startWith("🔴 Being hunted by ")
    embed.getDescription should include("\n**Your Bookings**\n")
    // Nothing quoted anywhere: both lists are titled instead. By line, not by
    // substring — every timestamp ends in "> " and would match one.
    embed.getDescription.linesIterator.foreach(_ should not startWith "> ")
  }

  test("a free spawn is marked as free, in the glyph as well as the word") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55", Nil,
      holder = None, now, image(cultOrcs))
    embed.getDescription should startWith("### 🟢 **Free** right now.")
  }

  test("the state glyph is the guild's own yes/no pair, not a circle") {
    // Custom emoji in Discord's `<:name:id>` form, which is what Config holds —
    // so the panel answers with the same two marks every other reply from this
    // bot does, rather than a pair of coloured circles nothing else uses.
    val yes = "<:yes:1135988738565099550>"
    val no = "<:no:1135988651327766648>"
    val free = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55", Nil,
      holder = None, now, image(cultOrcs), yesEmoji = yes, noEmoji = no)
    val taken = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55", Nil,
      Some(claim("55")), now, image(cultOrcs), yesEmoji = yes, noEmoji = no)

    free.getDescription should startWith(s"### $yes **Free**")
    // Held is one line and no heading — see bookingPanel for why only one of the
    // two states is short enough to be one.
    taken.getDescription should startWith(s"$no Being hunted by ")
    taken.getDescription should not startWith "###"
    taken.getDescription.linesIterator.next() should include("hunter55")
    // And no circle left behind either way.
    free.getDescription should not include "🟢"
    taken.getDescription should not include "🔴"
  }

  test("a slot somebody is waiting on an answer for says so") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, List(booking()), "99",
      List(reserved("99", now.plusHours(2)), reserved("77", now.plusHours(4), requester = Some("12"))),
      holder = None, now, image(cultOrcs))
    section(embed, AllBookings) should include("asked")
  }

  test("two bookings on one spawn are both described, earliest first") {
    val evening = booking(minutes = 60)
    val morning = evening.copy(id = 6L, anchorAt = now.plusHours(1))
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, List(evening, morning), "99",
      List(reserved("99", now.plusHours(1), 60), reserved("99", now.plusHours(2), 60)),
      holder = None, now, image(cultOrcs))

    // One per line, not a semicolon-joined paragraph.
    embed.getDescription should not include ";"
    // Counted inside their own section: the All Bookings rows below wear the
    // same marker, so counting the whole description would find both lists.
    val yours = section(embed, YourBookings).linesIterator.toList
    yours should have size 2
    // Rows and nothing else — the title says whose they are, so there is no
    // sentence above them saying it again.
    all(yours) should startWith("▸")
    val morningAt = s"<t:${now.plusHours(1).toInstant.getEpochSecond}:t>"
    val eveningAt = s"<t:${now.plusHours(2).toInstant.getEpochSecond}:t>"
    embed.getDescription.indexOf(morningAt) should be < embed.getDescription.indexOf(eveningAt)
  }

  test("the panel closes by saying how to add a booking, naming both ways") {
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55", Nil,
      holder = None, now, image(cultOrcs))
    val footer = Option(embed.getFooter).map(_.getText).getOrElse("")
    footer should include("doesn't overlap")
    footer should include("Book")
    footer should include("Dashboard")
    // Bare, not emphasised: a footer is plain text, so asterisks here would be
    // read as asterisks.
    footer should not include "**"
  }

  test("the booking panel lists the next ten bookings and owns up to the rest") {
    val slots = (1 to 14).map(hour => reserved(s"$hour", now.plusHours(hour.toLong), 30)).toList
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55", slots,
      holder = None, now, image(cultOrcs))

    val booked = section(embed, AllBookings)
    booked.linesIterator.count(_.startsWith(indent)) shouldBe 10
    // Soonest first, and the ten it kept are the ten soonest rather than
    // whichever ten fit.
    booked should include("hunter1")
    booked should include("hunter10")
    booked should not include "hunter11"
    booked should include("…and 4 more")
  }

  // The same fold the card does, for the same reason and more so: this list is
  // opened to find an evening that is still free, and a week of one person's
  // 7pm pushed the other evenings off the end of it.
  test("the booking panel folds one person's identical bookings too") {
    val evenings = (2 to 8).toList.map(day =>
      RespawnSchedule(70L + day, 1L, "77", "hunter77", "", now.plusDays(day.toLong),
        RespawnSchedule.Daily, 150, active = true, createdAt = now,
        daysOfWeek = RespawnSchedule.OneOff))
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55", Nil,
      holder = None, now, image(cultOrcs), upcoming = evenings)

    val booked = section(embed, AllBookings)
    booked.linesIterator.count(_.contains("hunter77")) shouldBe 1
    booked should include("+6 repeats")
    // Room the fold made back: nothing is behind a "…and N more" any more.
    booked should not include "…and"
  }

  test("a folded row is still marked as the reader's own") {
    val mine = (2 to 4).toList.map(day =>
      RespawnSchedule(80L + day, 1L, "55", "hunter55", "", now.plusDays(day.toLong),
        RespawnSchedule.Daily, 150, active = true, createdAt = now,
        daysOfWeek = RespawnSchedule.OneOff))
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55", Nil,
      holder = None, now, image(cultOrcs), upcoming = mine)

    val booked = section(embed, AllBookings)
    // The filled marker, not the hollow one every other row gets.
    booked.linesIterator.toList.filter(_.contains("hunter55")).map(_.take(1)) shouldBe List("▸")
    booked should include("+2 repeats")
  }

  test("a booking too far out to have a slot yet is still one of the next ten") {
    // Nothing written down at all: a spawn booked beyond the look-ahead has only
    // its rules, and answering "nothing booked" is what this panel is opened to
    // avoid.
    val embed = RespawnEmbeds.bookingPanel(cultOrcs, Nil, "55", Nil,
      holder = None, now, image(cultOrcs), upcoming = List(booking(userId = "77")))

    val booked = section(embed, AllBookings)
    booked should include("hunter77")
    // Said as the standing arrangement it is, not as a one-off evening.
    booked should include("every day")
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

  test("the asker is named, in a DM they aren't in and so cannot be pinged from") {
    val slot = reserved("99", now.plusHours(2), requester = Some("42"))
    List(None, Some((now.plusHours(1), 180))).foreach { wanted =>
      val text = RespawnEmbeds.slotRequest(cultOrcs, slot, now.plusMinutes(60), wanted)
      text should include("hunter42")
      text should not include "<@"
      text should include("the slot goes to them")
    }
  }

  test("the asker is named the way the server knows them, where it knows them") {
    val slot = reserved("99", now.plusHours(2), requester = Some("42"),
      requesterNickname = Some("Bald Dwarf"))
    val text = RespawnEmbeds.slotRequest(cultOrcs, slot, now.plusMinutes(60), None)

    // The account is what makes them findable; the guild name is what makes the
    // owner recognise who is asking them to give a slot up.
    text should include("hunter42")
    text should include("Bald Dwarf")
    text should not include "<@"
  }

  test("an asker with no guild name is still named by their account alone") {
    val slot = reserved("99", now.plusHours(2), requester = Some("42"))
    val text = RespawnEmbeds.slotRequest(cultOrcs, slot, now.plusMinutes(60), None)

    text should include("hunter42")
    // Not a dangling "(@)" where a name it never had would have gone.
    text should not include "(@"
  }

  test("a request with no asker recorded still reads as a sentence") {
    val text = RespawnEmbeds.slotRequest(cultOrcs, reserved("99", now.plusHours(2)),
      now.plusMinutes(60), None)
    text should include("Someone")
    text should not include "<@"
  }

  test("the slot reminder says when, how long, and what confirming buys you") {
    val text = RespawnEmbeds.slotReminder(cultOrcs, reserved("99", now.plusMinutes(6)))
    text should include("415 — Cult Orcs")
    text should include("2h")
    text should include(":R>")
    // No instructions to act on: it claims itself, and the one useful action was
    // buried at the end of a sentence about doing nothing.
    text should not include "cancel it"
    // Confirm is optional, so the reason to bother has to be on the message —
    // the button alone doesn't say what it settles.
    text should include("Confirm now")
    text should include("claim it automatically")
  }

  // --- confirming a booking ------------------------------------------------

  test("a started booking that was confirmed early just says it has started") {
    val text = RespawnEmbeds.slotStarted(cultOrcs, reserved("99", now).copy(endsAt = Some(now.plusHours(2))))
    text should include("has started")
    text should not include "Take the claim"
  }

  test("an unconfirmed start sets the deadline apart from the hunt") {
    val started = reserved("99", now).copy(endsAt = Some(now.plusHours(2)))
    val text = RespawnEmbeds.slotStartedUnconfirmed(cultOrcs, started, now.plusMinutes(15))
    text should include("415 — Cult Orcs")
    // The thing to act on is the one that expires, and it has to be findable at a
    // glance in a notification.
    text should include("**Take the claim")
    text should include(":R>")
    // And what letting it expire costs, so the deadline reads as one.
    text should include("lose the claim")
  }

  test("giving up an unconfirmed hunt says what it cost and where the hunt went") {
    val text = RespawnEmbeds.slotUnconfirmed(cultOrcs, 90)
    text should include("was given up")
    text should include("1h 30m")
    // Why it went, and to whom. The copy no longer says the standing booking
    // itself survives, which its own scaladoc still claims it does.
    text should include("didn't take the claim in time")
    text should include("whoever was next")
  }

  test("a give-up with nothing left to refund doesn't mention the tank") {
    val text = RespawnEmbeds.slotUnconfirmed(cultOrcs, 0)
    text should not include "tank"
    text should include("was given up")
  }

  test("a refused request says when the slot they wanted runs to") {
    val slot = reserved("99", now.plusHours(2), requester = Some("42"))
      .copy(requestedStartsAt = Some(now.plusHours(2)), requestedDurationMinutes = Some(60))
    val text = RespawnEmbeds.slotRequestDeclined(cultOrcs, slot)

    text should include("has confirmed they are hunting")
    // Both ends: when a slot you can't have frees up is the useful part.
    text should include(s"<t:${now.plusHours(2).toInstant.getEpochSecond}:s>")
    text should include(s"until <t:${now.plusHours(4).toInstant.getEpochSecond}:s>")
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
