package com.tibiabot.tibiadata.response

import java.time.LocalDate

case class NewsEntry(
  category: String,
  date: String,  // Format: "YYYY-MM-DD"
  id: Int,
  news: String,
  title: String,
  url: String
)

case class NewsData(
  news: List[NewsEntry]
)

case class NewsResponse(
  information: Information,
  news: NewsData
)

// News ticker structures
case class NewsTickerEntry(
  date: String,  // Format: "YYYY-MM-DD"
  message: String
)

case class NewsTickerData(
  newstickers: List[NewsTickerEntry]
)

case class NewsTickerResponse(
  information: Information,
  newstickers: NewsTickerData
)