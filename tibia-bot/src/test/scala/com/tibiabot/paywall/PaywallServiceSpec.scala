package com.tibiabot.paywall

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.domain.{PatreonGrace, PatreonMember, PatreonSeat}
import com.tibiabot.persistence.{PatreonGraceRepository, PatreonMemberRepository, PatreonSeatOverrideRepository, PatreonSeatRepository}
import net.dv8tion.jda.api.entities.{Guild, User}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class PaywallServiceSpec extends AnyFunSuite with Matchers {

  private class FakeGateway extends DiscordGateway {
    def guildById(id: String): Guild = null
    def guilds: List[Guild] = Nil
    def retrieveUser(id: String): User = null
    def selfUserId: String = "self"
    def selfUserName: String = "ViolentBot"
    def selfUserAvatarUrl: String = "https://example.com/avatar.png"
    def applicationOwnerId: String = "owner"
    def setWatchingActivity(text: String): Unit = ()
  }

  private class FakeSeatRepository(existingSeats: Int = 0) extends PatreonSeatRepository {
    private val fakeSeat = PatreonSeat("user-1", "User One", "guild-x", "World", ZonedDateTime.now())
    def seatsForUser(userId: String): List[PatreonSeat] = List.fill(existingSeats)(fakeSeat)
    def seatFor(guildId: String, world: String): Option[PatreonSeat] = None
    def assignSeat(userId: String, userName: String, guildId: String, world: String, created: ZonedDateTime): Unit = ()
    def releaseSeat(guildId: String, world: String): Unit = ()
    def releaseAllSeatsForUser(userId: String): Unit = ()
    def allSeats(): List[PatreonSeat] = Nil
  }

  private class FakeSeatOverrideRepository(initial: Map[String, Int] = Map.empty) extends PatreonSeatOverrideRepository {
    private var overrides = initial
    def extraSeatsFor(userId: String): Int = overrides.getOrElse(userId, 0)
    def setExtraSeats(userId: String, extraSeats: Int, updated: ZonedDateTime): Unit = overrides += userId -> extraSeats
    def allExtraSeats(): Map[String, Int] = overrides
  }

  private class FakeGraceRepository(initial: List[PatreonGrace] = Nil) extends PatreonGraceRepository {
    private var timers = initial.map(g => (g.guildId, g.world) -> g).toMap
    def beginGrace(guildId: String, world: String, started: ZonedDateTime): Unit =
      if (!timers.contains((guildId, world))) timers += (guildId, world) -> PatreonGrace(guildId, world, started, notified = false)
    def markNotified(guildId: String, world: String): Unit =
      timers.get((guildId, world)).foreach(g => timers += (guildId, world) -> g.copy(notified = true))
    def clearGrace(guildId: String, world: String): Unit = timers -= ((guildId, world))
    def allGrace(): List[PatreonGrace] = timers.values.toList
  }

  /** Stands in for the synced Patreon campaign snapshot — `activePatrons` are
   *  the Discord ids Patreon reports as active_patron. */
  private class FakeMemberRepository(activePatrons: Set[String] = Set.empty, failing: Boolean = false) extends PatreonMemberRepository {
    def replaceSnapshot(members: List[PatreonMember], syncedAt: ZonedDateTime): Unit = ()
    def snapshot(): List[PatreonMember] = Nil
    def isActivePatron(discordUserId: String): Boolean =
      if (failing) throw new RuntimeException("database unreachable") else activePatrons.contains(discordUserId)
  }

  private val now = ZonedDateTime.parse("2026-08-01T12:00:00Z")

  private def service(
    seatLimit: Int = 3,
    ownerId: String = "owner-id",
    overrides: Map[String, Int] = Map.empty,
    existingSeats: Int = 0,
    graceDays: Int = 7,
    grace: FakeGraceRepository = new FakeGraceRepository(),
    activePatrons: Set[String] = Set.empty,
    memberLookupFails: Boolean = false
  ) =
    new PaywallService(
      new FakeGateway, new FakeSeatRepository(existingSeats), new FakeSeatOverrideRepository(overrides), grace,
      new FakeMemberRepository(activePatrons, memberLookupFails), "support-guild", seatLimit, graceDays, ownerId
    )

  test("isActive defaults true for a (guild, world) pair that's never been checked") {
    service().isActive("unknown-guild", "Antica") shouldBe true
  }

  test("callerIsSubscribed passes an account Patreon reports as an active patron") {
    val svc = service(activePatrons = Set("user-1"))
    svc.callerIsSubscribed("user-1") shouldBe true
  }

  test("callerIsSubscribed fails an account Patreon doesn't report as an active patron") {
    // Covers all of: never subscribed, subscription lapsed or declined, and
    // subscribed but Discord not connected on Patreon's side — the snapshot
    // simply has no active row for this id in every one of those cases.
    val svc = service(activePatrons = Set("someone-else"))
    svc.callerIsSubscribed("user-1") shouldBe false
  }

  test("callerIsSubscribed does not consult the support guild at all") {
    // FakeGateway.guildById always returns null; a Patreon-backed pass has to
    // survive that, since the support guild no longer gates anything.
    service(activePatrons = Set("user-1")).callerIsSubscribed("user-1") shouldBe true
  }

  test("callerIsSubscribed: a failed snapshot lookup reads as not subscribed rather than throwing") {
    // Which starts the grace period rather than cutting anyone off — see
    // applyRefresh; a database blip costs headroom, not anyone's tracking.
    val svc = service(memberLookupFails = true)
    svc.callerIsSubscribed("user-1") shouldBe false
  }

  test("callerIsSubscribed always passes for the configured owner, bypassing the Patreon check entirely") {
    val svc = service(ownerId = "owner-id", memberLookupFails = true)
    svc.callerIsSubscribed("owner-id") shouldBe true
  }

  test("callerIsSubscribed: a positive seat adjustment bypasses the Patreon check entirely") {
    // memberLookupFails would fail this for anyone else (see above) — the
    // positive override short-circuits before Patreon is ever consulted.
    val svc = service(ownerId = "owner-id", overrides = Map("user-1" -> 1), memberLookupFails = true)
    svc.callerIsSubscribed("user-1") shouldBe true
  }

  test("callerIsSubscribed: a zero or negative seat adjustment does not bypass the Patreon check") {
    val svc = service(ownerId = "owner-id", overrides = Map("user-1" -> 0, "user-2" -> -1))
    svc.callerIsSubscribed("user-1") shouldBe false
    svc.callerIsSubscribed("user-2") shouldBe false
  }

  test("findUserIdByUsername: an unreachable support guild resolves to None") {
    // FakeGateway.guildById always returns null, same as the
    // support-guild-unreachable path callerIsSubscribed already covers.
    service().findUserIdByUsername("someone") shouldBe None
  }

  test("canAssignSeatPure: under the limit with no existing owner is allowed") {
    service(seatLimit = 3).canAssignSeatPure(None, 2, "user-1", 3) shouldBe true
  }

  test("canAssignSeatPure: at the limit with no existing owner is blocked") {
    service(seatLimit = 3).canAssignSeatPure(None, 3, "user-1", 3) shouldBe false
  }

  test("canAssignSeatPure: re-claiming your own existing seat is always allowed, even at the limit") {
    service(seatLimit = 3).canAssignSeatPure(Some("user-1"), 3, "user-1", 3) shouldBe true
  }

  test("canAssignSeatPure: another user's existing seat blocks you even under the limit") {
    service(seatLimit = 3).canAssignSeatPure(Some("someone-else"), 0, "user-1", 3) shouldBe false
  }

  test("canReassignSeatPure: already owning it is allowed even at the limit") {
    service(seatLimit = 3).canReassignSeatPure(newUserAlreadyOwnsIt = true, newUserSeatCount = 3, effectiveLimit = 3) shouldBe true
  }

  test("canReassignSeatPure: under the limit with no prior relation to the seat is allowed") {
    service(seatLimit = 3).canReassignSeatPure(newUserAlreadyOwnsIt = false, newUserSeatCount = 2, effectiveLimit = 3) shouldBe true
  }

  test("canReassignSeatPure: at the limit and not the owner is blocked") {
    service(seatLimit = 3).canReassignSeatPure(newUserAlreadyOwnsIt = false, newUserSeatCount = 3, effectiveLimit = 3) shouldBe false
  }

  test("effectiveSeatLimit: with no override, equals the flat global default") {
    service(seatLimit = 5).effectiveSeatLimit("user-1") shouldBe 5
  }

  test("effectiveSeatLimit: a positive override adds on top of the global default") {
    service(seatLimit = 5, overrides = Map("user-1" -> 2)).effectiveSeatLimit("user-1") shouldBe 7
  }

  test("effectiveSeatLimit: a negative override subtracts from the global default") {
    service(seatLimit = 5, overrides = Map("user-1" -> -2)).effectiveSeatLimit("user-1") shouldBe 3
  }

  test("effectiveSeatLimit: a negative override larger than the default floors at 0, never negative") {
    service(seatLimit = 5, overrides = Map("user-1" -> -10)).effectiveSeatLimit("user-1") shouldBe 0
  }

  test("effectiveSeatLimit: an override only applies to the user it was granted to") {
    val svc = service(seatLimit = 5, overrides = Map("user-1" -> 3))
    svc.effectiveSeatLimit("user-1") shouldBe 8
    svc.effectiveSeatLimit("user-2") shouldBe 5
  }

  test("canAssignSeat: at the global default with no override, a new seat is blocked") {
    val svc = service(seatLimit = 1, existingSeats = 1)
    svc.canAssignSeat("user-1", "guild-1", "Antica") shouldBe false
  }

  test("canAssignSeat: a user with a granted extra seat can go past the global default") {
    val svc = service(seatLimit = 1, existingSeats = 1, overrides = Map("user-1" -> 1))
    svc.canAssignSeat("user-1", "guild-1", "Antica") shouldBe true
  }

  test("reclaimOverridesFromPatreon: a positive override is cleared for a now-linked account, and reported") {
    val svc = service(overrides = Map("user-1" -> 3))
    svc.reclaimOverridesFromPatreon(List("user-1")) shouldBe Set("user-1")
    svc.effectiveSeatLimit("user-1") shouldBe service().effectiveSeatLimit("user-1") // back to the flat default
  }

  test("reclaimOverridesFromPatreon: a zero or negative override is left alone, not reported") {
    val svc = service(overrides = Map("user-1" -> 0, "user-2" -> -2))
    svc.reclaimOverridesFromPatreon(List("user-1", "user-2")) shouldBe empty
  }

  test("reclaimOverridesFromPatreon: an id with no override at all is left alone, not reported") {
    val svc = service()
    svc.reclaimOverridesFromPatreon(List("someone-else")) shouldBe empty
  }

  test("reclaimOverridesFromPatreon: only clears overrides for ids actually passed in") {
    val svc = service(overrides = Map("user-1" -> 3, "user-2" -> 2))
    svc.reclaimOverridesFromPatreon(List("user-1")) shouldBe Set("user-1")
    svc.allExtraSeats() shouldBe Map("user-1" -> 0, "user-2" -> 2)
  }

  test("a (guild, world) pair whose owner passes the check stays active and starts no timer") {
    val grace = new FakeGraceRepository()
    val svc = service(grace = grace)
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => true, now) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe true
    grace.allGrace() shouldBe empty
  }

  test("a (guild, world) pair whose owner fails the check keeps running, and only starts the clock") {
    val grace = new FakeGraceRepository()
    val svc = service(grace = grace)
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe true
    grace.allGrace() shouldBe List(PatreonGrace("guild-1", "Antica", now, notified = false))
  }

  test("a world with no seat at all is treated exactly like a lapsed one — grace, then pause") {
    val grace = new FakeGraceRepository()
    val svc = service(grace = grace)
    svc.applyRefresh(List(("guild-1", "Antica", None)), _ => true, now) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe true
    svc.applyRefresh(List(("guild-1", "Antica", None)), _ => true, now.plusDays(7)) shouldBe List(("guild-1", "Antica"))
    svc.isActive("guild-1", "Antica") shouldBe false
  }

  test("the pause lands once the grace period is up, and is reported then") {
    val grace = new FakeGraceRepository(List(PatreonGrace("guild-1", "Antica", now, notified = false)))
    val svc = service(grace = grace)
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(7).minusMinutes(1)) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe true
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(7)) shouldBe List(("guild-1", "Antica"))
    svc.isActive("guild-1", "Antica") shouldBe false
  }

  test("later sweeps never push the deadline forward — the clock runs from the first sweep that noticed") {
    val grace = new FakeGraceRepository()
    val svc = service(grace = grace)
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now) shouldBe empty
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(5)) shouldBe empty
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(7)) shouldBe List(("guild-1", "Antica"))
  }

  test("an already-paused pair is not reported again on later sweeps") {
    val svc = service()
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now) shouldBe empty
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(7)) shouldBe List(("guild-1", "Antica"))
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(8)) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe false
  }

  test("an already-notified pause is not re-announced after a restart, when nothing is active-status cached") {
    val grace = new FakeGraceRepository(List(PatreonGrace("guild-1", "Antica", now, notified = true)))
    val svc = service(grace = grace)
    svc.isActive("guild-1", "Antica") shouldBe true // fail-open, nothing swept yet
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(30)) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe false
  }

  test("sorting the subscription out mid-grace stops the clock and leaves no trace") {
    val grace = new FakeGraceRepository()
    val svc = service(grace = grace)
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now) shouldBe empty
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => true, now.plusDays(3)) shouldBe empty
    grace.allGrace() shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe true
  }

  test("a pair that recovers after being paused becomes active again and gets a fresh window on a later lapse") {
    val grace = new FakeGraceRepository()
    val svc = service(grace = grace)
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now) shouldBe empty
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(7)) shouldBe List(("guild-1", "Antica"))
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => true, now.plusDays(8)) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe true
    // lapses again: a full new grace period, and its own notice
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(9)) shouldBe empty
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now.plusDays(16)) shouldBe List(("guild-1", "Antica"))
  }

  test("a zero-day grace period pauses on the first sweep that notices") {
    val svc = service(graceDays = 0)
    svc.applyRefresh(List(("guild-1", "Antica", Some("user-1"))), _ => false, now) shouldBe List(("guild-1", "Antica"))
    svc.isActive("guild-1", "Antica") shouldBe false
  }

  test("different (guild, world) pairs are evaluated independently, even in the same guild") {
    val grace = new FakeGraceRepository(List(PatreonGrace("guild-1", "Secura", now.minusDays(30), notified = false)))
    val svc = service(grace = grace)
    val checker: String => Boolean = {
      case "good-user" => true
      case _ => false
    }
    svc.applyRefresh(List(("guild-1", "Antica", Some("good-user")), ("guild-1", "Secura", Some("bad-user"))), checker, now) shouldBe List(("guild-1", "Secura"))
    svc.isActive("guild-1", "Antica") shouldBe true
    svc.isActive("guild-1", "Secura") shouldBe false
  }
}
