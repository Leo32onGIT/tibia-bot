package com.tibiabot
package tibiadata

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** In-process latency/throughput probe for outbound TibiaData requests.
 *
 *  Every outbound request records its round-trip (millis) bucketed by host;
 *  once per window we log per-host p50/p95/p99/max plus effective req/sec, so
 *  the akka host-connection-pool `max-connections` can be sized from real
 *  numbers rather than guessed. Little's law: to sustain R req/sec at latency
 *  L seconds you need ~R*L concurrent connections — we print that figure too.
 *
 *  A singleton on purpose: there is one TibiaDataClient per world (~40 in prod),
 *  so per-instance metrics would fragment into 40 useless summaries. All clients
 *  feed this one accumulator and the first to start wires the single reporter.
 *
 *  CAVEAT: latency is measured to response-headers (singleRequest's Future), not
 *  to full entity consumption. The pool holds a connection until the entity is
 *  drained + parsed, so the recorded latency understates true connection-hold
 *  time and the suggested connection count is a *floor*. The gap scales with
 *  body size: character pages are ~2KB (gap small), but the larger endpoints
 *  (creatures ~93KB, guild ~76KB, world ~33KB) can roughly double the effective
 *  latency — round the suggestion up accordingly (≈2x for those) when sizing.
 *
 *  Observational only; remove once max-connections is tuned. */
object RequestMetrics extends StrictLogging {
  private val windowSeconds = 60
  private val samplesByHost = new ConcurrentHashMap[String, ConcurrentLinkedQueue[java.lang.Long]]()
  private val started = new AtomicBoolean(false)

  /** Record one completed request's round-trip latency against its host. */
  def record(host: String, millis: Long): Unit =
    samplesByHost
      .computeIfAbsent(host, _ => new ConcurrentLinkedQueue[java.lang.Long]())
      .add(millis)

  /** Idempotent: the first TibiaDataClient to construct starts the single
   *  shared reporter so the per-world clients all roll up into one summary. */
  def ensureReporting(system: ActorSystem): Unit =
    if (started.compareAndSet(false, true))
      system.scheduler.scheduleWithFixedDelay(windowSeconds.seconds, windowSeconds.seconds)(
        () => report())(system.dispatcher)

  /** Drain the window per host and log a summary. Samples added mid-drain simply
   *  roll into the next window. */
  private def report(): Unit =
    samplesByHost.asScala.foreach { case (host, queue) =>
      val buf = mutable.ArrayBuffer.empty[Long]
      var x = queue.poll()
      while (x != null) { buf += x.longValue; x = queue.poll() }
      if (buf.nonEmpty) {
        val sorted = buf.toArray.sorted
        val n = sorted.length
        def pct(p: Double): Long = sorted(math.min(n - 1, math.round(p * (n - 1)).toInt))
        val rps = n.toDouble / windowSeconds
        val p95 = pct(0.95)
        val littlesLawConns = math.ceil(rps * p95 / 1000.0).toInt
        logger.info(
          f"[req-probe] host=$host n=$n rps=$rps%.1f/s " +
            f"p50=${pct(0.50)}ms p95=$p95%dms p99=${pct(0.99)}ms max=${sorted(n - 1)}ms " +
            f"mean=${sorted.sum.toDouble / n}%.0fms => max-connections to sustain this rate @p95 >= $littlesLawConns")
      }
    }
}
