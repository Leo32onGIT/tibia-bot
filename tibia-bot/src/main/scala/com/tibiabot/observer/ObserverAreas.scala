package com.tibiabot.observer

/** The areas Observer's raids happen in, as `/observer` lists them. */
object ObserverAreas {

  /** Every area the raid catalogue has a raid in, alphabetical: an area with no
   *  raids can't send the raids channel anything, so it isn't listed. */
  lazy val raidAreas: List[String] =
    RaidTypeCatalog.byId.values.flatMap(_.area).filter(_.nonEmpty).toList.distinct.sortBy(_.toLowerCase)

  /** Area ids whose names were seen before Observer's own could be read: its rules
   *  store numbers, and these two were matched to the account's explored areas on
   *  25 Sep 2026. Observer's own names, once they come in with the rules, win. */
  val KnownNames: Map[Int, String] = Map(3 -> "Carlin", 23 -> "Hrodmir")
}
