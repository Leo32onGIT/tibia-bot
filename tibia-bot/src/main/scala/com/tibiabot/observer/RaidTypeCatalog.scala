package com.tibiabot.observer

import com.typesafe.scalalogging.StrictLogging
import io.circe.parser.parse

import scala.io.Source
import scala.util.Try

/** A creature that appears in a raid, with the catalogue's count qualifier when it
 *  has one (e.g. `8x`, `2x per tower floor`). */
final case class RaidCreature(name: String, qualifier: Option[String]) {
  def display: String = qualifier.map(q => s"$name ($q)").getOrElse(name)
}

/** One scripted step of a raid: `millis` after the raid starts, an optional server
 *  broadcast, and the creatures that appear at that step. Some steps are
 *  creature-only (no broadcast). */
final case class RaidEvent(millis: Long, message: Option[String], creatures: Vector[RaidCreature])

/** One raid type: its name, where it happens, a wiki link, and its full timed
 *  script. The script is deterministic, so once a raid's start time is known every
 *  remaining broadcast can be timed from the catalogue alone. */
final case class RaidType(
    id: Int,
    name: String,
    area: Option[String],
    subarea: Option[String],
    link: Option[String],
    events: Vector[RaidEvent]
) {

  /** The broadcasts (steps that carry a message), in fire order — this is the
   *  sequence the raids channel drips out. Indices here are stable and are what
   *  delivery dedupes each line on. */
  lazy val broadcasts: Vector[RaidEvent] =
    events.filter(_.message.exists(_.nonEmpty)).sortBy(_.millis)

  /** Every creature across the whole raid, de-duplicated by name in first-appearance
   *  order — for the "creatures" line of the imminent-raid embed. */
  lazy val creatures: Vector[RaidCreature] = {
    val seen = scala.collection.mutable.LinkedHashMap.empty[String, RaidCreature]
    events.sortBy(_.millis).flatMap(_.creatures).foreach(c => if (!seen.contains(c.name)) seen.put(c.name, c))
    seen.values.toVector
  }
}

/** The bundled raid-type catalogue (`resources/raidtypes.json`).
 *
 *  The Observer feed reports a raid only as a numeric id, an area and a stage, so
 *  this catalogue supplies its name, location, creatures, wiki link and the full
 *  timed broadcast script for that id. The raids channel uses it to post an
 *  imminent-raid summary on first sighting and then drip the raid's broadcasts at
 *  their real timing — all from the catalogue, without polling the feed again.
 *
 *  Parsed once, lazily. A missing or malformed file degrades to an empty catalogue
 *  with a warning rather than stopping the bot at boot: raids then fall back to
 *  their area and stage. Same treatment, and reasoning, as
 *  [[com.tibiabot.statistics.BossCatalogue]]. */
object RaidTypeCatalog extends StrictLogging {

  private val ResourcePath = "/raidtypes.json"

  /** Keyed by the raid type id the feed reports each raid as. */
  lazy val byId: Map[Int, RaidType] = load()

  def get(id: Int): Option[RaidType] = byId.get(id)

  private def load(): Map[Int, RaidType] = {
    import io.circe.Decoder

    val creatureDecoder: Decoder[RaidCreature] = Decoder.instance { c =>
      for {
        name <- c.downField("name").as[String]
        qualifier <- c.downField("qualifier").as[Option[String]]
      } yield RaidCreature(name.trim, qualifier.map(_.trim).filter(_.nonEmpty))
    }
    val eventDecoder: Decoder[RaidEvent] = Decoder.instance { c =>
      for {
        millis <- c.downField("millis").as[Option[Long]].map(_.getOrElse(0L))
        message <- c.downField("message").as[Option[String]]
        creatures <- c.downField("creatures").as[Option[List[RaidCreature]]](
          Decoder.decodeOption(Decoder.decodeList(creatureDecoder))).map(_.getOrElse(Nil))
      } yield RaidEvent(millis, message.map(_.trim).filter(_.nonEmpty), creatures.toVector)
    }
    val typeDecoder: Decoder[RaidType] = Decoder.instance { c =>
      for {
        id <- c.downField("id").as[Int]
        name <- c.downField("name").as[String]
        area <- c.downField("area").as[Option[String]]
        subarea <- c.downField("subarea").as[Option[String]]
        link <- c.downField("link").as[Option[String]]
        events <- c.downField("events").as[Option[List[RaidEvent]]](
          Decoder.decodeOption(Decoder.decodeList(eventDecoder))).map(_.getOrElse(Nil))
      } yield RaidType(id, name.trim, area.map(_.trim).filter(_.nonEmpty),
        subarea.map(_.trim).filter(_.nonEmpty), link.map(_.trim).filter(_.nonEmpty), events.toVector)
    }

    val parsed = for {
      raw <- Try {
        val stream = Option(getClass.getResourceAsStream(ResourcePath))
          .getOrElse(throw new RuntimeException(s"$ResourcePath not found on the classpath"))
        val source = Source.fromInputStream(stream, "UTF-8")
        try source.mkString finally source.close()
      }.toEither.left.map(_.getMessage)
      json <- parse(raw).left.map(_.getMessage)
      types <- json.hcursor.downField("raidTypes").as[List[RaidType]](
        Decoder.decodeList(typeDecoder)).left.map(_.getMessage)
    } yield types

    parsed match {
      case Right(types) =>
        val map = types.map(t => t.id -> t).toMap
        logger.info(s"Loaded ${map.size} raid types from $ResourcePath")
        map
      case Left(error) =>
        logger.warn(s"Could not load the raid-type catalogue from $ResourcePath — " +
          s"raids will show their area and stage without a name: $error")
        Map.empty
    }
  }
}
