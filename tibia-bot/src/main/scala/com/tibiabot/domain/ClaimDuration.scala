package com.tibiabot.domain

/** Reading a length of time out of whatever somebody typed.
 *
 *  Every door into the claim system's durations goes through here — the
 *  dashboard field, the Discord modals, anything relayed between bots — so `2h`
 *  means one thing in all of them. As `text.toInt` in two places, the dashboard
 *  and Discord disagreed about `2h` and neither could read `1h30`.
 *
 *  ==The bare number==
 *  A suffixed length means the same everywhere. A number on its own cannot,
 *  because the boxes it is typed into do not agree about what it has always
 *  meant, so there are two doors and the caller picks by which box it is
 *  reading:
 *
 *   - [[parse]], for a claim ceiling, reads a bare number at or below
 *     [[HoursCutoff]] as hours. A guess, and the right one there: nobody sets a
 *     ceiling of two minutes. The cutoff is 24 because that is also the longest
 *     ceiling allowed, so everything expressible in hours is below it and
 *     everything above it is unambiguous as minutes.
 *   - [[parseMinutes]], for every box that has always been minutes, reads a
 *     bare number as minutes.
 *
 *  The cost of the first is that a bare `20` reads as twenty hours; the cost of
 *  having both is that the two doors disagree about exactly that number. The
 *  alternative was reinterpreting durations people have been typing for as long
 *  as the boxes have existed, which is worse than an unsuffixed number meaning
 *  what it has always meant in the box it is typed into. Anybody who wants to
 *  be sure says `20m`, which is why the suffixes are accepted at all. */
object ClaimDuration {

  /** At or below this, a bare number is read as hours — [[parse]] only. */
  val HoursCutoff: Int = 24

  private val hourWord = "h|hr|hrs|hour|hours"
  private val minuteWord = "m|min|mins|minute|minutes"

  // Longest first: `1h30m` has to be tried before `1h`, or the minutes are lost.
  private val HoursAndMinutes = s"^(\\d+)(?:$hourWord)(\\d+)(?:$minuteWord)?$$".r
  private val Hours = s"^(\\d+(?:\\.\\d+)?)(?:$hourWord)$$".r
  private val Minutes = s"^(\\d+)(?:$minuteWord)$$".r
  private val Bare = "^(\\d+)$".r

  /** Minutes, reading a bare number as hours — the claim-ceiling door.
   *
   *  `None` is "nothing was typed", which every caller here reads as "follow the
   *  server". `Left` is text that could not be read at all, which is refused
   *  rather than guessed at: taking `2 hrs pls` for two hours is the kind of
   *  helpfulness that eventually reads `2 days` as two minutes.
   *
   *  Whitespace and case are thrown away first, so `2 H` and `2h` are one thing.
   */
  def parse(text: String): Either[String, Option[Int]] = read(clean(text), bareIsHours = true)

  /** Minutes, reading a bare number as minutes — the door for every box that has
   *  always been one: how long a slot runs, a hunt's total length, the server's
   *  default and maximum, a stamina budget, a handover window.
   *
   *  `2h`, `1h30` and `90m` mean here exactly what they mean in [[parse]]. A
   *  bare `20` is the whole of the difference, and it is twenty minutes because
   *  that is what it has meant in these boxes since they existed.
   *
   *  A leading `-` is accepted, for the one box that hands stamina back rather
   *  than spending it. What a negative means is the caller's business: the rest
   *  refuse it downstream against their own range, which is a better answer than
   *  being told it is not a number. */
  def parseMinutes(text: String): Either[String, Option[Int]] = clean(text) match {
    case ""                                 => Right(None)
    // A minus sign and nothing to apply it to. Left rather than "nothing typed",
    // which a required box would otherwise report as an empty one.
    case "-"                                => Unreadable
    case negative if negative.startsWith("-") =>
      read(negative.drop(1), bareIsHours = false).map(_.map(minutes => -minutes))
    case positive                           => read(positive, bareIsHours = false)
  }

  private def clean(text: String): String =
    Option(text).getOrElse("").replaceAll("\\s", "").toLowerCase

  /** The grammar itself, which both doors share. Only what an unsuffixed number
   *  means is theirs to decide. */
  private def read(cleaned: String, bareIsHours: Boolean): Either[String, Option[Int]] =
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
        case None                                                => TooLong
        case Some(value) if bareIsHours && value <= HoursCutoff => whole(value * 60)
        case Some(value)                                         => whole(value)
      }
      case _ => Unreadable
    }

  private val Unreadable: Either[String, Option[Int]] =
    Left("Try minutes, or something like 2h, 90m or 1h30.")

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
