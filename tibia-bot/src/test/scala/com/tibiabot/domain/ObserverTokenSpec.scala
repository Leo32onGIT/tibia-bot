package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ObserverTokenSpec extends AnyFunSuite with Matchers {

  private def token(world: Option[String]) =
    ObserverToken(1L, "g1", "u1", world, Some("Keeper of Tibia"), ObserverStatus.Linked, Instant.EPOCH, Instant.EPOCH)

  test("the worlds a link covers are read from the label it was linked with") {
    token(Some("Cantabra, Honbra, Jadebra")).worlds shouldBe List("Cantabra", "Honbra", "Jadebra")
  }

  test("a link not yet verified covers no worlds") {
    token(None).worlds shouldBe empty
    token(Some(" ")).worlds shouldBe empty
  }
}
