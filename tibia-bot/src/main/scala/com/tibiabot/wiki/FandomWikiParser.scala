package com.tibiabot.wiki

import com.tibiabot.domain.{BossEntry, DreamScarSnapshot}
import org.jsoup.Jsoup

import java.time.DayOfWeek
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Pure HTML parsing for the Fandom wiki pages, split out from the HTTP fetch so
 *  it can be unit-tested with fixture HTML. Logic moved verbatim from BotApp's
 *  fetchDreamScarBosses / fetchCreatureNames. */
object FandomWikiParser {

  /** The page opens with "Today is Wednesday and the Dream Scar boss on most
   *  worlds should be …" — the day the cached render was built for. */
  private val TodayIs = """Today is (\w+)""".r

  /** The boss table plus the day the page thinks it is, together — a caller
   *  checking freshness needs both from the same read. */
  def parseDreamScarSnapshot(html: String): DreamScarSnapshot =
    DreamScarSnapshot(parseDreamScarDay(html), parseDreamScarBosses(html))

  /** The weekday the page states it was rendered for, if it states one. Read
   *  off the document's text rather than its markup, since the sentence is
   *  plain prose interrupted by links. */
  def parseDreamScarDay(html: String): Option[DayOfWeek] =
    TodayIs.findFirstMatchIn(Jsoup.parse(html).text())
      .flatMap(m => Try(DayOfWeek.valueOf(m.group(1).toUpperCase)).toOption)

  def parseDreamScarBosses(html: String): List[BossEntry] = {

    val doc = Jsoup.parse(html)

    val table = doc.select("table.wikitable").first()

    val tableEntries =
      if (table == null) Nil
      else {
        table.select("tr")
          .asScala
          .drop(1)
          .flatMap { row =>
            val cols = row.select("td").asScala
            if (cols.size >= 2)
              Some(BossEntry(cols(0).text().trim, cols(1).text().trim))
            else None
          }
          .toList
      }

    val fallbackBoss =
      Option(doc.select("div.mp-rashid").first())
        .map(_.select("a").asScala.map(_.text().trim).filter(_.nonEmpty))
        .flatMap(_.find(_ != "Dream Scar")) // skip page link

    val fallbackEntry =
      fallbackBoss.map(b => BossEntry("Unknown", b)).toList

    tableEntries ++ fallbackEntry
  }

  def parseCreatureNames(html: String): List[String] = {
    val doc = Jsoup.parse(html)
    doc.select("a")
      .asScala
      .flatMap { link =>
        val href = link.attr("href")
        val text = link.text().trim
        // creature pages are /wiki/Creature_Name
        if (
          href.startsWith("/wiki/") &&
          text.nonEmpty &&
          !text.contains(":") &&
          !href.contains("List_of_Creatures")
        ) {
          Some(text)
        } else {
          None
        }
      }
      .distinct
      .toList
  }
}
