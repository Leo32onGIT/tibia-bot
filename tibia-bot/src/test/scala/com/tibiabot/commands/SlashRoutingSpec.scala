package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the slash dispatch table against the registered command schemas, so a
 *  command can never be registered with Discord without a handler (which would
 *  silently no-op when invoked) and vice versa. */
class SlashRoutingSpec extends AnyFunSuite with Matchers {

  private val registered: Set[String] = CommandSchemas.adminCommands.map(_.getName).toSet

  test("every registered slash command has a dispatch handler") {
    val missing = registered.diff(SlashRouting.handlers.keySet)
    withClue(s"registered commands with no handler: $missing") {
      missing shouldBe empty
    }
  }

  test("every routable command is registered with Discord — no dead routes") {
    // Previously this allowed one exception, the unregistered work-in-progress
    // "leaderboards" handler. That was removed, so the invariant is now exact:
    // a handler Discord never dispatches to is dead code.
    val unreachable = SlashRouting.handlers.keySet.diff(registered)
    withClue(s"handlers with no registered command: $unreachable") {
      unreachable shouldBe empty
    }
  }

  test("no duplicate handler names") {
    SlashRouting.handlers.keySet should have size SlashRouting.handlers.size
  }
}
