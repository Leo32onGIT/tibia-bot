package com.tibiabot.presentation

/** Death-notification thumbnails for deaths that have no sensible creature image.
 *
 *  Tibia environmental/field deaths arrive from the API with the damage type as
 *  the killer name (e.g. `drowning`, `death`); for those we show an effect
 *  animation instead of looking up a (nonexistent) creature image. Player kills
 *  (`pvp`) and pure suicides are decided by the surrounding death logic, not by a
 *  killer name, so they are exposed as named constants rather than via [[thumbnail]].
 *
 *  [[thumbnail]] returns `None` for anything else — a regular creature or a player
 *  kill, or an environmental type we have no animation for — and the caller then
 *  falls back to the creature image. So an unmapped killer name is never worse
 *  than the previous behaviour; it can only upgrade a broken thumbnail to an effect.
 */
object DeathEffect {

  private def resource(file: String): String =
    s"https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/$file"

  /** Animation for a player kill — set at the kill site, since "pvp" is a
   *  classification (killer.player), never a killer name the API sends. */
  val pvp: String = resource("Phantasmal_Ooze.gif")

  /** Animation for a pure suicide / assists-only death (empty killer list). */
  val suicide: String = resource("Ghost_Smoke_Effect.gif")

  /** Environmental damage types we have an animation for, keyed by the lowercased
   *  killer name the API reports for such deaths. */
  private val byDamageType: Map[String, String] = Map(
    "death"      -> resource("Death_Effect.gif"),
    "ice"        -> resource("Ice_Explosion_Effect.gif"),
    "drowning"   -> resource("Reaper_Effect.gif"),
    "life drain" -> resource("Red_Sparkles_Effect.gif")
  )

  /** Damage-type killer names we have an effect animation for. Every one must be a
   *  recognised substance death source (`domain.Killers.substanceSources`); a
   *  DeathEffectSpec check guards against a key that could never match a real death. */
  def mappedDamageTypes: Set[String] = byDamageType.keySet

  /** The effect animation for an environmental death by `killerName`, or `None`
   *  for a creature/player kill (caller falls back to the creature image). */
  def thumbnail(killerName: String): Option[String] =
    byDamageType.get(killerName.toLowerCase)
}
