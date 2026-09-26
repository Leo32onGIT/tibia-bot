package com.tibiabot.observer

/** The areas Observer's raids happen in, as `/observer` lists them. */
object ObserverAreas {

  /** Every area the raid catalogue has a raid in, alphabetical: an area with no
   *  raids can't send the raids channel anything, so it isn't listed. */
  lazy val raidAreas: List[String] =
    RaidTypeCatalog.byId.values.flatMap(_.area).filter(_.nonEmpty).toList.distinct.sortBy(_.toLowerCase)

  /** Area ids as Observer named them in a linked account's explored areas (its
   *  `name` field, read 27 Sep 2026), so the card is right before any rules have
   *  been set since. The names that come in with the rules win. Quirefang has no
   *  raids in the catalogue, so it isn't listed. */
  val KnownNames: Map[Int, String] =
    Map(3 -> "Carlin", 7 -> "Edron", 11 -> "Quirefang", 23 -> "Hrodmir", 25 -> "Venore")
}
