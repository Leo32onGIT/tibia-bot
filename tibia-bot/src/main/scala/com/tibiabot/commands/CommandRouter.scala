package com.tibiabot.commands

/**
 * Maps a slash-command path to its handler. Keeps the dispatch table in one place
 * and free of JDA types (the event type `E` is a parameter), so routing is unit-testable.
 *
 * Paths come from [[CommandPath]] and are as deep as the interaction was — but a
 * handler need not be registered that deep. Dispatch falls back to the longest
 * registered prefix, so `"hunted player"` reaches a handler registered at
 * `"hunted"`, and `"settings neutral levels"` reaches one registered at
 * `"settings neutral"`. That is what lets a handler own a whole subtree and keep
 * reading the subcommand itself, which is how every one of them is already written.
 *
 * Where prefixes overlap, the most specific wins: registering both `"settings"`
 * and `"settings neutral"` sends the neutral subtree to the latter.
 */
final class CommandRouter[E](handlers: Map[String, E => Unit]) {

  /** Paths this router has a handler registered at — not the paths it can
   *  serve, which includes anything extending one of these. */
  def registeredPaths: Set[String] = handlers.keySet

  /** True when `path`, or some prefix of it, has a handler — the question a
   *  registered command tree is checked against, without invoking anything. */
  def handles(path: String): Boolean = handlerFor(path).isDefined

  /** Dispatch `path` to its handler, or to the one registered at its longest
   *  prefix. Returns false if nothing is registered for it. */
  def route(path: String, event: E): Boolean =
    handlerFor(path) match {
      case Some(handler) => handler(event); true
      case None          => false
    }

  /** `inits` yields the whole path first and then progressively shorter
   *  prefixes, so the first hit is by construction the most specific one. */
  private def handlerFor(path: String): Option[E => Unit] =
    path.split(' ').toList.inits
      .map(_.mkString(" "))
      .collectFirst { case key if key.nonEmpty && handlers.contains(key) => handlers(key) }
}
