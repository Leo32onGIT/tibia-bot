package com.tibiabot.web

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}

class CreatureSpriteCacheSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  /** Runs work on the calling thread, so a "background" fetch has finished by
   *  the time the call that started it returns and the tests need no waiting. */
  private implicit val ec: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = throw cause
  }

  private var dir: Path = _
  override def beforeEach(): Unit = dir = Files.createTempDirectory("sprite-cache-spec")
  override def afterEach(): Unit = {
    if (dir != null && Files.exists(dir))
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
  }

  private val bytes = Array[Byte](71, 73, 70, 56, 57, 97) // "GIF89a"

  private def cache(result: String => Future[Option[Array[Byte]]], counter: AtomicInteger = new AtomicInteger) =
    new CreatureSpriteCache(dir, name => { counter.incrementAndGet(); result(name) })

  "get" should {

    "miss the first time and serve from disk afterwards" in {
      val calls = new AtomicInteger
      val c = cache(_ => Future.successful(Some(bytes)), calls)
      // First look is a miss — the page shows the placeholder — but it starts
      // the fetch, so the next one is served locally.
      c.get("Dragon") shouldBe None
      c.get("Dragon").map(_.toList) shouldBe Some(bytes.toList)
      calls.get() shouldBe 1
    }

    "refuse a name that failed the safety check, and never fetch it" in {
      val calls = new AtomicInteger
      val c = cache(_ => Future.successful(Some(bytes)), calls)
      c.get("../../etc/passwd") shouldBe None
      calls.get() shouldBe 0
    }

    "not write anything for a name the wiki does not have" in {
      val c = cache(_ => Future.successful(None))
      c.get("Nonexistent_Creature") shouldBe None
      c.isCached("Nonexistent_Creature") shouldBe false
    }
  }

  "warm" should {

    "ask once for a sprite, however many times it is requested" in {
      val calls = new AtomicInteger
      val c = cache(_ => Future.successful(Some(bytes)), calls)
      // A board showing the same missing sprite many times must not turn into
      // many requests.
      (1 to 20).foreach(_ => c.warm("Dragon"))
      calls.get() shouldBe 1
    }

    "stop asking for a sprite the wiki reported missing" in {
      val calls = new AtomicInteger
      val c = cache(_ => Future.successful(None), calls)
      c.warm("Ghost")
      c.warm("Ghost")
      c.warm("Ghost")
      calls.get() shouldBe 1
      c.missingCount shouldBe 1
    }

    // A bad minute must not blank a sprite until the next restart.
    "retry after a transient failure rather than remembering it as missing" in {
      val calls = new AtomicInteger
      val c = cache(_ => Future.failed(new RuntimeException("connection reset")), calls)
      c.warm("Dragon")
      c.warm("Dragon")
      calls.get() shouldBe 2
      c.missingCount shouldBe 0
    }

    "treat an empty response as missing rather than caching a blank sprite" in {
      val c = cache(_ => Future.successful(Some(Array.emptyByteArray)))
      c.warm("Dragon")
      c.isCached("Dragon") shouldBe false
      c.missingCount shouldBe 1
    }

    "not re-fetch something already on disk" in {
      val calls = new AtomicInteger
      val c = cache(_ => Future.successful(Some(bytes)), calls)
      c.warm("Dragon")
      c.warm("Dragon")
      calls.get() shouldBe 1
    }

    "create the cache directory if it is not there yet" in {
      val nested = dir.resolve("sprites")
      val c = new CreatureSpriteCache(nested, _ => Future.successful(Some(bytes)))
      c.warm("Dragon")
      Files.isDirectory(nested) shouldBe true
      c.isCached("Dragon") shouldBe true
    }

    // Written aside and moved into place, so an interrupted fetch cannot leave
    // a truncated gif that would then be served forever as if it were whole.
    "leave no partial files behind" in {
      val c = cache(_ => Future.successful(Some(bytes)))
      c.warm("Dragon")
      val leftovers = Files.list(dir).toArray.map(_.toString).filter(_.contains(".part"))
      leftovers shouldBe empty
    }

    "store the bytes it was given, unchanged" in {
      val c = cache(_ => Future.successful(Some(bytes)))
      c.warm("Orc_Warlord")
      Files.readAllBytes(dir.resolve("Orc_Warlord.gif")).toList shouldBe bytes.toList
    }
  }
}
