package com.tibiabot.tracking

/** One counter's figures at a point in time. `perHour` is an *observed* count
 *  over the trailing hour, not `perSecond * 3600` — an extrapolation would
 *  claim a full hour of history seconds after boot. See
 *  [[ApiCallSnapshot.observedSeconds]] for how much history actually backs it. */
final case class ApiCallStats(total: Long, perSecond: Double, perHour: Long)

/** A source's throughput plus its breakdowns.
 *
 *  `dimensions` is keyed by dimension name then by value — e.g.
 *  `"endpoint" -> ("/v4/character" -> stats)`. A source can carry several
 *  independent dimensions (TibiaData counts every call once under `endpoint`
 *  and once under `status`), so the values within one dimension sum to the
 *  overall total but values across dimensions do not.
 *
 *  A dimension supplied on only *some* calls is the exception, and a deliberate
 *  one: it sums to that subset instead. Discord's `ratelimited` (see
 *  `app.Bootstrap`) is recorded only for 429s, because "which call was
 *  throttled" is a question the whole-total dimensions cannot answer between
 *  them. A consumer that shares such a dimension against the overall total gets
 *  a fraction of all traffic, which is meaningful; one that shares it within
 *  the dimension gets a fraction of the subset, which is also meaningful. Both
 *  are correct, so the caller has to say which it meant.
 *
 *  `observedSeconds` is how long this counter has actually been collecting,
 *  capped at the hour window. Below 3600 the `perHour` figure is a partial
 *  hour rather than a rate, and the dashboard says so rather than showing a
 *  confident number that is really "everything since boot". */
final case class ApiCallSnapshot(
  total: Long,
  perSecond: Double,
  perHour: Long,
  observedSeconds: Long,
  history: Vector[Double],
  dimensions: Map[String, Map[String, ApiCallStats]]
)

/** A count of events per second over a trailing hour, in a fixed ring of
 *  one-second buckets.
 *
 *  Fixed-size and allocation-free once built: recording is an increment, and
 *  the only work on a time step is zeroing the buckets that just aged out.
 *  That matters because the hottest caller here is one record per TibiaData
 *  response, which is hundreds a second across all worlds.
 *
 *  A bucket ages out by being overwritten with zero as the clock passes it, so
 *  a counter that goes quiet for a while reports zero rather than stale counts
 *  from an hour ago. All access is synchronised: writers are akka dispatcher
 *  and JDA threads, the reader is the dashboard's HTTP thread. */
private[tracking] final class RollingCounter(now: () => Long) {
  import RollingCounter.BucketCount

  private val buckets = new Array[Int](BucketCount)
  private val startedAtSecond: Long = now() / 1000
  private var lastSecond: Long = startedAtSecond
  private var cumulative: Long = 0L

  private def slot(second: Long): Int = (((second % BucketCount) + BucketCount) % BucketCount).toInt

  /** Zero every bucket the clock has passed since the last touch, so reads
   *  never pick up a value that wrapped around from an hour ago. Caller must
   *  hold the lock. */
  private def advance(): Unit = {
    val current = now() / 1000
    if (current > lastSecond) {
      val stale = math.min(BucketCount.toLong, current - lastSecond)
      var i = 1L
      while (i <= stale) {
        buckets(slot(lastSecond + i)) = 0
        i += 1
      }
      lastSecond = current
    }
  }

  def record(): Unit = synchronized {
    advance()
    buckets(slot(lastSecond)) += 1
    cumulative += 1L
  }

  /** Total over the `seconds` most recent buckets, inclusive of the current
   *  (still filling) one. Caller must hold the lock. */
  private def sumLast(seconds: Int): Long = {
    advance()
    var sum = 0L
    var i = 0
    while (i < seconds) {
      sum += buckets(slot(lastSecond - i))
      i += 1
    }
    sum
  }

  def total: Long = synchronized { cumulative }

  /** Averaged over a full minute rather than read off the current bucket: a
   *  single second is far too noisy to show as a live figure, and the current
   *  bucket is only partly filled anyway. */
  def perSecond: Double = synchronized { sumLast(RollingCounter.PerSecondWindow) / RollingCounter.PerSecondWindow.toDouble }

  def perHour: Long = synchronized { sumLast(BucketCount) }

  /** How long this counter has been collecting, capped at the hour window. */
  def observedSeconds: Long = synchronized {
    advance()
    math.min(BucketCount.toLong, lastSecond - startedAtSecond)
  }

  /** `points` averages of `secondsPer` seconds each, oldest first — the shape
   *  the dashboard's sparkline draws. Values are per-second rates, so the
   *  history and [[perSecond]] are in the same unit and a sparkline can be read
   *  against the headline figure. */
  def history(points: Int, secondsPer: Int): Vector[Double] = synchronized {
    advance()
    val out = Vector.newBuilder[Double]
    var p = points - 1
    while (p >= 0) {
      var sum = 0L
      var i = 0
      while (i < secondsPer) {
        sum += buckets(slot(lastSecond - (p.toLong * secondsPer + i)))
        i += 1
      }
      out += sum.toDouble / secondsPer
      p -= 1
    }
    out.result()
  }

  def stats: ApiCallStats = ApiCallStats(total, perSecond, perHour)
}

