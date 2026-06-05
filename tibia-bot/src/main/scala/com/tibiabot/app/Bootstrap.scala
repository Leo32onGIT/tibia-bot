package com.tibiabot.app

import net.dv8tion.jda.api.{JDA, JDABuilder}

/** Startup wiring for the Discord session, kept out of BotApp's body. */
object Bootstrap {

  /** Build the JDA session with the given listeners and block until it is ready. */
  def buildReadyJda(token: String, listeners: AnyRef*): JDA = {
    val jda = JDABuilder.createDefault(token)
      .addEventListeners(listeners: _*)
      .build()
    jda.awaitReady()
    jda
  }
}
