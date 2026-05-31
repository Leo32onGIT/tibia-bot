package com.tibiabot.presentation

/** Name formatting shared by the command and boosted paths. */
object Names {

  /** Upper-case the first letter of each space-separated word, leaving the rest
   *  of each word untouched (so "violent beams" -> "Violent Beams"). This is the
   *  exact `split(" ").map(_.capitalize).mkString(" ")` idiom that was repeated
   *  across BotApp and BoostedService. */
  def capitalizeWords(name: String): String =
    name.split(" ").map(_.capitalize).mkString(" ")
}
