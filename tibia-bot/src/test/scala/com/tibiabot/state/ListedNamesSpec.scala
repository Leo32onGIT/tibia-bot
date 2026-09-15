package com.tibiabot.state

import com.tibiabot.domain.Players
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/** The flattened index behind `BotApp.isOnAnyList`.
 *
 *  It is derived state rebuilt lazily after a write, so what it has to get
 *  right is invalidation: every path that edits either list has to leave the
 *  next read seeing the edit. A removal matters as much as an add — an index
 *  that only ever grows would keep writing sheets for players nobody lists any
 *  more, which is the waste the whole cache guard exists to avoid.
 */
class ListedNamesSpec extends AnyFunSuite with Matchers {

  private def player(name: String) = Players(name, "false", "test", "0")

  test("flattens hunted and allied names across every guild, lowercased") {
    val state = new StreamState
    state.modifyHuntedPlayersData(_ => Map(
      "guild-a" -> List(player("Bubble"), player("Cachero")),
      "guild-b" -> List(player("Eternal Oblivion"))))
    state.modifyAlliedPlayersData(_ => Map("guild-c" -> List(player("Mateusz Dragon"))))

    state.listedNames shouldBe Set("bubble", "cachero", "eternal oblivion", "mateusz dragon")
  }

  test("is empty when no discord lists anybody") {
    new StreamState().listedNames shouldBe empty
  }

  test("a name listed by two guilds appears once") {
    val state = new StreamState
    state.modifyHuntedPlayersData(_ => Map(
      "guild-a" -> List(player("Bubble")),
      "guild-b" -> List(player("bubble"))))

    state.listedNames shouldBe Set("bubble")
  }

  test("mixed case on the stored entry still matches a lowercased lookup") {
    val state = new StreamState
    state.modifyHuntedPlayersData(_ => Map("guild-a" -> List(player("BuBbLe"))))

    state.listedNames should contain("bubble")
  }

  test("an add after a read is visible to the next read") {
    val state = new StreamState
    state.modifyHuntedPlayersData(_ => Map("guild-a" -> List(player("Bubble"))))
    state.listedNames shouldBe Set("bubble") // read first, so the index is built and clean

    state.modifyHuntedPlayersData(m => m.updated("guild-a", player("Cachero") :: m("guild-a")))
    state.listedNames shouldBe Set("bubble", "cachero")
  }

  test("a removal after a read is visible to the next read") {
    val state = new StreamState
    state.modifyHuntedPlayersData(_ => Map("guild-a" -> List(player("Bubble"), player("Cachero"))))
    state.listedNames shouldBe Set("bubble", "cachero")

    state.modifyHuntedPlayersData(m => m.updated("guild-a", m("guild-a").filterNot(_.name == "Bubble")))
    state.listedNames shouldBe Set("cachero")
  }

  test("dropping a guild's whole list drops its names") {
    val state = new StreamState
    state.modifyHuntedPlayersData(_ => Map("guild-a" -> List(player("Bubble"))))
    state.modifyAlliedPlayersData(_ => Map("guild-b" -> List(player("Cachero"))))
    state.listedNames shouldBe Set("bubble", "cachero")

    state.modifyHuntedPlayersData(m => m.updated("guild-a", List.empty))
    state.listedNames shouldBe Set("cachero")
  }

  test("an edit to the allied list alone still invalidates the index") {
    val state = new StreamState
    state.modifyHuntedPlayersData(_ => Map("guild-a" -> List(player("Bubble"))))
    state.listedNames shouldBe Set("bubble")

    state.modifyAlliedPlayersData(_ => Map("guild-b" -> List(player("Cachero"))))
    state.listedNames shouldBe Set("bubble", "cachero")
  }

  /** The reason the flag is cleared inside the same lock the writers take: a
   *  write landing while a rebuild is in flight must not be swallowed by that
   *  rebuild clearing the flag afterwards. Readers run flat out against writers
   *  here, and the last word has to be the final state of the lists. */
  test("a burst of concurrent writes leaves the index agreeing with the lists") {
    val state = new StreamState
    val writers = 8
    val perWriter = 200

    val pool = Executors.newFixedThreadPool(writers + 4)
    val start = new CountDownLatch(1)
    val done = new CountDownLatch(writers + 4)

    (0 until writers).foreach { t =>
      pool.submit(new Runnable {
        def run(): Unit = {
          start.await()
          try (0 until perWriter).foreach { i =>
            state.modifyHuntedPlayersData(m =>
              m.updated(s"guild-$t", player(s"Char-$t-$i") :: m.getOrElse(s"guild-$t", Nil)))
          } finally done.countDown()
        }
      })
    }
    // Readers, racing the writers to force rebuilds mid-burst.
    (0 until 4).foreach { _ =>
      pool.submit(new Runnable {
        def run(): Unit = {
          start.await()
          try (0 until 500).foreach(_ => state.listedNames)
          finally done.countDown()
        }
      })
    }

    start.countDown()
    done.await(30, TimeUnit.SECONDS) shouldBe true
    pool.shutdown()

    val expected = (for (t <- 0 until writers; i <- 0 until perWriter) yield s"char-$t-$i").toSet
    state.listedNames shouldBe expected
  }
}
