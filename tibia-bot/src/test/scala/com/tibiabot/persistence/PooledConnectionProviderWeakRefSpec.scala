package com.tibiabot.persistence

import com.zaxxer.hikari.util.ConcurrentBag
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** That discarded pools can actually be collected.
 *
 *  This provider builds and closes pools continuously — see its class comment —
 *  and HikariCP caches borrowed entries in a `ThreadLocal` that its `close()`
 *  does not clear. Without weak references there, every pool the bot ever made
 *  stays reachable from the worker threads that borrowed from it, and the heap
 *  fills in about a day and a half. It did, on 5 Sep 2026: ninety thousand live
 *  `HikariDataSource` objects and the old generation pinned at 100%.
 *
 *  No Postgres needed. Building the provider opens no connection, which is the
 *  point — the property has to be set before the first pool, not the first query.
 */
class PooledConnectionProviderWeakRefSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach {

  private val property = PooledConnectionProvider.WeakReferencesProperty
  private var original: Option[String] = None

  override def beforeEach(): Unit = {
    original = Option(System.getProperty(property))
    System.clearProperty(property)
  }

  override def afterEach(): Unit = {
    original.fold(System.clearProperty(property))(System.setProperty(property, _))
    ()
  }

  /** Builds a provider against a host nothing resolves. Nothing connects until a
   *  borrow, and this never borrows. */
  private def provider(): PooledConnectionProvider =
    new PooledConnectionProvider("pool-spec.invalid", "unused")

  test("constructing the provider asks Hikari for weak thread-locals") {
    val pooled = provider()
    try System.getProperty(property) shouldBe "true"
    finally pooled.close()
  }

  test("an explicit setting wins, so a host can still turn it off without a build") {
    System.setProperty(property, "false")
    val pooled = provider()
    try System.getProperty(property) shouldBe "false"
    finally pooled.close()
  }

  test("enabling it twice is not a change the second time") {
    PooledConnectionProvider.enableWeakThreadLocals()
    PooledConnectionProvider.enableWeakThreadLocals()
    System.getProperty(property) shouldBe "true"
  }

  /** The one that matters, and the reason this reaches into Hikari's own field
   *  rather than trusting the property name: the name is not part of any API we
   *  are promised, and an upgrade that renames or drops it would put the leak
   *  back with nothing failing. This fails instead.
   *
   *  Only the "set" direction is asserted. Hikari's other route to weak
   *  references is being loaded by something other than the system classloader,
   *  which is true under sbt and false of the bot — so a test run cannot observe
   *  the strong default that production gets, and asserting it here would only
   *  pin sbt's classloading. That gap is the whole reason this property has to be
   *  set explicitly: the leak cannot reproduce in a test.
   */
  test("Hikari actually reads the property we set") {
    PooledConnectionProvider.enableWeakThreadLocals()
    withClue(s"HikariCP no longer honours '$property' — the pool leak is back") {
      bagUsesWeakThreadLocals() shouldBe true
    }
  }

  /** What a freshly built bag decided, read off the field Hikari keeps it in. */
  private def bagUsesWeakThreadLocals(): Boolean = {
    val bag = new ConcurrentBag[ConcurrentBag.IConcurrentBagEntry]((_: Int) => ())
    try {
      val field = classOf[ConcurrentBag[_]].getDeclaredField("useWeakThreadLocals")
      field.setAccessible(true)
      field.getBoolean(bag)
    } finally bag.close()
  }
}