private[tracking] object RollingCounter {
  /** One hour of one-second buckets — the longest window anything asks for. */
  val BucketCount = 3600
  val PerSecondWindow = 60
}

/** Outbound API call counters for one upstream (Discord, TibiaData), feeding
 *  the dashboard's throughput panel.
 *
 *  Each call is recorded once against the overall counter and once under each
 *  tag supplied, so one `record("endpoint" -> "/v4/world", "status" -> "200")`
 *  populates both breakdowns without double-counting the total. A dimension
 *  named on every call therefore sums to the overall total, which is what lets
 *  the dashboard show a share per row; one named on only some calls sums to
 *  that subset instead — see [[ApiCallSnapshot]].
 *
 *  Unlike [[WorldMetrics]]' fixed 15-minute counters (reset externally on a
 *  timer) and [[com.tibiabot.discord.RateLimitedSender]]'s per-label window
 *  (reset on read), these never need resetting by a caller: the ring buffer
 *  ages values out on its own, so the dashboard can poll as often as it likes
 *  without disturbing anything. */
final class ApiCallMetrics(now: () => Long = () => System.currentTimeMillis()) {

  private val overall = new RollingCounter(now)
  private var dimensions: Map[String, Map[String, RollingCounter]] = Map.empty

  /** Counter for `value` within `dimension`, created on first sight. Caller
   *  must hold the lock.
   *
   *  Past [[ApiCallMetrics.MaxValuesPerDimension]] distinct values, everything
   *  new folds into a single "other" bucket. Every dimension in use today is
   *  naturally bounded (a fixed set of endpoints, HTTP statuses, send labels),
   *  so this should never trigger — it exists so that a tag derived from
   *  something unexpected can't grow this map without bound in a process that
   *  runs for months. */
  private def counterFor(dimension: String, value: String): RollingCounter = {
    val byValue = dimensions.getOrElse(dimension, Map.empty)
    val key =
      if (byValue.contains(value) || byValue.size < ApiCallMetrics.MaxValuesPerDimension) value
      else ApiCallMetrics.OverflowValue
    byValue.get(key) match {
      case Some(counter) => counter
      case None =>
        val created = new RollingCounter(now)
        dimensions = dimensions.updated(dimension, byValue.updated(key, created))
        created
    }
  }

  /** Record one call: once overall, and once under each `dimension -> value`. */
  def record(tags: (String, String)*): Unit = {
    overall.record()
    val counters = synchronized { tags.map { case (d, v) => counterFor(d, v) } }
    counters.foreach(_.record())
  }

  def snapshot(): ApiCallSnapshot = {
    val dims = synchronized { dimensions }
    ApiCallSnapshot(
      total = overall.total,
      perSecond = overall.perSecond,
      perHour = overall.perHour,
      observedSeconds = overall.observedSeconds,
      history = overall.history(ApiCallMetrics.HistoryPoints, ApiCallMetrics.HistoryBucketSeconds),
      dimensions = dims.map { case (dimension, byValue) =>
        dimension -> byValue.map { case (value, counter) => value -> counter.stats }
      }
    )
  }
}

object ApiCallMetrics {
  /** 60 points of 10 seconds — ten minutes of history, matching the interval
   *  the dashboard's own poll produces one point over. */
  val HistoryPoints = 60
  val HistoryBucketSeconds = 10

  val MaxValuesPerDimension = 64
  val OverflowValue = "other"
}
