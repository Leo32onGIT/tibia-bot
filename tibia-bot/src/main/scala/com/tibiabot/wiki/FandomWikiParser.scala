package com.tibiabot.wiki

import com.tibiabot.domain.BossEntry
import org.jsoup.Jsoup

import scala.jdk.CollectionConverters._

/** Pure HTML parsing for the Fandom wiki pages, split out from the HTTP fetch so
 *  it can be unit-tested with fixture HTML. Logic moved verbatim from BotApp's
 *  fetchDreamScarBosses / fetchCreatureNames. */
object FandomWikiParser {

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
