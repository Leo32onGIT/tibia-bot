package com.tibiabot.presentation

/** Bars drawn out of custom emoji.
 *
 *  Discord leaves a gap between two adjacent custom emoji, so a bar cannot be
 *  tiled from a single repeating piece — it reads as a run of separate blocks.
 *  What makes it look like one object instead is rounding only the ends, which
 *  is why every colour needs three shapes: a start, a middle and an end.
 *
 *  Pure and Config-free: the six emoji are passed in as a lookup, the same way
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

  /** A run split between two colours, for "us against them".
   *
   *  `left` and `right` are the two figures; the split is where the day was
   *  won. Neither side is allowed to vanish entirely while it has anything at
   *  all — a 31–1 day still shows one red block, because a bar that reads as a
   *  clean sweep when somebody did die is a lie. */
  def split(left: Int, right: Int, emoji: ((String, String)) => String,
            segments: Int = Segments): String = {
    val total = left + right
    val leftSegments =
      if (total <= 0) segments / 2
      else clamp(math.round(left.toDouble / total * segments).toInt, if (left > 0) 1 else 0,
        if (right > 0) segments - 1 else segments)
    run(segments, index => if (index < leftSegments) "green" else "red", emoji)
  }

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
