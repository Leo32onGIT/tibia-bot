package com.tibiabot.paywall

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.domain.PatreonSeat
import com.tibiabot.persistence.{PatreonSeatOverrideRepository, PatreonSeatRepository}
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

  private def service(seatLimit: Int = 3, ownerId: String = "owner-id", overrides: Map[String, Int] = Map.empty, existingSeats: Int = 0) =
    new PaywallService(new FakeGateway, new FakeSeatRepository(existingSeats), new FakeSeatOverrideRepository(overrides), "support-guild", "patreon-role", seatLimit, ownerId)

  test("isActive defaults true for a (guild, world) pair that's never been checked") {
    service().isActive("unknown-guild", "Antica") shouldBe true
  }

  test("hasPatreonRole matches on the configured role id") {
    val svc = service()
    svc.hasPatreonRole(List("other-role", "patreon-role")) shouldBe true
    svc.hasPatreonRole(List("other-role")) shouldBe false
  }

  test("callerIsSubscribed always passes for the configured owner, bypassing the role check entirely") {
    val svc = service(ownerId = "owner-id")
    svc.callerIsSubscribed("owner-id") shouldBe true
  }

  test("callerIsSubscribed still runs the normal role check for everyone else") {
    val svc = service(ownerId = "owner-id")
    // FakeGateway.guildById always returns null, so a non-owner reads as
    // "not subscribed" (the real not-a-member-of-the-support-guild path).
    svc.callerIsSubscribed("someone-else") shouldBe false
  }

  test("callerIsSubscribed: a positive seat adjustment bypasses the role check entirely") {
    val svc = service(ownerId = "owner-id", overrides = Map("user-1" -> 1))
    // FakeGateway.guildById returning null would normally fail this for a
    // non-owner (see the previous test) - the positive override bypasses
    // that path before it's even reached.
    svc.callerIsSubscribed("user-1") shouldBe true
  }

  test("callerIsSubscribed: a zero or negative seat adjustment does not bypass the role check") {
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

  test("a (guild, world) pair whose owner fails the check becomes inactive and is reported as lapsed") {
    val svc = service()
    svc.applyRefresh(List(("guild-1", "Antica", "user-1")), _ => false) shouldBe List(("guild-1", "Antica"))
    svc.isActive("guild-1", "Antica") shouldBe false
  }

  test("a (guild, world) pair whose owner passes the check stays active") {
    val svc = service()
    svc.applyRefresh(List(("guild-1", "Antica", "user-1")), _ => true) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe true
  }

  test("a pair that was already inactive is not reported as lapsed again") {
    val svc = service()
    svc.applyRefresh(List(("guild-1", "Antica", "user-1")), _ => false) shouldBe List(("guild-1", "Antica"))
    svc.applyRefresh(List(("guild-1", "Antica", "user-1")), _ => false) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe false
  }

  test("a pair that recovers (subscription renewed) becomes active again, not reported as lapsed") {
    val svc = service()
    svc.applyRefresh(List(("guild-1", "Antica", "user-1")), _ => false) shouldBe List(("guild-1", "Antica"))
    svc.applyRefresh(List(("guild-1", "Antica", "user-1")), _ => true) shouldBe empty
    svc.isActive("guild-1", "Antica") shouldBe true
  }

  test("different (guild, world) pairs are evaluated independently, even in the same guild") {
    val svc = service()
    val checker: String => Boolean = {
      case "good-user" => true
      case _ => false
    }
    svc.applyRefresh(List(("guild-1", "Antica", "good-user"), ("guild-1", "Secura", "bad-user")), checker) shouldBe List(("guild-1", "Secura"))
    svc.isActive("guild-1", "Antica") shouldBe true
    svc.isActive("guild-1", "Secura") shouldBe false
  }
}
