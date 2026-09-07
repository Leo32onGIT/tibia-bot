package com.tibiabot.domain

/** What looking a character up on TibiaData actually told us.
 *
 *  Three states, not two. The add/remove commands used to reduce a lookup to a
 *  (name, world, vocation, level) tuple with an empty name meaning "no such
 *  character" — which quietly folded *the request failed* into *the character
 *  does not exist*. Those are different answers: one is about the character and
 *  is worth telling somebody, the other is about the API and means try again.
 *
 *  Roughly 45% of TibiaData's responses are 503s, so the difference is not a
 *  corner case. Adding one name at a time it read as a typo you retyped; adding
 *  a pasted list it would confidently report a chunk of real characters as
 *  nonexistent, which is the sort of wrong that gets believed.
 */
sealed trait PlayerLookup

object PlayerLookup {

  /** The character exists, with the details the add/remove replies render. */
  final case class Found(name: String, world: String, vocation: String, level: Int) extends PlayerLookup

  /** TibiaData answered, and there is no such character. */
  case object NotFound extends PlayerLookup

  /** TibiaData did not answer — after retries. Says nothing about whether the
   *  character exists, so nothing may be added, removed or denied on it. */
  case object Unavailable extends PlayerLookup
}
