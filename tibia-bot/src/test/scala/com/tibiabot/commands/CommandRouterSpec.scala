package com.tibiabot.commands

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

class CommandRouterSpec extends AnyFunSuite with Matchers {

  test("routes a known command to its handler and reports true") {
    val seen = ListBuffer.empty[String]
    val router = new CommandRouter[String](Map(
      "setup"  -> (e => seen += s"setup:$e"),
      "online" -> (e => seen += s"online:$e")
    ))

    router.route("online", "guild1") shouldBe true
    seen.toList shouldBe List("online:guild1")
  }

  test("an unknown command runs no handler and reports false") {
    val seen = ListBuffer.empty[String]
    val router = new CommandRouter[String](Map("setup" -> (e => seen += e)))

    router.route("nope", "x") shouldBe false
    seen shouldBe empty
  }

  test("exposes the set of registered command names") {
    val router = new CommandRouter[String](Map("a" -> (_ => ()), "b" -> (_ => ())))
    router.commandNames shouldBe Set("a", "b")
  }
}
