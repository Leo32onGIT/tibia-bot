package com.tibiabot.app

import net.dv8tion.jda.api.{JDA, JDABuilder}
import com.tibiabot.discord.DiscordApiRoute
import com.tibiabot.tracking.ApiMetrics

import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

/** Startup wiring for the Discord session, kept out of BotApp's body. */
object Bootstrap {

  private def namedThreadFactory(prefix: String): ThreadFactory = {
    val count = new AtomicInteger(0)
    (r: Runnable) => {
      val thread = new Thread(r, s"$prefix-${count.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }

  /** Counts every Discord REST call for the dashboard's throughput panel.
   *
   *  Inside JDA's own HTTP client rather than at an application call site, because
   *  much of this bot's Discord traffic never passes through
   *  [[com.tibiabot.discord.RateLimitedSender]]: death posts and the boosted
   *  server-save post are deliberately unpaced, and command replies never touch
   *  the queue. Counting at the queue would look authoritative and be short.
   *
   *  Does nothing but count and pass through — no buffering, no body inspection,
   *  no failing the request — and the recording is wrapped and swallowed, since a
   *  throwing interceptor would turn a metrics bug into dropped Discord traffic.
   *  Retries and rate-limit re-sends count separately, which is what "how many
   *  requests did we make" should mean. */
  private def countingInterceptor: okhttp3.Interceptor = (chain: okhttp3.Interceptor.Chain) => {
    val request = chain.request()
    val response = chain.proceed(request)
    try {
      val status = response.code()
      val operation = DiscordApiRoute.operation(request.method(), request.url().encodedPath())
      // A third tag carrying the operation again, but only on rate-limited calls.
      // "Which call is being throttled" is not answerable from the other two: each
      // sums to the whole total on its own, so a 10% 429 share beside a 73%
      // PATCH-message share says nothing about whether those 429s were that
      // operation's. Every route sits in a different Discord bucket, so which is
      // at its ceiling decides where the pacing has to change.
      //
      // Added to the same record call rather than made a second one: `record`
      // counts the overall total once per call, so recording twice would
      // inflate the headline throughput by the 429 count and leave the other
      // two dimensions no longer summing to it.
      val tags = Seq("operation" -> operation, "status" -> status.toString)
      ApiMetrics.discord.record((if (status == 429) tags :+ ("ratelimited" -> operation) else tags): _*)
    } catch { case _: Throwable => () }
    response
  }

  /** Build the JDA session with the given listeners and block until it is ready.
   *
   *  By default JDA dispatches every gateway event (slash commands, buttons,
   *  guild join/leave, ...) through a single shared thread. Several listeners
   *  in this bot (channel/role creation in particular) make many sequential
   *  blocking JDA REST calls, so a single slow event can starve dispatch of
   *  every other event — including a different user's slash command, whose
   *  deferReply() then never fires within Discord's 3-second ack window and
   *  shows as "interaction failed". Widening the event pool here is
   *  defense-in-depth; the primary fix is BotListener routing slash-command
   *  handling onto its own dedicated executor so a slow command can't block
   *  new interactions from being dispatched at all. */
  def buildReadyJda(token: String, listeners: AnyRef*): JDA = {
    val eventPool = Executors.newFixedThreadPool(4, namedThreadFactory("jda-event"))
    val jda = JDABuilder.createDefault(token)
      .setEventPool(eventPool, true)
      .setHttpClientBuilder(new okhttp3.OkHttpClient.Builder().addInterceptor(countingInterceptor))
      .addEventListeners(listeners: _*)
      .build()
    jda.awaitReady()
    jda
  }
}
