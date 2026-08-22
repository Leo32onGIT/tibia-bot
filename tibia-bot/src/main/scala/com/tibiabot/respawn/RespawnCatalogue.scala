package com.tibiabot.respawn

import com.typesafe.scalalogging.StrictLogging
import io.circe.parser.parse

import scala.io.Source
import scala.util.Try

/** One spawn as it appears in the bundled seed file, before a guild imports it
 *  into its own catalogue. */
final case class SeedSpawn(code: String, region: String, name: String, creature: String)

/** Loads and searches the bundled respawn seed catalogue
 *  (`resources/respawns.json`).
 *
 *  Parsed once, lazily, on first use — the file is a few hundred rows and never
 *  changes at runtime, so re-reading it per guild import or per autocomplete
 *  keystroke would be pure waste. A malformed or missing file degrades to an
 *  empty catalogue with a warning rather than taking the bot down at boot: the
 *  seed is a convenience, and a guild can still build a catalogue by hand from
 *  the dashboard.
 */
object RespawnCatalogue extends StrictLogging {

  private val ResourcePath = "/respawns.json"

  lazy val seed: List[SeedSpawn] = load()

  private def load(): List[SeedSpawn] = {
    val parsed = for {
      raw <- Try {
        val stream = Option(getClass.getResourceAsStream(ResourcePath))
          .getOrElse(throw new RuntimeException(s"$ResourcePath not found on the classpath"))
        val source = Source.fromInputStream(stream, "UTF-8")
        try source.mkString finally source.close()
      }.toEither.left.map(_.getMessage)
      json <- parse(raw).left.map(_.getMessage)
      spawns <- json.hcursor.downField("spawns").as[List[SeedSpawn]](
        io.circe.Decoder.decodeList(io.circe.Decoder.instance { c =>
          for {
            code <- c.downField("code").as[String]
            region <- c.downField("region").as[String]
            name <- c.downField("name").as[String]
            // absent or null is as valid as "" — the field is optional by design
            creature <- c.downField("creature").as[Option[String]].map(_.getOrElse(""))
          } yield SeedSpawn(code.trim, region.trim, name.trim, creature.trim)
        })
      ).left.map(_.getMessage)
    } yield spawns

    parsed match {
      case Right(spawns) =>
        logger.info(s"Loaded ${spawns.size} seed respawns from $ResourcePath")
        spawns
      case Left(error) =>
        logger.warn(s"Could not load the respawn seed catalogue from $ResourcePath — " +
          s"guilds will start with an empty catalogue and can only add spawns from the dashboard: $error")
        Nil
    }
  }

  /** Sort codes the way a person reads them: numerically by the leading digits,
   *  then by the optional letter suffix. Plain string ordering would put
   *  "1010" before "201" and "411c" before "411a" is at least consistent, but
   *  the numeric part is what makes a long list scannable. */
  def sortKey(code: String): (Int, String) = {
    val digits = code.takeWhile(_.isDigit)
    val suffix = code.drop(digits.length)
    (Try(digits.toInt).getOrElse(Int.MaxValue), suffix)
  }
}
