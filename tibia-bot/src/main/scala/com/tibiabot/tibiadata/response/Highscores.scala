package com.tibiabot.tibiadata.response

case class Highscores(
  category: String,
  highscore_age: Double,
  highscore_list: Option[List[HighscoresList]],
  highscore_page: HighscoresPage,
  vocation: String,
  world: String
)

case class HighscoresList(
  level: Double,
  name: String,
  rank: Double,
  value: Double,
  vocation: String,
  world: String
)

case class HighscoresPage(
  current_page: Double,
  total_pages: Double,
  total_records: Double
)


case class HighscoresResponse(
  highscores: Highscores,
  information: Information
)
