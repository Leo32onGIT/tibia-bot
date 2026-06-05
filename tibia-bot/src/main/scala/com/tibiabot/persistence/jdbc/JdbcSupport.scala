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
}
