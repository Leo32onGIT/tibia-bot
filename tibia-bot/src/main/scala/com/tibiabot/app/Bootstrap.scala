package com.tibiabot.app

import net.dv8tion.jda.api.{JDA, JDABuilder}

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
      .addEventListeners(listeners: _*)
      .build()
    jda.awaitReady()
    jda
  }
}
