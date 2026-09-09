package com.tibiabot.statistics

import com.typesafe.scalalogging.StrictLogging
import io.circe.parser.parse

import scala.io.Source
import scala.util.Try

/** One boss and the shape of its spawn cycle.
 *
 *  `windowMin`/`windowMax` are days between spawns, `spawnPoints` how many
 *  places it can appear at once. Neither is read yet — the prediction that uses
 *  them is a later phase — but they travel with the catalogue because they are
 *  what makes it worth having a catalogue rather than a list of names.
 *
 *  `raceName` is how kill statistics names the boss when that differs from its
 *  own name: tibia.com counts Yeti kills under "yetis" and Rotworm Queen under
 *  "Rotworm Queens". Four of the seventy-four need it, and matching without it
 *  would silently record those four as never having spawned.
 *
 *  `predict` is false for the seventeen whose cycle is not a fixed window. They
 *  are still recorded — a kill is a fact regardless — and simply will not be
 *  predicted. */
final case class Boss(
    name: String,
    raceName: Option[String],
    predict: Boolean,
    windowMin: Int,
    windowMax: Int,
    spawnPoints: Int,
    category: String
) {

  /** The name to look for in a kill statistics entry. */
  def race: String = raceName.getOrElse(name)
}

/** The bundled boss catalogue (`resources/bosses.json`).
 *
 *  Spawn windows come from github.com/kik-tibia/boss-tracker (MIT, © 2023
 *  kik-tibia), whose `data-example/boss-list.json` this is. Taken rather than
 *  re-derived because the windows are the product of somebody having watched
 *  these bosses for years, and there is no other source for them.
 *
 *  Parsed once, lazily. A missing or malformed file degrades to an empty
 *  catalogue with a warning rather than stopping the bot at boot: with no
 *  catalogue the daily snapshot records nothing, which costs history and breaks
 *  nothing that is already running. Same treatment, and the same reasoning, as
 *  [[com.tibiabot.respawn.RespawnCatalogue]]. */
object BossCatalogue extends StrictLogging {

  private val ResourcePath = "/bosses.json"

  lazy val bosses: List[Boss] = load()

  /** Keyed by the lowercased race name, which is what a kill statistics entry
   *  is matched on. Lowercased because the endpoint's casing is not something
   *  to depend on — it already mixes "yetis" with "Rotworm Queens". */
  lazy val byRace: Map[String, Boss] = bosses.map(boss => boss.race.toLowerCase -> boss).toMap

  /** Whether a kill statistics entry is one of the bosses worth storing. */
  def isBoss(race: String): Boolean = byRace.contains(race.toLowerCase)

  private def load(): List[Boss] = {
    val parsed = for {
      raw <- Try {
        val stream = Option(getClass.getResourceAsStream(ResourcePath))
          .getOrElse(throw new RuntimeException(s"$ResourcePath not found on the classpath"))
        val source = Source.fromInputStream(stream, "UTF-8")
        try source.mkString finally source.close()
      }.toEither.left.map(_.getMessage)
      json <- parse(raw).left.map(_.getMessage)
      bosses <- json.hcursor.downField("bosses").as[List[Boss]](
        io.circe.Decoder.decodeList(io.circe.Decoder.instance { c =>
          for {
            name <- c.downField("name").as[String]
            raceName <- c.downField("raceName").as[Option[String]]
            // Absent means predictable, which is the common case — only the
            // seventeen exceptions carry the field at all.
            predict <- c.downField("predict").as[Option[Boolean]].map(_.getOrElse(true))
            windowMin <- c.downField("windowMin").as[Int]
            windowMax <- c.downField("windowMax").as[Int]
            spawnPoints <- c.downField("spawnPoints").as[Option[Int]].map(_.getOrElse(1))
            category <- c.downField("category").as[Option[String]].map(_.getOrElse(""))
          } yield Boss(name.trim, raceName.map(_.trim).filter(_.nonEmpty), predict,
            windowMin, windowMax, spawnPoints, category.trim)
        })
      ).left.map(_.getMessage)
    } yield bosses

    parsed match {
      case Right(bosses) =>
        logger.info(s"Loaded ${bosses.size} bosses from $ResourcePath")
        bosses
      case Left(error) =>
        logger.warn(s"Could not load the boss catalogue from $ResourcePath — " +
          s"no boss kills will be recorded, so nothing will accumulate for spawn predictions: $error")
        Nil
    }
  }
}
