package com.tibiabot.presentation

import com.tibiabot.domain.BulkListOutcome
import com.tibiabot.panels.PanelIds.Panel
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class PanelRepliesSpec extends AnyFunSuite with Matchers {

  /** Distinct stand-ins for the server's configured emoji, so a test can tell the
   *  reply used what it was given rather than a literal of its own. */
  private val Yes = "<yes>"
  private val No = "<no>"

  /** Every call goes through here. The emoji are required arguments precisely so
   *  a caller cannot forget them — a spec repeating them nine times would be the
   *  first place somebody stopped noticing. */
  private def embed(outcome: BulkListOutcome, adding: Boolean = true, tagKey: String = "") =
    PanelReplies.bulkEmbed(Panel.Hunted, "player", adding, outcome, Yes, No, tagKey)

  private def fields(outcome: BulkListOutcome, adding: Boolean = true) =
    embed(outcome, adding).getFields.asScala.map(f => f.getName -> f.getValue).toList

  private def titles(outcome: BulkListOutcome, adding: Boolean = true) =
    fields(outcome, adding).map(_._1)

  test("a plain add reports what went on the list") {
    val result = embed(adding = true, outcome = BulkListOutcome(added = List("Bubble", "Charm")))
    result.getDescription should include("2")
    result.getDescription should include("hunted list")
    titles(BulkListOutcome(added = List("Bubble", "Charm"))).head should include("Added")
  }

  test("one name reads as one, not as a plural") {
    embed(adding = true, outcome = BulkListOutcome(added = List("Bubble"))).getDescription should include("**1** player")
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
    val result = embed(adding = true, outcome = BulkListOutcome(unavailable = List("Bubble")))
    result.getDescription should include("left alone")
    result.getDescription.toLowerCase should include("retry")
  }

  test("names already on the list are their own group, not a failure") {
    titles(BulkListOutcome(already = List("Bubble"))).mkString should include("Already on the list")
  }

  test("removing says removed rather than added") {
    val result = embed(adding = false, outcome = BulkListOutcome(added = List("Bubble")))
    result.getDescription should include("removed from")
    titles(BulkListOutcome(added = List("Bubble")), adding = false).head should include("Removed")
  }

  test("nothing at all still produces a readable answer") {
    val result = embed(BulkListOutcome.empty)
    result.getDescription should include("Nothing")
    result.getFields.asScala shouldBe empty
  }

  /** An embed field caps at 1024 characters; a hundred names would run past it
   *  and Discord would reject the whole message. */
  test("a long list is cut to a count rather than overflowing the field") {
    val many = (1 to 100).map(i => s"Character Number $i").toList
    val result = embed(adding = true, outcome = BulkListOutcome(added = many))
    val field = result.getFields.asScala.head
    field.getName should include("100")
    field.getValue.length should be <= 1024
    field.getValue should include("more")
  }

  test("over-the-limit names are named as skipped, not silently dropped") {
    val outcome = BulkListOutcome(added = List("Bubble"), skipped = List("Charm"))
    titles(outcome).mkString should include("Over the limit")
    embed(outcome)
      .getDescription should include("separately")
  }

  /** The reply must use the server's configured emoji, not a literal of its own.
   *
   *  Worth its own test because the failure is quiet: a caller that dropped the
   *  argument got a plausible-looking tick from a default and nothing complained.
   *  Both are required arguments now, so the compiler asks — this pins that they
   *  are also actually used. */
  test("the configured yes emoji heads the added and removed groups") {
    titles(BulkListOutcome(added = List("Bubble"))).mkString should include(Yes)
    titles(BulkListOutcome(added = List("Bubble")), adding = false).mkString should include(Yes)
    titles(BulkListOutcome(added = List("Bubble"))).mkString should not include ":white_check_mark:"
  }

  test("the configured no emoji fronts the couldn't-check note") {
    embed(BulkListOutcome(unavailable = List("Bubble"))).getDescription should include(No)
  }
}
