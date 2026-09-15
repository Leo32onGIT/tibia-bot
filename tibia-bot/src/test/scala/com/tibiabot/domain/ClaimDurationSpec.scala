package com.tibiabot.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** What somebody may type into a length of time — a claim ceiling through
 *  `parse`, every box that has always been minutes through `parseMinutes`.
 *
 *  Worth pinning down properly because it is a guess in one place — a bare
 *  number — and a guess that changes silently is worse than no guess at all.
 *  The two doors differ on exactly that guess and nowhere else, which is a
 *  property worth a test of its own rather than a comment.
 */
class ClaimDurationSpec extends AnyWordSpec with Matchers {

  private def parsed(text: String) = ClaimDuration.parse(text)
  private def minutes(text: String) = parsed(text).toOption.flatten

  private def asMinutes(text: String) = ClaimDuration.parseMinutes(text).toOption.flatten

  "a bare number" should {

    // The guess: nobody sets a ceiling of two minutes, and 24 is also the
    // longest ceiling allowed, so everything above it is unambiguous as minutes.
    "be hours at or below the cutoff" in {
      minutes("1") shouldBe Some(60)
      minutes("2") shouldBe Some(120)
      minutes("24") shouldBe Some(1440)
    }

    "be minutes above it" in {
      minutes("25") shouldBe Some(25)
      minutes("120") shouldBe Some(120)
      minutes("90") shouldBe Some(90)
    }

    // The known cost of the guess, written down so it is a decision rather than
    // a surprise: twenty minutes has to be said as 20m.
    "read 20 as twenty hours, which is what the suffix is for" in {
      minutes("20") shouldBe Some(1200)
      minutes("20m") shouldBe Some(20)
    }
  }

  "an explicit unit" should {

    "read hours however they are spelled" in {
      List("2h", "2 h", "2hr", "2hrs", "2hour", "2hours", "2 HOURS")
        .foreach(text => withClue(s"$text: ")(minutes(text) shouldBe Some(120)))
    }

    "read minutes however they are spelled" in {
      List("90m", "90 m", "90min", "90mins", "90minute", "90minutes", "90 Min")
        .foreach(text => withClue(s"$text: ")(minutes(text) shouldBe Some(90)))
    }

    // Longest first, or the minutes are silently dropped and 1h30 becomes an
    // hour — the kind of wrong that looks like it worked.
    "read the two together" in {
      minutes("1h30") shouldBe Some(90)
      minutes("1h30m") shouldBe Some(90)
      minutes("2h05m") shouldBe Some(125)
      minutes("1 h 30 m") shouldBe Some(90)
    }

    "read decimal hours, rounded to the minute" in {
      minutes("1.5h") shouldBe Some(90)
      minutes("2.5h") shouldBe Some(150)
      minutes("1.75h") shouldBe Some(105)
    }
  }

  "nothing typed" should {
    // Not an error: it is how an override is cleared, at every door.
    "mean follow the server" in {
      parsed("") shouldBe Right(None)
      parsed("   ") shouldBe Right(None)
      parsed(null) shouldBe Right(None)
    }
  }

  "anything else" should {

    // Refused rather than guessed at. Reading "2 days" as two minutes because it
    // starts with a 2 is how a ceiling ends up somewhere nobody chose.
    "be refused with something to do about it" in {
      List("2 days", "soon", "two hours", "-30", "1h30x", "h", "m", "1:30")
        .foreach { text =>
          withClue(s"$text: ")(parsed(text).isLeft shouldBe true)
        }
      parsed("soon").left.toOption.get should include("2h")
    }

    "refuse a number too large to be a duration rather than overflowing" in {
      parsed("99999999999999999999m").isLeft shouldBe true
    }
  }

  "the minutes-first door" should {

    // The whole of the difference, and the reason there are two doors: these
    // boxes have meant minutes for as long as they have existed, and quietly
    // making an old habit mean something twelve times longer is not an upgrade.
    "read a bare number as minutes, however small" in {
      asMinutes("20") shouldBe Some(20)
      asMinutes("5") shouldBe Some(5)
      asMinutes("90") shouldBe Some(90)
      asMinutes("240") shouldBe Some(240)
    }

    "agree with the ceiling door about everything with a unit on it" in {
      List("2h", "2 h", "2hrs", "2hours", "90m", "90min", "1h30", "1h30m", "1.5h", "2h05m", "25")
        .foreach { text =>
          withClue(s"$text: ")(asMinutes(text) shouldBe minutes(text))
        }
    }

    // The only disagreement, written down so it is a decision rather than a
    // surprise the next person has to rediscover from two call sites.
    "differ from it on a bare number, and only there" in {
      (1 to ClaimDuration.HoursCutoff).foreach { n =>
        withClue(s"$n: ") {
          asMinutes(n.toString) shouldBe Some(n)
          minutes(n.toString) shouldBe Some(n * 60)
        }
      }
      // Above the cutoff there is nothing to disagree about.
      List("25", "60", "120", "1440").foreach { text =>
        withClue(s"$text: ")(asMinutes(text) shouldBe minutes(text))
      }
    }

    // For the one form that hands stamina back rather than spending it. The
    // ceiling door still refuses these — a negative ceiling is not a thing.
    "read a negative, which the ceiling door refuses" in {
      asMinutes("-30") shouldBe Some(-30)
      asMinutes("-30m") shouldBe Some(-30)
      asMinutes("-2h") shouldBe Some(-120)
      asMinutes("-1h30") shouldBe Some(-90)
      parsed("-30").isLeft shouldBe true
    }

    // A required box reports an empty one as a question; a lone minus sign is
    // not empty, so it has to come back as something to fix rather than as
    // "nothing typed".
    "refuse a minus sign with nothing after it" in {
      ClaimDuration.parseMinutes("-").isLeft shouldBe true
      ClaimDuration.parseMinutes("- ").isLeft shouldBe true
    }

    "still mean nothing typed when nothing was" in {
      ClaimDuration.parseMinutes("") shouldBe Right(None)
      ClaimDuration.parseMinutes("   ") shouldBe Right(None)
      ClaimDuration.parseMinutes(null) shouldBe Right(None)
    }

    "refuse the same nonsense" in {
      List("2 days", "soon", "two hours", "1h30x", "h", "m", "1:30", "--5", "-")
        .foreach(text => withClue(s"$text: ")(ClaimDuration.parseMinutes(text).isLeft shouldBe true))
    }
  }
}
