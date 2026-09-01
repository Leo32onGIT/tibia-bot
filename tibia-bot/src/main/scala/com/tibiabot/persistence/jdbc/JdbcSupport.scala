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

  /** Like [[withConnection]], but runs `use` in one transaction: committed if it
   *  returns, rolled back if it throws.
   *
   *  Needed by the respawn claim system, whose writes are genuinely contended —
   *  claim and queue mutations arrive on JDA's event threads *and* on the expiry
   *  sweep, racing on the same spawn. Read-then-write across two autocommit
   *  statements lets two simultaneous Next clicks read the same queue length and
   *  both write position 3. Callers pair this with `SELECT ... FOR UPDATE`, which
   *  only serialises within a transaction.
   *
   *  Autocommit is restored before close, so a pooled connection cannot leak
   *  transactional mode into an unrelated caller. */
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
