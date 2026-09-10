package com.tibiabot.presentation

/** Bars drawn out of custom emoji.
 *
 *  Discord leaves a gap between two adjacent custom emoji, so a bar cannot be
 *  tiled from a single repeating piece — it reads as a run of separate blocks.
 *  What makes it look like one object instead is rounding only the ends, which
 *  is why every colour needs three shapes: a start, a middle and an end.
 *
 *  Pure and Config-free: the nine emoji are passed in as a lookup, the same way
 *  [[SkillEmojis]] and [[GuildIcons]] keep their configured strings out of the
 *  code that decides what to draw. `Config.barEmoji` is that lookup in
 *  production; a test can hand over anything readable.
 */
object Bars {

  /** How many segments a bar is drawn from.
   *
   *  Twelve. Each emoji renders about 20px wide, so twelve is roughly 250px —
   *  comfortable inside an embed on a desktop and still short of wrapping on a
   *  phone. Longer stops being a bar and starts being a line of blocks. */
  val Segments: Int = 12

  /** How a world's own shape turns a day's frags into a bar.
   *
   *  Both halves are read off the same thing — who was actually online there —
   *  so a world is measured against itself rather than against Antica.
   *
   *  `ceiling` is what a full bar is worth, in deaths of an ordinary local
   *  character. `referenceLevel` is what "ordinary local character" means: the
   *  average level of the people on that world, so a level 8 is negligible on a
   *  mature server and a real kill on a week-old one. Deriving it from the
   *  population rather than from the day's victims is the whole point — a
   *  reference taken from the victims would collapse to their level and draw a
   *  full bar for a day of killing nobodies. */
  final case class Scale(ceiling: Int, referenceLevel: Double)

  object Scale {
    /** What a world with no samples yet gets, which is every world on its first
     *  day. Slightly wrong for one morning beats no bar at all. */
    val Default: Scale = Scale(DefaultCeiling, DefaultLevel)

    def forWorld(averageOnline: Option[Double], averageLevel: Option[Double]): Scale =
      Scale(
        averageOnline.map(ceilingFor).getOrElse(DefaultCeiling),
        averageLevel.filter(_ >= 1).getOrElse(DefaultLevel.toDouble))
  }

  /** A death's worth, against the level an ordinary character on that world is.
   *
   *  A side whose levels add to nothing but whose count does not is holding rows
   *  filed before `victim_level` existed; each counts as one ordinary death
   *  rather than disappearing, since the death did happen and only the level is
   *  unknown. */
  def weigh(levels: Long, deaths: Int, referenceLevel: Double): Double =
    if (levels > 0) levels / math.max(1.0, referenceLevel) else deaths.toDouble

  /** The level to reckon against where the world's own average is not known. */
  val DefaultLevel: Int = 150

  /** The ceiling to use where the world's own figure is not known yet.
   *
   *  A world's first day has no samples to average, and a bar that is slightly
   *  wrong for one morning is worth more than no bar at all. Thirty is about a
   *  busy day on a mid-size world, so the first post lands in the right region
   *  rather than at either extreme. */
  val DefaultCeiling: Int = 30

  /** How many players online one death is worth, when the ceiling is derived
   *  from a world's population. Ten: on Antica that is a ceiling near 110 and a
   *  bar that keeps growing to about ninety deaths, while a dying world fills
   *  at six — which is the point, since six frags there is the whole day. */
  val OnlinePerCeiling: Int = 10

  /** The ceiling for a world with `averageOnline` players on it that day. */
  def ceilingFor(averageOnline: Double): Int =
    math.max(2, math.round(averageOnline / OnlinePerCeiling).toInt)

  /** A run split between two colours, with the rest left as track.
   *
   *  Two questions in one shape. How much of the bar is coloured says how big
   *  the day was, against `ceiling`; where the colour changes says who won.
   *  Before this the bar was always full, so 10–0 and 30–0 drew identically and
   *  a 0–0 day split down the middle — a dead-even war on a day nobody died.
   *
   *  Logarithmic rather than linear, because the interesting range is not even
   *  close to uniform: a handful of frags is a real day and ought to look like
   *  one, while the difference between forty and fifty is worth almost nothing.
   *  A linear fill spends most of the bar on totals nobody ever reaches.
   *
   *  Neither side is allowed to vanish entirely while it has anything at all —
   *  a 31–1 day still shows one red block, because a bar that reads as a clean
   *  sweep when somebody did die is a lie. */
  def split(left: Double, right: Double, emoji: ((String, String)) => String,
            ceiling: Int = DefaultCeiling, segments: Int = Segments): String = {
    val total = left + right
    val filled =
      if (total <= 0) 0
      else clamp(scaled(total, ceiling, segments), 1, segments)
    val leftSegments =
      if (total <= 0) 0
      else clamp(math.round(left / total * filled).toInt, if (left > 0) 1 else 0,
        filled - (if (right > 0) 1 else 0))
    run(segments, index =>
      if (index < leftSegments) "green" else if (index < filled) "red" else "empty", emoji)
  }

  /** Where `total` lands on a log scale that reaches `segments` at `ceiling`.
   *
   *  The ceiling is floored at two so the logarithm has something to divide by
   *  on a world with almost nobody on it; there, one frag nearly fills the bar,
   *  which is the honest answer rather than an accident. */
  private def scaled(total: Double, ceiling: Int, segments: Int): Int =
    math.round(math.log1p(total) / math.log1p(math.max(2, ceiling).toDouble) * segments).toInt

  /** The ends are rounded and the middles are not, which is what makes a row of
   *  separate emoji read as one bar. */
  private def run(segments: Int, colourAt: Int => String, emoji: ((String, String)) => String): String =
    (0 until segments).map { index =>
      val shape =
        if (index == 0) "start"
        else if (index == segments - 1) "end"
        else "mid"
      emoji((colourAt(index), shape))
    }.mkString

  private def clamp(value: Int, low: Int, high: Int): Int = math.max(low, math.min(high, value))
}
