package com.tibiabot.domain

/** What happened to each name in a pasted list.
 *
 *  Four buckets rather than a count, because they need different things said
 *  about them and — for the last one — offered. `unavailable` is the reason this
 *  type has more than two fields: a name Tibia's API never answered for has not
 *  been added and has not been ruled out, and folding it into `notFound` would
 *  report real characters as nonexistent, in bulk, convincingly.
 */
final case class BulkListOutcome(added: List[String] = Nil,
                                 already: List[String] = Nil,
                                 notFound: List[String] = Nil,
                                 unavailable: List[String] = Nil,
                                 /** Names past the ceiling, never looked at. */
                                 skipped: List[String] = Nil) {

  def merge(other: BulkListOutcome): BulkListOutcome =
    BulkListOutcome(added ++ other.added, already ++ other.already,
      notFound ++ other.notFound, unavailable ++ other.unavailable, skipped ++ other.skipped)

  def total: Int = added.size + already.size + notFound.size + unavailable.size + skipped.size

  /** Whether anything actually changed — decides if the admin log is worth a post. */
  def changedAnything: Boolean = added.nonEmpty
}

object BulkListOutcome {
  val empty: BulkListOutcome = BulkListOutcome()
}
