package com.tibiabot.presentation

import com.tibiabot.domain.{ObserverStatus, ObserverToken}
import net.dv8tion.jda.api.components.buttons.Button
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.jdk.CollectionConverters._

/** The `/observer` panel's Add and Remove buttons. */
class ObserverPanelSpec extends AnyFunSuite with Matchers {

  private def token(status: ObserverStatus) =
    ObserverToken(1L, "g1", "u1", Some("Antica"), None, status, Instant.EPOCH, Instant.EPOCH)

  /** Which of Add and Remove can be pressed. */
  private def enabled(t: Option[ObserverToken]): List[(String, Boolean)] =
    ObserverEmbeds.controls(t).getComponents.asScala.toList.collect { case b: Button => b.getLabel -> !b.isDisabled }

  test("with no token, only Add") {
    enabled(None) shouldBe List("Add" -> true, "Remove" -> false)
  }

  test("with a working link, only Remove") {
    enabled(Some(token(ObserverStatus.Linked))) shouldBe List("Add" -> false, "Remove" -> true)
  }

  test("with a link that needs a fresh token, both: the panel asks for Add") {
    enabled(Some(token(ObserverStatus.NeedsRelink))) shouldBe List("Add" -> true, "Remove" -> true)
    enabled(Some(token(ObserverStatus.Error))) shouldBe List("Add" -> true, "Remove" -> true)
  }
}
