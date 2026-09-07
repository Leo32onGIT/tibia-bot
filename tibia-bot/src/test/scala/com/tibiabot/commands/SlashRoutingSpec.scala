package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** Pins the slash dispatch table against the registered command schemas, so a
 *  command can never be registered with Discord without a handler (which would
 *  silently no-op when invoked) and vice versa.
 *
 *  Both directions are checked against invokable *paths* rather than command
 *  names: dispatch keys off the full path and falls back to the longest
 *  registered prefix, so names alone cannot tell whether the two agree once a
 *  command is folded underneath a root. */
class SlashRoutingSpec extends AnyFunSuite with Matchers {

  test("every invokable path in the registered tree resolves to a handler") {
    val router = new CommandRouter[Any](SlashRouting.handlers.map { case (path, _) => path -> ((_: Any) => ()) })
    val unroutable = leafPaths.filterNot(router.handles)
    withClue(s"invokable paths with no handler: $unroutable") {
      unroutable shouldBe empty
    }
  }

  test("every registered route is reachable from the command tree — no dead routes") {
    val leaves = leafPaths
    val dead = SlashRouting.handlers.keySet.filterNot(key =>
      leaves.exists(leaf => leaf == key || leaf.startsWith(s"$key ")))
    withClue(s"handlers nothing registered can reach: $dead") {
      dead shouldBe empty
    }
  }

  /** A duplicated key in the map literal collapses silently, losing whichever
   *  entry came first — which neither check above can see, since what survives
   *  is still a valid route. Only the count gives it away. */
  test("no route is registered twice") {
    SlashRouting.handlers should have size 18
  }

  test("the loot split form is named at the depth it actually lives at") {
    SlashRouting.opensModal.foreach(path => SlashRouting.handlers.keySet should contain(path))
  }

  /** Each way the registered commands can be invoked, as a CommandPath string:
   *  a bare name, "name sub", or "name group sub". */
  private def leafPaths: List[String] =
    CommandSchemas.adminCommands.flatMap { command =>
      val direct = command.getSubcommands.asScala.map(sub => CommandPath.of(command.getName, None, Some(sub.getName)))
      val grouped = command.getSubcommandGroups.asScala.flatMap(group =>
        group.getSubcommands.asScala.map(sub =>
          CommandPath.of(command.getName, Some(group.getName), Some(sub.getName))))
      val leaves = (direct ++ grouped).toList
      if (leaves.isEmpty) List(CommandPath.of(command.getName, None, None)) else leaves
    }
}
