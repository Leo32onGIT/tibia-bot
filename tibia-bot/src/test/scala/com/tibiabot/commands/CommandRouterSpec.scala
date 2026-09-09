package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

class CommandRouterSpec extends AnyFunSuite with Matchers {

  test("routes a known path to its handler and reports true") {
    val seen = ListBuffer.empty[String]
    val router = new CommandRouter[String](Map(
      "setup"  -> (e => seen += s"setup:$e"),
      "online" -> (e => seen += s"online:$e")
    ))

    router.route("online", "guild1") shouldBe true
    seen.toList shouldBe List("online:guild1")
  }

  test("an unknown path runs no handler and reports false") {
    val seen = ListBuffer.empty[String]
    val router = new CommandRouter[String](Map("setup" -> (e => seen += e)))

    router.route("nope", "x") shouldBe false
    seen shouldBe empty
  }

  // The whole point of path routing: handlers stay registered at the depth that
  // owns the behaviour, and keep reading the subcommand themselves.
  test("a deeper path falls back to the handler registered at its prefix") {
    val seen = ListBuffer.empty[String]
    val router = new CommandRouter[String](Map("hunted" -> (e => seen += e)))

    router.route("hunted player", "x") shouldBe true
    router.route("settings neutral levels", "y") shouldBe false
    seen.toList shouldBe List("x")
  }

  test("the most specific registered prefix wins") {
    val seen = ListBuffer.empty[String]
    val router = new CommandRouter[String](Map(
      "settings"         -> (_ => seen += "root"),
      "settings neutral" -> (_ => seen += "neutral")
    ))

    router.route("settings neutral levels", "x") shouldBe true
    router.route("settings fullbless", "x") shouldBe true
    seen.toList shouldBe List("neutral", "root")
  }

  // A prefix has to be a whole segment: "settings" must not serve "settingsfoo",
  // which is a different command entirely.
  test("prefixes match on whole segments, never on a partial name") {
    val router = new CommandRouter[String](Map("settings" -> (_ => ())))

    router.handles("settings") shouldBe true
    router.handles("settingsfoo") shouldBe false
  }

  test("handles answers without invoking the handler") {
    val seen = ListBuffer.empty[String]
    val router = new CommandRouter[String](Map("hunted" -> (e => seen += e)))

    router.handles("hunted player") shouldBe true
    router.handles("allies player") shouldBe false
    seen shouldBe empty
  }

  test("exposes the set of registered paths") {
    val router = new CommandRouter[String](Map("a" -> (_ => ()), "b c" -> (_ => ())))
    router.registeredPaths shouldBe Set("a", "b c")
  }
}
