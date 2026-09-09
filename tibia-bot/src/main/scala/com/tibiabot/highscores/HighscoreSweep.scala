package com.tibiabot.highscores

import com.tibiabot.domain.HighscoreEvent
import com.tibiabot.persistence.{ExperienceRepository, HighscoreRepository}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.tibiabot.tibiadata.{HighscoreCategory, HighscoreList, Highscores, HighscoresApi}
import com.tibiabot.tibiadata.response.{HighscoreData, HighscoreEntry}
import com.tibiabot.highscores.HighscoreSweep.{NoPages, PageReads}
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
    readPages(world, list).map { reads =>
      val entries = reads.entries
      // Not one page came back. Writing now would stamp nothing and prove
      // nothing, and the next snapshot re-reads the whole list anyway.
      if (entries.isEmpty) {
        if (reads.failed > 0) logger.warn(s"Highscores: read no pages of '$list' for '$world' (${reads.failed} failed)")
        ListSweep(world, list, reads.read, reads.failed, 0, Nil)
      } else {
        // A missing page in the middle is survivable and deliberately not fatal:
        // nothing is ever deleted here, so its characters simply keep the score
        // and last_seen they had. Their advance is found on the next snapshot
        // against the same baseline — late, not lost.
        //
        // A list that is simply shorter than 20 pages is not that, and does not
        // reach here: [[readPages]] stops at the end the endpoint reports.
        if (reads.failed > 0)
          logger.warn(s"Highscores: '$list' for '$world' is short ${reads.failed} page(s); writing the ${entries.size} characters that came back")

        val previous = repository.load(world, list.category.slug)
        val advances = HighscoreDiff.advances(
          world, list.category, list.postsAdvances, previous, entries, snapshotAt, maxBaselineAge)

        repository.upsertAll(world, list.category.slug, entries, snapshotAt)
        if (advances.nonEmpty) repository.recordEvents(advances)
        if (list.category == HighscoreCategory.Experience) recordHistory(world, entries, snapshotAt)

        ListSweep(world, list, reads.read, reads.failed, entries.size, advances)
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
   *  a single list's 20 pages as well would make the pacing meaningless.
   *
   *  It stops at `total_pages` rather than always walking to 20. Twenty is the
   *  ceiling, not the length: a vocation-filtered list on a young world runs
   *  out well before it — Penumbra's monk magic level was 11 pages of 517
   *  characters — and the endpoint refuses a page past the end with HTTP 400 /
   *  error 11008, exactly as it refuses page 21. Walking on regardless would
   *  count nine refusals as nine failed pages and warn about a list that is
   *  complete, on top of nine requests that can only be refused. */
  private def readPages(world: String, list: HighscoreList): Future[PageReads] =
    Highscores.pages.foldLeft(Future.successful(NoPages)) { case (acc, page) =>
      acc.flatMap { reads =>
        if (page > reads.lastPage) Future.successful(reads)
        else delay(gap()).flatMap(_ => api.getHighscores(world, list, page)).map {
          case Right(response) => reads.add(response.highscores)
          case Left(_) => reads.missed // already logged by the client
        }.recover { case error =>
          logger.warn(s"Highscores: page $page of '$list' for '$world' failed: ${error.getMessage}")
          reads.missed
        }
      }
    }
}

/** Internals of [[HighscoreSweep]]. Out here rather than nested inside it
 *  because a case class inside a class carries an unchecked outer reference. */
private object HighscoreSweep {

  /** What one pass over a list's pages produced, page by page.
   *
   *  `lastPage` is where the list actually ends, which is not always page 20 —
   *  see `readPages`. `pages` is newest-first while the fold runs; `entries`
   *  puts it back into list order. */
  final case class PageReads(
      pages: List[List[HighscoreEntry]],
      read: Int,
      failed: Int,
      lastPage: Int
  ) {

    /** A page that answered. Every page of a list carries the same paging
     *  record, so in practice the first one to answer settles `lastPage`; taking
     *  the smallest means a list that shrinks mid-read is never over-read. */
    def add(data: HighscoreData): PageReads = copy(
      pages = data.highscore_list :: pages,
      read = read + 1,
      lastPage = math.min(lastPage, data.highscore_page.total_pages))

    def missed: PageReads = copy(failed = failed + 1)

    def entries: List[HighscoreEntry] = pages.reverse.flatten
  }

  val NoPages: PageReads = PageReads(Nil, 0, 0, Highscores.MaxPages)
}
