package com.tibiabot.highscores

import com.tibiabot.domain.{FiledEvent, HighscoreEvent}
import com.tibiabot.persistence.HighscoreRepository
import com.tibiabot.tibiadata.HighscoreCategory
import com.typesafe.scalalogging.StrictLogging

/** What one read of the events table turns into: the batches to announce, and
 *  how far the cursor may advance. */
final case class FeedBatch(
    groups: List[(String, HighscoreCategory, List[HighscoreEvent])],
    cursor: Long
)

object HighscoreFeed extends StrictLogging {

  /** Group a page of filed advances into what this bot should post.
   *
   *  The cursor moves past everything read, not merely everything announced.
   *  Rows for a world this bot does not serve, or a category it no longer
   *  knows, are another bot's business or an older build's — leaving the cursor
   *  behind them would mean re-reading the same rows on every tick forever. */
  def plan(filed: List[FiledEvent], serves: String => Boolean): FeedBatch = {
    val groups = filed
      .filter(row => serves(row.event.world))
      .flatMap(row => HighscoreCategory.fromSlug(row.event.category)
        .filter(_.postsAdvances)
        .map(category => (row.event.world, category, row.event)))
      // Grouped so a snapshot's advances reach a channel as one message rather
      // than one per character, and ordered so two ticks read the same way.
      .groupBy { case (world, category, _) => (world, category) }
      .toList
      .sortBy { case ((world, category), _) => (world, category.slug) }
      .map { case ((world, category), rows) => (world, category, rows.map(_._3)) }

    FeedBatch(groups, if (filed.isEmpty) 0L else filed.map(_.id).max)
  }
}

/** Posts the advances the sweep filed, for the guilds this bot actually serves.
 *
 *  Separate from [[HighscoreService]] because the two answer to different
 *  things. Scraping tibia.com is a fleet-wide job and belongs to one bot: two
 *  bots reading the same pages from two addresses through the same TibiaData
 *  instance is pure waste. Posting is the opposite — each bot is a different
 *  Discord user in its own set of guilds and can only write to those, so a
 *  primary announcing on everyone's behalf would silently drop every guild it
 *  is not itself in.
 *
 *  So the primary sweeps and files; every bot, primary included, reads this
 *  table and posts what belongs to it. The primary's own posting goes through
 *  the same path, which also makes it survive a restart mid-sweep. */
final class HighscoreFeed(
    repository: HighscoreRepository,
    botId: String,
    serves: String => Boolean,
    announce: (String, HighscoreCategory, List[HighscoreEvent]) => Unit,
    batchLimit: Int = 500
) extends StrictLogging {

  def tick(): Unit =
    repository.feedCursor(botId) match {
      case None =>
        // First run on this bot. Start from the end rather than the beginning:
        // the events table holds a month, and announcing all of it would fill
        // every Levels channel with advances that happened weeks ago.
        val start = repository.maxEventId()
        repository.setFeedCursor(botId, start)
        logger.info(s"Highscores: starting the advance feed at event $start — anything already filed is history and is not announced")

      case Some(cursor) =>
        val filed = repository.eventsAfter(cursor, batchLimit)
        if (filed.nonEmpty) {
          val batch = HighscoreFeed.plan(filed, serves)
          batch.groups.foreach { case (world, category, events) => announce(world, category, events) }
          repository.setFeedCursor(botId, batch.cursor)
          logger.debug(s"Highscores: read ${filed.size} advance(s) through event ${batch.cursor}, posting ${batch.groups.size} batch(es)")
        }
    }
}
