package com.tibiabot.discord

import net.dv8tion.jda.api.entities.{Guild, User}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DiscordGatewaySpec extends AnyFunSuite with Matchers {

  /** Proves the port is implementable without JDA and honours the nullable
   *  guildById contract that call sites (e.g. `if (guild != null)`) rely on. */
  private class FakeGateway(known: Set[String]) extends DiscordGateway {
    def guildById(id: String): Guild = null
    def guilds: List[Guild] = Nil
    def retrieveUser(id: String): User = null
    def selfUserId: String = "self-1"
    def selfUserName: String = "ViolentBot"
    def applicationOwnerId: String = "owner-9"
    def setWatchingActivity(text: String): Unit = ()
  }

  test("guildById returns null for an unknown guild (mirrors JDA)") {
    new FakeGateway(Set.empty).guildById("nope") shouldBe null
  }

  test("identity accessors are plain strings usable without JDA") {
    val gw = new FakeGateway(Set.empty)
    gw.selfUserId shouldBe "self-1"
    gw.selfUserName shouldBe "ViolentBot"
    gw.applicationOwnerId shouldBe "owner-9"
  }
}
