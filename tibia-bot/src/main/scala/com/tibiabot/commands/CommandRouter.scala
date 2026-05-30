package com.tibiabot.commands

/**
 * Maps a slash-command name to its handler. Keeps the dispatch table in one place
 * and free of JDA types (the event type `E` is a parameter), so routing is unit-testable.
 */
final class CommandRouter[E](handlers: Map[String, E => Unit]) {

  /** Names this router knows how to handle. */
  def commandNames: Set[String] = handlers.keySet

  /** Dispatch to the handler for `name`. Returns false if no handler is registered. */
  def route(name: String, event: E): Boolean =
    handlers.get(name) match {
      case Some(handler) => handler(event); true
      case None          => false
    }
}
