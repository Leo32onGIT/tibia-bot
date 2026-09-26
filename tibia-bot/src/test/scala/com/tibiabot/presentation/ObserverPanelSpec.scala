package com.tibiabot.presentation

import com.tibiabot.domain.{ObserverStatus, ObserverToken}
import com.tibiabot.observer.{ObserverAreas, ObserverPanel, WorldCoverage}
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.mediagallery.MediaGallery
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.jdk.CollectionConverters._

/** The `/observer` reply: its card and its Add and Remove buttons. The emoji are
 *  passed in so these run without loading Config. */
class ObserverPanelSpec extends AnyFunSuite with Matchers {

  private def token(status: ObserverStatus) =
    ObserverToken(1L, "g1", "u1", Some("Antica, Victoris"), Some("Keeper of Tibia"), status, Instant.EPOCH, Instant.EPOCH)

  private val victoris = WorldCoverage("Victoris", Map("Carlin" -> true, "Edron" -> false))

  private def card(view: ObserverPanel): Container = ObserverEmbeds.panelCard(view, ":yes:", ":no:")

  /** The card's parts, in order: each text, "divider" or the picture's address. */
  private def parts(view: ObserverPanel): List[String] =
    card(view).getComponents.asScala.toList.map {
      case t: TextDisplay  => t.getContent
      case _: Separator    => "divider"
      case g: MediaGallery => g.getItems.asScala.map(_.getUrl).mkString
      case other           => other.toString
    }

  /** Which of Add and Remove can be pressed. */
  private def enabled(t: Option[ObserverToken]): List[(String, Boolean)] =
    ObserverEmbeds.controls(t).getComponents.asScala.toList.collect { case b: Button => b.getLabel -> !b.isDisabled }

  private val heading = "### 🔭 Tibia Observer\n-# Raid alerts and mini world changes, pooled from every linked member."

  test("with no token: how to link, the Connect page's picture, then the coverage") {
    parts(ObserverPanel(None, List(victoris))) shouldBe List(
      "### 🔭 Tibia Observer\n-# Link your Tibia account to add raid alerts and mini world changes for this server.",
      "divider",
      ":no: You haven't linked a Tibia Observer token.\n" +
        "-# Click the **Add** button below and enter the token from your Tibia Account.\n" +
        "-# Account Management → Tibia Observer → Connect",
      "https://violentbot.xyz/discord/observer/connect.png",
      "divider",
      "### 🗺️ Raid Coverage",
      ObserverEmbeds.worldCoverageText(victoris, ":yes:", ":no:"),
      "-# Link your account to add the areas you've explored.")
  }

  test("linked: the account, the worlds it covers here, and each one's checklist with the member's areas in bold") {
    val p = parts(ObserverPanel(Some(token(ObserverStatus.Linked)), List(victoris)))
    p.take(3) shouldBe List(heading, "divider", ":yes: Linked as **Keeper of Tibia**\n-# Covering **Victoris** for this server")
    p.last shouldBe "-# The areas in bold are the ones your account covers."
    // The coverage heading, right under the divider with nothing between.
    p(3) shouldBe "divider"
    p(4) shouldBe "### 🗺️ Raid Coverage"
    val lines = p(5).split("\n").toList
    lines.head shouldBe "-# **VICTORIS · 2 OF 15 RAID AREAS**"
    lines.tail shouldBe ObserverAreas.raidAreas.map {
      case "Carlin" => ":yes: **Carlin**"
      case "Edron"  => ":yes: Edron"
      case area     => s":no: $area"
    }
  }

  test("two worlds covered here are named together") {
    parts(ObserverPanel(Some(token(ObserverStatus.Linked)), List(victoris, WorldCoverage("Antica", Map.empty))))(2) shouldBe
      ":yes: Linked as **Keeper of Tibia**\n-# Covering **Victoris** and **Antica** for this server"
  }

  test("linked with no world set up here: no coverage at all") {
    parts(ObserverPanel(Some(token(ObserverStatus.Linked)), Nil)) shouldBe
      List(heading, "divider", ":yes: Linked as **Keeper of Tibia**\n-# None of your worlds is set up here")
  }

  test("a token that was unlinked or expired says so, then how to add one, as with none") {
    val p = parts(ObserverPanel(Some(token(ObserverStatus.NeedsRelink)), List(victoris)))
    p(2) shouldBe ":no: Your Observer token has been unlinked or has expired.\n" +
      "-# Click the **Add** button below and enter the token from your Tibia Account.\n" +
      "-# Account Management → Tibia Observer → Connect"
    p(3) shouldBe "https://violentbot.xyz/discord/observer/connect.png"
    p.last shouldBe "-# Add a fresh token to count your explored areas again."
  }

  test("worlds that don't fit in a message are named rather than cut off") {
    val many = (1 to 20).map(i => WorldCoverage(s"World$i", Map("Carlin" -> false))).toList
    val texts = card(ObserverPanel(None, many)).getComponents.asScala.collect { case t: TextDisplay => t.getContent }
    texts.map(_.length).sum should be <= 4000
    texts.exists(_.startsWith("-# ")) shouldBe true
    texts.find(_.contains("didn't fit")).get should include("World20")
  }

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
