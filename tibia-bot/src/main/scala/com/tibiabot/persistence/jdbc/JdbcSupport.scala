package com.tibiabot.persistence.jdbc

import java.sql.Connection

/** Shared JDBC resource helpers for the repositories.
 *
 *  The repos previously closed their connection only on the happy path, so any
 *  SQL exception leaked the connection — under concurrent multi-guild load that
 *  exhausts Postgres' connection limit. `withConnection` guarantees the
 *  connection is closed (which also closes its statements/result sets) whether
 *  the body returns or throws.
 */
private[persistence] object JdbcSupport {
  def withConnection[A](connect: () => Connection)(use: Connection => A): A = {
    val conn = connect()
    try use(conn)
    finally conn.close()
  }

  /** Like [[withConnection]], but runs `use` inside a single transaction:
   *  committed if it returns, rolled back if it throws.
   *
   *  Needed by the respawn claim system, which is the first feature here whose
   *  writes are genuinely contended — claim and queue mutations arrive on JDA's
   *  event threads (slash commands and button clicks from different people at
   *  once) *and* on the expiry sweep, all racing on the same spawn. Doing
   *  read-then-write across two autocommit statements lets two simultaneous
   *  Next clicks read the same queue length and both write position 3. The
   *  callers pair this with `SELECT ... FOR UPDATE` on the respawn row, which
   *  only serialises within a transaction.
   *
   *  Autocommit is restored before the connection is closed so a pooled or
   *  reused connection can't leak transactional mode into an unrelated caller.
   */
  def withTransaction[A](connect: () => Connection)(use: Connection => A): A =
    withConnection(connect) { conn =>
      val previousAutoCommit = conn.getAutoCommit
      conn.setAutoCommit(false)
      try {
        val result = use(conn)
        conn.commit()
        result
      } catch {
        case error: Throwable =>
          try conn.rollback()
          catch { case rollbackError: Throwable => error.addSuppressed(rollbackError) }
          throw error
      } finally {
        try conn.setAutoCommit(previousAutoCommit)
        catch { case _: Throwable => () } // best-effort; the connection is closing anyway
      }
    }
}
