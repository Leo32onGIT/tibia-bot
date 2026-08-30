package com.tibiabot.app

import akka.actor.Cancellable
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Which worlds the primary takes on for the rest of the fleet.
 *
 *  The point of the class is that only the primary's address is whitelisted
 *  upstream, so a world nobody on the primary serves still has to be fetched
 *  here — otherwise the secondary that does serve it fetches for itself, from
 *  an address that was never agreed. */
class UnionFetchReconcilerSpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  /** A secondary's published snapshot, trimmed to the part that matters here. */
  private def snapshot(worlds: String*): JsObject =
    JsObject("worlds" -> JsArray(worlds.map(w => JsObject("name" -> JsString(w)): JsValue).toVector))

  private class Started {
    var started = List.empty[String]
    var cancelled = List.empty[String]
    def poller(world: String): Cancellable = {
      started = started :+ world
      new Cancellable {
        private var done = false
        def cancel(): Boolean = { cancelled = cancelled :+ world; done = true; true }
        def isCancelled: Boolean = done
      }
    }
  }

  private def reconciler(
      local: Set[String],
      statuses: Vector[JsObject],
      tracker: Started,
      enabled: Boolean = true
  ) = new UnionFetchReconciler(() => local, () => Future.successful(statuses), tracker.poller, enabled)

  test("covers a world only a secondary serves") {
    val t = new Started
    val r = reconciler(local = Set("Antica"), statuses = Vector(snapshot("Vunira")), t)
    await(r.reconcile())
    t.started shouldBe List("Vunira")
    r.covering shouldBe Set("Vunira")
  }

  test("does not double up on a world it already polls properly") {
    // The real stream already fetches it; a second poller would double this
    // world's upstream requests for no benefit whatsoever.
    val t = new Started
    val r = reconciler(local = Set("Antica"), statuses = Vector(snapshot("Antica")), t)
    await(r.reconcile())
    t.started shouldBe empty
  }

  test("several secondaries are merged, and a shared world is covered once") {
    val t = new Started
    val r = reconciler(local = Set.empty, statuses = Vector(snapshot("Vunira", "Bona"), snapshot("Bona")), t)
    await(r.reconcile())
    t.started.sorted shouldBe List("Bona", "Vunira")
  }

  test("stops covering a world once no secondary reports it") {
    // A secondary's snapshot expires on its own, so a bot that dies simply
    // stops appearing — there is no teardown message that could be missed.
    val t = new Started
    val r = new UnionFetchReconciler(
      () => Set.empty,
      new (() => Future[Vector[JsObject]]) {
        private var calls = 0
        def apply(): Future[Vector[JsObject]] = {
          calls += 1
          Future.successful(if (calls == 1) Vector(snapshot("Vunira")) else Vector.empty)
        }
      },
      t.poller, enabled = true)

    await(r.reconcile())
    r.covering shouldBe Set("Vunira")
    await(r.reconcile())
    t.cancelled shouldBe List("Vunira")
    r.covering shouldBe empty
  }

  test("hands a world back once this bot starts serving it itself") {
    val t = new Started
    var local = Set.empty[String]
    val r = new UnionFetchReconciler(
      () => local, () => Future.successful(Vector(snapshot("Vunira"))), t.poller, enabled = true)

    await(r.reconcile())
    r.covering shouldBe Set("Vunira")
    local = Set("Vunira") // a guild here added it
    await(r.reconcile())
    t.cancelled shouldBe List("Vunira")
  }

  test("a repeat pass with no change starts nothing new") {
    val t = new Started
    val r = reconciler(local = Set.empty, statuses = Vector(snapshot("Vunira")), t)
    await(r.reconcile())
    await(r.reconcile())
    await(r.reconcile())
    t.started shouldBe List("Vunira")
    t.cancelled shouldBe empty
  }

  test("one unreadable snapshot does not stop the others being covered") {
    val t = new Started
    val broken = JsObject("worlds" -> JsString("not an array"))
    val r = reconciler(local = Set.empty, statuses = Vector(broken, snapshot("Vunira")), t)
    await(r.reconcile())
    t.started shouldBe List("Vunira")
  }

  test("disabled does nothing at all") {
    val t = new Started
    val r = reconciler(local = Set.empty, statuses = Vector(snapshot("Vunira")), t, enabled = false)
    await(r.reconcile())
    t.started shouldBe empty
  }

  test("shutdown cancels everything it started") {
    val t = new Started
    val r = reconciler(local = Set.empty, statuses = Vector(snapshot("Vunira", "Bona")), t)
    await(r.reconcile())
    r.shutdown()
    t.cancelled.sorted shouldBe List("Bona", "Vunira")
    r.covering shouldBe empty
  }
}
