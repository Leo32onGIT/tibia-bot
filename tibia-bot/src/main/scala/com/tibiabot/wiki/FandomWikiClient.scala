package com.tibiabot.wiki

import com.tibiabot.domain.BossEntry
import io.circe.parser._
import sttp.client3._

/** Fetches and parses Fandom wiki pages via the api.php parse endpoint. Does no
 *  I/O on construction; each method performs its own request. */
final class FandomWikiClient extends WikiClient {

  def dreamScarBosses(): List[BossEntry] =
    FandomWikiParser.parseDreamScarBosses(fetchHtml("Dream_Scar/Boss_of_the_Day"))

  def creatureNames(): List[String] =
    FandomWikiParser.parseCreatureNames(fetchHtml("List_of_Creatures_(Ordered)"))

  /** Fetch the rendered HTML of a wiki page via the parse API. */
  private def fetchHtml(page: String): String = {
    val backend = HttpURLConnectionBackend()
    val apiUrl =
      "https://tibia.fandom.com/api.php" +
        "?action=parse" +
        s"&page=$page" +
        "&prop=text" +
        "&format=json"
    val response = basicRequest
      .get(uri"$apiUrl")
      .header("User-Agent", "Mozilla/5.0")
      .send(backend)
    val jsonStr = response.body.getOrElse(
      throw new RuntimeException("Empty response from API")
    )
    val parsed = parse(jsonStr).getOrElse(
      throw new RuntimeException("Invalid JSON from API")
    )
    parsed.hcursor
      .downField("parse")
      .downField("text")
      .downField("*")
      .as[String]
      .getOrElse(
        throw new RuntimeException("Could not extract HTML from API response")
      )
  }
}
