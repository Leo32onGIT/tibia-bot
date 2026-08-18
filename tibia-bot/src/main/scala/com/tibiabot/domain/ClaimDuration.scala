package com.tibiabot.domain

/** Reading a length of time out of whatever somebody typed.
 *
 *  Every door into the claim ceiling goes through here — the dashboard's own
 *  field, the Discord modal, and anything relayed between bots — so `2h` means
 *  the same thing in all of them. It lived as `text.toInt` in two places before,
 *  which meant the dashboard and Discord disagreed about `2h` (one refused it,
 *  the other never saw it) and neither could read `1h30`.
 *
 *  ==The bare number==
 *  A number on its own is hours at or below [[HoursCutoff]] and minutes above
 *  it. That is a guess, and it is the right one: nobody sets a ceiling of two
 *  minutes, and `2` is overwhelmingly two hours. The cutoff is 24 because that
 *  is also the longest ceiling allowed, so every value expressible in hours is
 *  on the hours side of it and every value above is unambiguous as minutes.
 *
 *  The cost is that a bare `20` reads as twenty hours rather than twenty
 *  minutes. Anybody who wants the short one says `20m`, which is exactly why
 *  the suffixes are accepted.
 */
object ClaimDuration {

  /** At or below this, a bare number is read as hours. */
  val HoursCutoff: Int = 24

  private val hourWord = "h|hr|hrs|hour|hours"
  private val minuteWord = "m|min|mins|minute|minutes"

  // Longest first: `1h30m` has to be tried before `1h`, or the minutes are lost.
  private val HoursAndMinutes = s"^(\\d+)(?:$hourWord)(\\d+)(?:$minuteWord)?$$".r
  private val Hours = s"^(\\d+(?:\\.\\d+)?)(?:$hourWord)$$".r
  private val Minutes = s"^(\\d+)(?:$minuteWord)$$".r
  private val Bare = "^(\\d+)$".r

  /** Minutes, or `None` for "nothing was typed" — which every caller reads as
   *  "follow the server". `Left` is text that could not be read at all, which is
   *  refused rather than guessed at: taking `2 hrs pls` for two hours is the
   *  kind of helpfulness that eventually reads `2 days` as two minutes.
   *
   *  Whitespace and case are thrown away first, so `2 H` and `2h` are one thing.
   */
  def parse(text: String): Either[String, Option[Int]] = {
    val cleaned = Option(text).getOrElse("").replaceAll("\\s", "").toLowerCase
    if (cleaned.isEmpty) Right(None)
    else cleaned match {
      case HoursAndMinutes(h, m) => combine(digits(h), digits(m))
      // Rounded rather than truncated, so `1.75h` is 105 and not 104. Decimal
      // hours are worth accepting because half an hour is a thing people write
      // as `1.5h` at least as readily as `90m`.
      case Hours(h) =>
        val asDouble = scala.util.Try(h.toDouble).getOrElse(Double.MaxValue)
        if (asDouble.isInfinite || asDouble > Int.MaxValue) TooLong
        else whole(Math.round(asDouble * 60))
      case Minutes(m) => digits(m).fold[Either[String, Option[Int]]](TooLong)(whole)
      case Bare(n) => digits(n) match {
        case None                              => TooLong
        case Some(value) if value <= HoursCutoff => whole(value * 60)
        case Some(value)                       => whole(value)
      }
      case _ => Left("Try minutes, or something like 2h, 90m or 1h30.")
    }
  }

  /** A run of digits as a Long, or None when there are simply too many of them.
   *  `toLong` throws on a twenty-digit number rather than saturating, and a
   *  pasted number is an ordinary thing to have to answer rather than a fault. */
  private def digits(text: String): Option[Long] = scala.util.Try(text.toLong).toOption

  private def combine(hours: Option[Long], mins: Option[Long]): Either[String, Option[Int]] =
    (hours, mins) match {
      case (Some(h), Some(m)) => whole(h * 60 + m)
      case _                  => TooLong
    }

  private val TooLong: Either[String, Option[Int]] =
    Left("That is longer than any claim could be.")

  /** Guards the conversion above rather than the range, which belongs to whoever
   *  is being configured — a ceiling and a reminder do not have the same bounds.
   *  What is refused here is only what cannot be an Int at all, so a pasted
   *  twenty-digit number is an answer rather than an overflow. */
  private def whole(minutes: Long): Either[String, Option[Int]] =
    if (minutes > Int.MaxValue) TooLong else Right(Some(minutes.toInt))
}
