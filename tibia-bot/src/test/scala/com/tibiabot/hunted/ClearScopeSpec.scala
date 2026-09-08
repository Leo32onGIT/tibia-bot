package com.tibiabot.hunted

import com.tibiabot.domain.{Guilds, PlayerCache, Players}
import com.tibiabot.state.StreamState
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** What clearing a list must and must not touch.
 *
 *  Both halves of this were wrong for a long time, in opposite directions.
 *
 *  The lists themselves were not cleared at all — the two lines that did it were
 *  dropped when BotApp's state moved behind StreamState, because in their old
 *  form they emptied *every* guild's lists and did not survive translation. So
 *  the command deleted the rows, reported success, and left the list showing
 *  everybody until a restart reloaded it from the empty tables.
 *
 *  The activity records went the other way: filtered across the whole map, so
 *  clearing one server's list dropped records in unrelated servers for anyone
 *  whose guild happened to match. Those records are the "have we seen this
 *  player" baseline, so those servers announced them all over again as joining.
 *
 *  Exercised against StreamState directly, which is where both bugs lived —
 *  the service wrapper needs a Guild and a database and would prove less.
 */
class ClearScopeSpec extends AnyFunSuite with Matchers {

  private val ours = "111"
  private val theirs = "222"

  private def player(name: String) = Players(name, "false", "none", "someone")
  private def guildEntry(name: String) = Guilds(name, "false", "none", "someone")
  private def record(name: String, guild: String) =
    PlayerCache(name, Nil, guild, ZonedDateTime.now())

  private def loaded(): StreamState = {
    val state = new StreamState
    state.modifyHuntedPlayersData(_ => Map(ours -> List(player("bubble")), theirs -> List(player("charm"))))
    state.modifyHuntedGuildsData(_ => Map(ours -> List(guildEntry("red rising")), theirs -> List(guildEntry("red rising"))))
    state.modifyActivityData(_ => Map(
      ours -> List(record("bubble", "Red Rising"), record("someoneelse", "Other Guild")),
      theirs -> List(record("charm", "Red Rising"), record("thirdparty", "Other Guild"))))
    state
  }

  /** What clearList does to the lists, isolated: this guild's entries go, and
   *  every other guild's stay. */
  private def clearLists(state: StreamState, guildId: String): Unit = {
    state.modifyHuntedGuildsData(m => m.updated(guildId, List.empty))
    state.modifyHuntedPlayersData(m => m.updated(guildId, List.empty))
  }

  /** The production filter, not a copy of it — see HuntedAlliedService's
   *  companion. Taking one guild's records is the whole point: this used to be
   *  applied across every guild at once. */
  private def clearActivity(state: StreamState, guildId: String,
                            guildNames: Set[String], playerNames: Set[String]): Unit =
    state.modifyActivityData(m =>
      m.updated(guildId,
        HuntedAlliedService.activityAfterClear(m.getOrElse(guildId, List.empty), guildNames, playerNames)))

  test("the guild's own lists are emptied") {
    val state = loaded()
    clearLists(state, ours)
    state.huntedPlayersData.getOrElse(ours, Nil) shouldBe empty
    state.huntedGuildsData.getOrElse(ours, Nil) shouldBe empty
  }

  /** The regression: this is what was missing entirely, so the list carried on
   *  showing everybody after a "successful" clear. */
  test("clearing empties the list rather than leaving it as it was") {
    val state = loaded()
    state.huntedPlayersData.getOrElse(ours, Nil) should not be empty
    clearLists(state, ours)
    state.huntedPlayersData.getOrElse(ours, Nil) shouldBe empty
  }

  test("another guild's lists are untouched") {
    val state = loaded()
    clearLists(state, ours)
    state.huntedPlayersData.getOrElse(theirs, Nil).map(_.name) shouldBe List("charm")
    state.huntedGuildsData.getOrElse(theirs, Nil).map(_.name) shouldBe List("red rising")
  }

  test("activity records for the cleared guild's members and players go") {
    val state = loaded()
    clearActivity(state, ours, Set("red rising"), Set("bubble"))
    state.activityData.getOrElse(ours, Nil).map(_.name) shouldBe List("someoneelse")
  }

  /** The other regression: another server's records are its own business, even
   *  when the guild name matches. */
  test("another guild's activity records are untouched, matching guild or not") {
    val state = loaded()
    clearActivity(state, ours, Set("red rising"), Set("bubble"))
    state.activityData.getOrElse(theirs, Nil).map(_.name) should
      contain theSameElementsAs List("charm", "thirdparty")
  }

  test("clearing a guild that has nothing leaves every other guild alone") {
    val state = loaded()
    clearLists(state, "333")
    clearActivity(state, "333", Set("red rising"), Set("bubble"))
    state.huntedPlayersData.getOrElse(ours, Nil).map(_.name) shouldBe List("bubble")
    state.activityData.getOrElse(theirs, Nil) should have size 2
  }
}
