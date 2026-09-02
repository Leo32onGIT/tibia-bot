package com.tibiabot.highscores

import com.tibiabot.domain.HighscoreEvent
import com.tibiabot.persistence.{ExperienceRepository, HighscoreRepository}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.tibiabot.tibiadata.{HighscoreCategory, HighscoreList, Highscores, HighscoresApi}
import com.tibiabot.tibiadata.response.HighscoreEntry
import com.typesafe.scalalogging.StrictLogging

import java.time.{Duration, Instant}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** What one list's pass over one world produced. */
final case class ListSweep(
    world: String,
    list: HighscoreList,
    pagesRead: Int,
    pagesFailed: Int,
    characters: Int,
    advances: List[HighscoreEvent]
)

/** Reads one highscore list for one world, works out what advanced, and writes
 *  the result down. Everything about *when* this runs lives in
 *  [[HighscoreService]]; everything about *what it means* lives in
 *  [[HighscoreDiff]]. This is the part that joins them to the database.
 *
 *  `delay` is injected rather than taken from a scheduler so the pacing is real
 *  in production and free in tests. */
final class HighscoreSweep(
    api: HighscoresApi,
    repository: HighscoreRepository,
    experience: ExperienceRepository,
    gap: () => FiniteDuration,
    delay: FiniteDuration => Future[Unit],
    maxBaselineAge: Duration = HighscoreDiff.MaxBaselineAge
)(implicit ec: ExecutionContext) extends StrictLogging {

  def sweepList(world: String, list: HighscoreList, snapshotAt: Instant): Future[ListSweep] =
    readPages(world, list).map { case (entries, failed) =>
      // Not one page came back. Writing now would stamp nothing and prove
      // nothing, and the next snapshot re-reads the whole list anyway.
      if (entries.isEmpty) {
        if (failed > 0) logger.warn(s"Highscores: read no pages of '$list' for '$world' ($failed failed)")
        ListSweep(world, list, 0, failed, 0, Nil)
      } else {
        // A missing page in the middle is survivable and deliberately not fatal:
        // nothing is ever deleted here, so its characters simply keep the score
        // and last_seen they had. Their advance is found on the next snapshot
        // against the same baseline — late, not lost.
        if (failed > 0)
          logger.warn(s"Highscores: '$list' for '$world' is short $failed page(s); writing the ${entries.size} characters that came back")

        val previous = repository.load(world, list.category.slug)
        val advances = HighscoreDiff.advances(
          world, list.category, list.postsAdvances, previous, entries, snapshotAt, maxBaselineAge)

        repository.upsertAll(world, list.category.slug, entries, snapshotAt)
        if (advances.nonEmpty) repository.recordEvents(advances)
        if (list.category == HighscoreCategory.Experience) recordHistory(world, entries, snapshotAt)

        ListSweep(world, list, Highscores.MaxPages - failed, failed, entries.size, advances)
      }
    }

  /** The experience list feeds the history tables and nothing else — it never
   *  posts, because the online-list comparison already announces a level-up
   *  within the minute and this reading is an hour old. */
  private def recordHistory(world: String, entries: List[HighscoreEntry], snapshotAt: Instant): Unit = {
    val saveDay = ServerSaveSchedule.lastServerSave(snapshotAt.atZone(com.tibiabot.domain.time.Clock.Berlin)).toLocalDate
    experience.recordReadings(world, entries, snapshotAt)
    experience.recordDaily(world, entries, saveDay)
  }

  /** Every page of the list, in order, one at a time with a gap between them.
   *
   *  Sequential rather than parallel on purpose: the parallelism that matters is
   *  across lists and worlds, where [[HighscoreService]] applies it. Fanning out
   *  a single list's 20 pages as well would make the pacing meaningless. */
  private def readPages(world: String, list: HighscoreList): Future[(List[HighscoreEntry], Int)] =
    Highscores.pages.foldLeft(Future.successful((List.empty[List[HighscoreEntry]], 0))) { case (acc, page) =>
      acc.flatMap { case (pages, failed) =>
        delay(gap()).flatMap(_ => api.getHighscores(world, list, page)).map {
          case Right(response) => (response.highscores.highscore_list :: pages, failed)
          case Left(_) => (pages, failed + 1) // already logged by the client
        }.recover { case error =>
          logger.warn(s"Highscores: page $page of '$list' for '$world' failed: ${error.getMessage}")
          (pages, failed + 1)
        }
      }
    }.map { case (pages, failed) => (pages.reverse.flatten, failed) }
}
