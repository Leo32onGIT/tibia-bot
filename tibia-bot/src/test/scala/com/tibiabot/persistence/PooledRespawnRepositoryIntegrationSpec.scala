package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcRespawnRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration._

/** The repository layer, run against a pool rather than a fresh connection per
 *  query (cancels without PGHOST).
 *
 *  One case here is worth more than the rest: [[JdbcRespawnRepository.withRespawnLock]]
 *  holds a connection open for the whole of a body that opens more. Drawn from
 *  a pool the body is also drawing on, enough lock holders arriving together
 *  would each be waiting for a connection the others were holding, and none of
 *  them could finish — a deadlock that only shows up under load, which is the
 *  worst kind to find in production. `guildUnpooled` is what makes it
 *  impossible; this is what proves it.
 */
class PooledRespawnRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "888000888000888502"

  test("concurrent lock holders that read inside the lock all finish") {
    val direct = pgOrCancel()
    val initializer = new SchemaInitializer(direct)
    initializer.initGuild(guildId, "pool-lock-spec")

    // Narrower than the number of threads on purpose. If the lock's own
    // connection came from here, two holders would be enough to wedge it.
    val provider = new PooledConnectionProvider(
      sys.env.getOrElse("PGHOST", ""), sys.env.getOrElse("PGPASSWORD", "postgres"),
      maxPerDatabase = 2, unpooled = direct)
    val repository = new JdbcRespawnRepository(provider)
    val pool = Executors.newFixedThreadPool(6)

    try {
      // Something to take the lock on, and something for the body to read.
      val respawn = repository.addRespawn(guildId, "415", "Cult Orcs", "Orc Warlord",
        "Edron", "", "", "seed", "spec")

      val holders = 6
      val ready = new CountDownLatch(holders)
      val go = new CountDownLatch(1)
      val done = new java.util.concurrent.atomic.AtomicInteger(0)

      (1 to holders).foreach { _ =>
        pool.submit(new Runnable {
          def run(): Unit = {
            ready.countDown()
            go.await()
            repository.withRespawnLock(guildId, respawn.id) {
              // A pooled read from inside the lock, which is the whole point:
              // the body does not join the lock's transaction, it opens its own.
              repository.listRespawns(guildId).map(_.code) should contain("415")
              done.incrementAndGet()
            }
            ()
          }
        })
        ()
      }

      ready.await(30, TimeUnit.SECONDS) shouldBe true
      go.countDown()
      pool.shutdown()
      // Comfortably inside the pool's own 15-second connection timeout, so a
      // failure here is a wedge rather than a slow database.
      pool.awaitTermination(PooledConnectionProvider.ConnectionTimeout.millis.toSeconds - 5, TimeUnit.SECONDS) shouldBe true
      done.get() shouldBe holders
    } finally {
      pool.shutdownNow()
      provider.close()
      initializer.dropGuild(guildId)
    }
  }
}
