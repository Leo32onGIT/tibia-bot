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

  test("the only routable command not registered with Discord is the WIP leaderboards") {
    // leaderboards is intentionally defined-but-unregistered (see CommandSchemasSpec);
    // any OTHER extra handler would be a dead route to flag.
    SlashRouting.handlers.keySet.diff(registered) shouldBe Set("leaderboards")
  }

  test("no duplicate handler names") {
    SlashRouting.handlers.keySet should have size SlashRouting.handlers.size
  }
}
