package com.tibiabot.state

import com.tibiabot.domain.{Guilds, PlayerCache, Players, Worlds}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Who a world offers to the fansite budget — see
 *  [[com.tibiabot.fansiteapi.FansiteRoster]], the only reader. The selection
 *  spans four maps that are edited independently by commands and streams, so
 *  what it must get right is which discord's lists apply to which world, and
 *  which of the characters in `activityData` are there for a *hunted* guild
 *  rather than an allied one.
 */
class HuntedNamesForWorldSpec extends AnyFunSuite with Matchers {

  private val when = ZonedDateTime.parse("2026-01-01T00:00:00Z")

  private def player(name: String) = Players(name, "false", "test", "0")
  private def guildEntry(name: String) = Guilds(name, "false", "test", "0")
  private def member(name: String, guild: String) = PlayerCache(name, Nil, guild, when)

  /** A world row with only `name` meaningful — the rest is channel and display
   *  configuration this selection never reads. */
  private def worldRow(name: String) = Worlds(
    name, "", "", "", "", "", "", "", "", "", "", "", "", "",
    0, "", "", "", "", "", "", "", 0, 0, "", "", "", "")

  private def stateWith(
      worlds: Map[String, List[Worlds]],
      hunted: Map[String, List[Players]] = Map.empty,
      huntedGuilds: Map[String, List[Guilds]] = Map.empty,
      allied: Map[String, List[Guilds]] = Map.empty,
      activity: Map[String, List[PlayerCache]] = Map.empty
  ): StreamState = {
    val state = new StreamState
    state.modifyWorldsData(_ => worlds)
    state.modifyHuntedPlayersData(_ => hunted)
    state.modifyHuntedGuildsData(_ => huntedGuilds)
    state.modifyAlliedGuildsData(_ => allied)
    state.modifyActivityData(_ => activity)
    state
  }

  test("a named hunted player is offered, and only by discords tracking that world") {
    val state = stateWith(
      worlds = Map("a" -> List(worldRow("Antica")), "b" -> List(worldRow("Bona"))),
      hunted = Map("a" -> List(player("Someone")), "b" -> List(player("Elsewhere"))))

    state.huntedNamesForWorld("Antica") shouldBe Set("someone")
  }

  test("members of a hunted guild are offered too") {
    // The reason this class was touched: hunting a guild is how most servers
    // watch an enemy, and naming nobody used to mean covering nobody.
    val state = stateWith(
      worlds = Map("a" -> List(worldRow("Antica"))),
      huntedGuilds = Map("a" -> List(guildEntry("Bad Guys"))),
      activity = Map("a" -> List(member("Grunt", "Bad Guys"), member("Other", "Bad Guys"))))

    state.huntedNamesForWorld("Antica") shouldBe Set("grunt", "other")
  }

  test("members of an allied guild are not") {
    // activityData carries both sides, so taking it whole would spend the
    // budget on the characters the server likes.
    val state = stateWith(
      worlds = Map("a" -> List(worldRow("Antica"))),
      huntedGuilds = Map("a" -> List(guildEntry("Bad Guys"))),
      allied = Map("a" -> List(guildEntry("Good Guys"))),
      activity = Map("a" -> List(member("Grunt", "Bad Guys"), member("Friend", "Good Guys"))))

    state.huntedNamesForWorld("Antica") shouldBe Set("grunt")
  }

  test("a guildless character in activity is not mistaken for a member") {
    // Someone who just left a tracked guild sits here with an empty guild until
    // the row is dropped, and "" must not match anything.
    val state = stateWith(
      worlds = Map("a" -> List(worldRow("Antica"))),
      huntedGuilds = Map("a" -> List(guildEntry("Bad Guys"))),
      activity = Map("a" -> List(member("Loner", ""))))

    state.huntedNamesForWorld("Antica") shouldBe empty
  }

  test("two discords hunting the same character on one world offer it once") {
    // The union is what makes this per world rather than per discord: the same
    // character is one fetch however many servers asked for it.
    val state = stateWith(
      worlds = Map("a" -> List(worldRow("Antica")), "b" -> List(worldRow("Antica"))),
      hunted = Map("a" -> List(player("Someone")), "b" -> List(player("someone"))),
      huntedGuilds = Map("b" -> List(guildEntry("Bad Guys"))),
      activity = Map("b" -> List(member("Grunt", "bad guys"))))

    state.huntedNamesForWorld("antica") shouldBe Set("someone", "grunt")
  }

  test("a discord with a hunted guild but no activity yet offers nothing for it") {
    // Membership is learned from fetched sheets, so a guild just added is empty
    // here until a poll has noticed somebody in it.
    val state = stateWith(
      worlds = Map("a" -> List(worldRow("Antica"))),
      huntedGuilds = Map("a" -> List(guildEntry("Bad Guys"))))

    state.huntedNamesForWorld("Antica") shouldBe empty
  }
}
