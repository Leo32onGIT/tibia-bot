package com.tibiabot.fansiteapi

import com.tibiabot.tibiadata.OriginTimestamp
import com.tibiabot.tibiadata.response.CharacterResponse

import java.time.{Duration, Instant}

/** What differed between the same character as told by two upstreams.
 *
 *  `stable` holds differences no amount of staleness can explain, `volatile` the
 *  ones it can. That split is the point: the two sources are deliberately out of
 *  phase, so one is always looking at a copy up to a window older. A level that
 *  moved, a guild that changed, a death only the newer copy has — all the design
 *  working, not a mapping bug.
 *
 *  A mapping bug looks like a field that cannot drift disagreeing (sex, world), or
 *  the copies disagreeing about a death that was already history when both were
 *  built. Only those are worth waking anybody for. */
final case class CharacterDivergence(
    name: String,
    originSkewSeconds: Long,
    stable: List[String],
    volatile: List[String]
) {
  def isEmpty: Boolean = stable.isEmpty && volatile.isEmpty
  def nonEmpty: Boolean = !isEmpty

  def describe: String = {
    val parts = (stable.map(d => s"!$d") ++ volatile.map(d => s"~$d")).mkString(", ")
    s"'$name' (origins ${originSkewSeconds}s apart): $parts"
  }
}

object CharacterDivergence {

  /** A death both copies were built late enough to know about. A death newer
   *  than the older copy's origin is one that copy simply had not seen yet,
   *  which is the expected consequence of running the sources out of phase. */
  private def settledDeaths(sheet: CharacterResponse, before: Option[Instant]): List[(String, Int)] =
    sheet.character.deaths.getOrElse(Nil).collect {
      case death if before.forall(cutoff => !Instant.parse(death.time).isAfter(cutoff)) =>
        (death.time, death.level.toInt)
    }

  private def diff[A](label: String, left: A, right: A): Option[String] =
    if (left == right) None else Some(s"$label: tibiadata=$left fansite=$right")

  /** Compare the same character as returned by each source.
   *
   *  `tibiaData` is the reference — it is what the bot posts from today — so
   *  every message reads "tibiadata=X fansite=Y" regardless of which side looks
   *  wrong. */
  def between(tibiaData: CharacterResponse, fansite: CharacterResponse): CharacterDivergence = {
    val leftOrigin = OriginTimestamp.of(tibiaData.information)
    val rightOrigin = OriginTimestamp.of(fansite.information)
    val skew = (for (l <- leftOrigin; r <- rightOrigin) yield math.abs(Duration.between(l, r).getSeconds)).getOrElse(0L)
    // Deaths are only comparable up to the older of the two copies.
    val cutoff = for (l <- leftOrigin; r <- rightOrigin) yield if (l.isBefore(r)) l else r

    val left = tibiaData.character.character
    val right = fansite.character.character

    val stable = List(
      diff("name", left.name, right.name),
      diff("sex", left.sex, right.sex),
      diff("world", left.world, right.world),
      diff("residence", left.residence, right.residence),
      diff("settled deaths", settledDeaths(tibiaData, cutoff), settledDeaths(fansite, cutoff))
    ).flatten

    val volatileDiffs = List(
      diff("level", left.level.toInt, right.level.toInt),
      diff("vocation", left.vocation, right.vocation),
      diff("guild", left.guild.map(g => (g.name, g.rank)), right.guild.map(g => (g.name, g.rank))),
      diff("last_login", left.last_login, right.last_login),
      diff("former_names", left.former_names.getOrElse(Nil), right.former_names.getOrElse(Nil)),
      diff("former_worlds", left.former_worlds.getOrElse(Nil), right.former_worlds.getOrElse(Nil))
    ).flatten

    CharacterDivergence(left.name, skew, stable, volatileDiffs)
  }
}
