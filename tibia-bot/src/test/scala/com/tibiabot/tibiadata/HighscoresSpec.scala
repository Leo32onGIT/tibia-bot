package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant

/** The highscores wire model, the request vocabulary, and the snapshot clock.
 *
 *  Fixtures are unedited live responses (TibiaData v4.10.0, 2 Sep 2026): one
 *  unfiltered skill page, one experience page, and one restriction-mode refusal. */
class HighscoresSpec extends AnyFunSuite with Matchers with JsonSupport {

  private def fixture(name: String): String = {
    val is = getClass.getResourceAsStream(s"/tibiadata/$name")
    require(is != null, s"missing fixture /tibiadata/$name")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString finally is.close()
  }

  private val shielding = fixture("highscores_shielding.json").parseJson.convertTo[HighscoresResponse]
  private val experience = fixture("highscores_experience.json").parseJson.convertTo[HighscoresResponse]

  test("a skill page parses into rows carrying both the skill value and the character level") {
    val data = shielding.highscores
    data.category shouldBe "shielding"
    data.vocation shouldBe "all"
    data.highscore_list should have size Highscores.PageSize

    val first = data.highscore_list.head
    first.name shouldBe "Xerxess"
    first.world shouldBe "Refugia"
    first.vocation shouldBe "Paladin"
    // The skill level itself, and the character level beside it — which is what
    // lets an advance be gated on levels_min without a second lookup.
    first.value shouldBe 114L
    first.level shouldBe 13
  }

  test("experience values exceed Int, so the model has to hold them as Long") {
    val top = experience.highscores.highscore_list.head.value
    top should be > Int.MaxValue.toLong
  }

  test("the endpoint reports the same 1000-record cap the constants assume") {
    List(shielding, experience).foreach { response =>
      val page = response.highscores.highscore_page
      page.total_pages shouldBe Highscores.MaxPages
      page.total_records shouldBe Highscores.MaxRecords
    }
  }

  test("rank is dense, so the last row of a full page can rank below its position") {
    // Page 20's fiftieth row is the thousandth record, but ties share a rank —
    // anything treating rank as a position would be reading 143 rows short here.
    val page20 = shielding.highscores
    page20.highscore_page.current_page shouldBe Highscores.MaxPages
    page20.highscore_list.last.rank should be < Highscores.MaxRecords
  }

  test("a restriction-mode refusal fails to parse rather than yielding an empty list") {
    // The 400 body carries no `highscores` object at all. Silently reading it as
    // zero rows would look exactly like "nobody advanced", so it must throw and
    // reach the client's logged-Left path instead.
    a[DeserializationException] should be thrownBy
      fixture("highscores_restricted.json").parseJson.convertTo[HighscoresResponse]
  }

  test("page is a path segment, and the page range is guarded") {
    val list = HighscoreList(HighscoreCategory.MagicLevel, HighscoreVocation.Knights)
    // Not `?page=20` — the query parameter is accepted and silently ignored,
    // returning page 1 every time.
    list.path("Antica", 20) shouldBe "/v4/highscores/Antica/magiclevel/knights/20"
    an[IllegalArgumentException] should be thrownBy list.path("Antica", 0)
    an[IllegalArgumentException] should be thrownBy list.path("Antica", Highscores.MaxPages + 1)
  }

  test("world names are encoded with %20 rather than +") {
    val list = HighscoreList(HighscoreCategory.Experience, HighscoreVocation.All)
    list.path("New World", 1) shouldBe "/v4/highscores/New%20World/experience/all/1"
  }

  test("only a vocation filter sends a list to our own instance") {
    HighscoreCategory.all.foreach { category =>
      HighscoreList(category, HighscoreVocation.All).source shouldBe HighscoreSource.Public
      HighscoreVocation.vocations.foreach { vocation =>
        HighscoreList(category, vocation).source shouldBe HighscoreSource.Local
      }
    }
  }

  test("experience is recorded but never announced; every skill is announced") {
    HighscoreList(HighscoreCategory.Experience, HighscoreVocation.All).postsAdvances shouldBe false
    HighscoreCategory.all.filterNot(_ == HighscoreCategory.Experience).foreach { category =>
      HighscoreList(category, HighscoreVocation.All).postsAdvances shouldBe true
    }
  }

  test("the snapshot is the response timestamp less the published age") {
    // Fixture: generated 06:01:34Z, 21 minutes after tibia.com last rebuilt.
    HighscoreSnapshot.of(experience) shouldBe Some(Instant.parse("2026-09-02T05:40:34Z"))
  }

  test("a response with no timestamp yields no snapshot rather than a wrong one") {
    val undated = experience.copy(information = experience.information.copy(timestamp = None))
    HighscoreSnapshot.of(undated) shouldBe None
  }

  test("snapshot comparison tolerates the minute of jitter in a floored age") {
    val seen = Instant.parse("2026-09-02T05:40:34Z")
    // The same snapshot re-read a minute later: the floored age moves, so the
    // estimate does too. That must not read as new data.
    HighscoreSnapshot.isNewerThan(seen.plusSeconds(59), Some(seen)) shouldBe false
    // An hour on is unambiguously the next snapshot.
    HighscoreSnapshot.isNewerThan(seen.plusSeconds(3600), Some(seen)) shouldBe true
    // Nothing seen yet always counts as new.
    HighscoreSnapshot.isNewerThan(seen, None) shouldBe true
  }
}
