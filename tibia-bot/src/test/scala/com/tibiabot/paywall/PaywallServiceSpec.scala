package com.tibiabot.paywall

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.domain.PatreonSeat
import com.tibiabot.persistence.PatreonSeatRepository
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
    def applicationOwnerId: String = "owner"
    def setWatchingActivity(text: String): Unit = ()
  }

  private class FakeSeatRepository extends PatreonSeatRepository {
    def seatsForUser(userId: String): List[PatreonSeat] = Nil
    def seatFor(guildId: String, world: String): Option[PatreonSeat] = None
    def assignSeat(userId: String, guildId: String, world: String, created: ZonedDateTime): Unit = ()
    def releaseSeat(guildId: String, world: String): Unit = ()
    def allSeats(): List[PatreonSeat] = Nil
  }

  private def service(seatLimit: Int = 3) =
    new PaywallService(new FakeGateway, new FakeSeatRepository, "support-guild", "patreon-role", seatLimit)

  test("isActive defaults true for a (guild, world) pair that's never been checked") {
    service().isActive("unknown-guild", "Antica") shouldBe true
  }

  test("hasPatreonRole matches on the configured role id") {
    val svc = service()
    svc.hasPatreonRole(List("other-role", "patreon-role")) shouldBe true
    svc.hasPatreonRole(List("other-role")) shouldBe false
  }

  test("canAssignSeatPure: under the limit with no existing owner is allowed") {
    service(seatLimit = 3).canAssignSeatPure(None, 2, "user-1") shouldBe true
  }

  test("canAssignSeatPure: at the limit with no existing owner is blocked") {
    service(seatLimit = 3).canAssignSeatPure(None, 3, "user-1") shouldBe false
  }

  test("canAssignSeatPure: re-claiming your own existing seat is always allowed, even at the limit") {
    service(seatLimit = 3).canAssignSeatPure(Some("user-1"), 3, "user-1") shouldBe true
  }

  test("canAssignSeatPure: another user's existing seat blocks you even under the limit") {
    service(seatLimit = 3).canAssignSeatPure(Some("someone-else"), 0, "user-1") shouldBe false
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
