package com.tibiabot.domain

/** One currently-active mini world change, as the Observer feed reports it:
 *  the world it is on, its short title, and the descriptive body. */
final case class MiniWorldChange(world: String, title: String, body: String)
