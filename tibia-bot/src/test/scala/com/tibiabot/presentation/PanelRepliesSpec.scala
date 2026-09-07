package com.tibiabot.presentation

import com.tibiabot.domain.BulkListOutcome
import com.tibiabot.panels.PanelIds.Panel
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class PanelRepliesSpec extends AnyFunSuite with Matchers {

  private def fields(outcome: BulkListOutcome, adding: Boolean = true) =
    PanelReplies.bulkEmbed(Panel.Hunted, "player", adding, outcome, "X")
      .getFields.asScala.map(f => f.getName -> f.getValue).toList

  private def titles(outcome: BulkListOutcome, adding: Boolean = true) =
    fields(outcome, adding).map(_._1)

  test("a plain add reports what went on the list") {
    val embed = PanelReplies.bulkEmbed(Panel.Hunted, "player", adding = true,
      BulkListOutcome(added = List("Bubble", "Charm")))
    embed.getDescription should include("2")
    embed.getDescription should include("hunted list")
    titles(BulkListOutcome(added = List("Bubble", "Charm"))).head should include("Added")
  }

  test("one name reads as one, not as a plural") {
    PanelReplies.bulkEmbed(Panel.Hunted, "player", adding = true,
      BulkListOutcome(added = List("Bubble"))).getDescription should include("**1** player")
  }

  /** The whole reason PlayerLookup has three cases: a name the API never
   *  answered for must never be reported as nonexistent. */
  test("a failed lookup is reported apart from a name that does not exist") {
    val outcome = BulkListOutcome(notFound = List("Nobody"), unavailable = List("Bubble"))
    val shown = fields(outcome)
    shown.map(_._1).mkString should include("Couldn't check")
    shown.find(_._1.contains("Couldn't check")).get._2 should include("Bubble")
    shown.find(_._1.contains("No such character")).get._2 should include("Nobody")
    shown.find(_._1.contains("No such character")).get._2 should not include "Bubble"
  }

  test("a failed lookup says the names were left alone and can be retried") {
    val embed = PanelReplies.bulkEmbed(Panel.Hunted, "player", adding = true,
      BulkListOutcome(unavailable = List("Bubble")))
    embed.getDescription should include("left alone")
    embed.getDescription.toLowerCase should include("retry")
  }

  test("names already on the list are their own group, not a failure") {
    titles(BulkListOutcome(already = List("Bubble"))).mkString should include("Already on the list")
  }

  test("removing says removed rather than added") {
    val embed = PanelReplies.bulkEmbed(Panel.Hunted, "player", adding = false,
      BulkListOutcome(added = List("Bubble")))
    embed.getDescription should include("removed from")
    titles(BulkListOutcome(added = List("Bubble")), adding = false).head should include("Removed")
  }

  test("nothing at all still produces a readable answer") {
    val embed = PanelReplies.bulkEmbed(Panel.Hunted, "player", adding = true, BulkListOutcome.empty)
    embed.getDescription should include("Nothing")
    embed.getFields.asScala shouldBe empty
  }

  /** An embed field caps at 1024 characters; a hundred names would run past it
   *  and Discord would reject the whole message. */
  test("a long list is cut to a count rather than overflowing the field") {
    val many = (1 to 100).map(i => s"Character Number $i").toList
    val embed = PanelReplies.bulkEmbed(Panel.Hunted, "player", adding = true,
      BulkListOutcome(added = many))
    val field = embed.getFields.asScala.head
    field.getName should include("100")
    field.getValue.length should be <= 1024
    field.getValue should include("more")
  }

  test("over-the-limit names are named as skipped, not silently dropped") {
    val outcome = BulkListOutcome(added = List("Bubble"), skipped = List("Charm"))
    titles(outcome).mkString should include("Over the limit")
    PanelReplies.bulkEmbed(Panel.Hunted, "player", adding = true, outcome)
      .getDescription should include("separately")
  }
}
