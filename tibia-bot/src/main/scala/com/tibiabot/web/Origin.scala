package com.tibiabot.web

/** Assembling a scheme and authority out of settings that may be blank.
 *
 *  Its own object rather than a private helper on `Config` because that one
 *  cannot be loaded without a deployment's environment behind it, and the rule
 *  this encodes is worth pinning by a test: a blank setting interpolated after
 *  `https://` produces a string that still looks like a URL, and a link
 *  built that way is wrong in a way nothing rejects. It is how `https://dashboard`
 *  came to be pinned in every guild run by a bot that serves no dashboard.
 */
object Origin {

  /** The first of these to actually name a host, normalised.
   *
   *  `base` is an origin already — a configured override, scheme included. The
   *  two after it are bare domains, tolerated with a scheme or a trailing slash
   *  on them because they are typed into an env file by hand. The result never
   *  ends in a slash, so callers can append a path without thinking about it.
   *
   *  Falling through all three is not possible for the caller that matters:
   *  `fallbackDomain` has a real default in `discord.conf` precisely so there is
   *  always an address to give somebody. An empty one still yields `https://`,
   *  which is the shape of the original fault — that is left visible rather than
   *  papered over, because inventing a host here would hide a missing default.
   */
  def of(base: String, domain: String, fallbackDomain: String): String = {
    val configured = base.trim.stripSuffix("/")
    if (configured.nonEmpty) configured
    else List(domain, fallbackDomain).map(host).find(_.nonEmpty) match {
      case Some(found) => s"https://$found"
      case None        => "https://"
    }
  }

  /** A domain as written into configuration, reduced to the authority: people
   *  paste the whole address they know, and `https://https://x/` is not a host. */
  private def host(domain: String): String =
    domain.trim.stripPrefix("https://").stripPrefix("http://").stripSuffix("/")
}
