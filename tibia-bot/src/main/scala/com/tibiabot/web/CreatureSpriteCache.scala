package com.tibiabot.web

import com.typesafe.scalalogging.StrictLogging

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Creature sprites, fetched once from the wiki and served from our own domain
 *  thereafter.
 *
 *  Not only about leaning on a third party: tibiawiki geoblocks some regions, so a
 *  hotlinking page has no art at all for those visitors while the VPS reaches it
 *  fine. A board also shows dozens of sprites at once rather than one thumbnail
 *  per Discord post, so the volume is not somebody else's to carry.
 *
 *  Misses never block the page: a sprite not yet on disk is reported absent and
 *  fetched in the background, so the first viewer sees the placeholder and
 *  everybody after sees the real thing. `fetch` is injected so this can be
 *  exercised without network. */
final class CreatureSpriteCache(
  directory: Path,
  fetch: String => Future[Option[Array[Byte]]]
)(implicit ec: ExecutionContext) extends StrictLogging {

  /** Fetches already under way, so a board showing the same missing sprite
   *  twenty times asks the wiki once rather than twenty times. Also what stops
   *  a repeated miss from re-requesting on every poll. */
  private val inFlight = new ConcurrentHashMap[String, java.lang.Boolean]()

  /** Names that came back missing, so a spawn whose creature has no wiki page
   *  isn't retried forever at one request per page view. Not persisted — a
   *  restart is a cheap enough excuse to try again in case the wiki has since
   *  gained the file. */
  private val known404 = ConcurrentHashMap.newKeySet[String]()

  /** What each cached sprite measured, by file name. [[SpriteNudge.None]] means
   *  measured and not worth shifting, true of about half. Held rather than
   *  recomputed because the answer belongs to the file and files never change. */
  private val nudges = new ConcurrentHashMap[String, SpriteNudge]()

  /** Measurements under way, so a board asking about the same unmeasured sprite
   *  twenty times decodes it once. */
  private val measuring = ConcurrentHashMap.newKeySet[String]()

  /** Whether the cache can actually be written to, checked once at startup.
   *
   *  Worth doing eagerly because the failure it catches is a deployment
   *  problem, not a runtime one: a directory the container's user cannot write
   *  to fails identically for every sprite, forever, and saying so once — with
   *  the path and the user — is the difference between a five-minute fix and a
   *  log full of a hundred identical warnings that name only a temp file.
   */
  private def checkWritable(): Unit =
    try {
      Files.createDirectories(directory)
      val probe = Files.createTempFile(directory, ".writable", ".probe")
      Files.deleteIfExists(probe)
      logger.info(s"Creature sprite cache ready at ${directory.toAbsolutePath}")
    } catch {
      case NonFatal(e) =>
        logger.error(
          s"Creature sprite cache at ${directory.toAbsolutePath} is not writable by " +
            s"${sys.props.getOrElse("user.name", "this user")} (${describe(e)}). " +
            "Sprites will fall back to the placeholder until it is. In Docker this usually " +
            "means the directory was created by the daemon as root — see the mkdir in build.sbt.")
    }

  checkWritable()
  measureCached()

  private def fileFor(safeName: String): Path = directory.resolve(safeName)

  /** How far this creature's art should be shifted for the creature to sit in
   *  the middle of it — see [[SpriteInk]]. None for a sprite that wants no
   *  shifting, and for one nobody has measured yet.
   *
   *  Never blocks and never decodes on the caller's thread, exactly as [[get]]
   *  never fetches on it. A sprite this has not seen yet answers None and is
   *  measured behind the request, so the first board drawn after a brand-new
   *  creature arrives shows it where `object-fit` puts it and every board after
   *  that shows it centred. The catalogue is only cached for two minutes, so
   *  "after that" means within two minutes.
   */
  def nudgeFor(wikiName: String): Option[SpriteNudge] =
    CreatureSprites.safeFileName(wikiName).flatMap { safeName =>
      Option(nudges.get(safeName)) match {
        case Some(known) => Some(known).filterNot(_.isZero)
        case None        => measureLater(safeName); None
      }
    }

  /** The bytes if we already hold them. A miss starts a background fetch and
   *  returns None immediately — the caller shows the placeholder. */
  def get(wikiName: String): Option[Array[Byte]] =
    CreatureSprites.safeFileName(wikiName).flatMap { safeName =>
      val file = fileFor(safeName)
      if (Files.isReadable(file)) {
        try Some(Files.readAllBytes(file))
        catch {
          case NonFatal(e) =>
            logger.warn(s"Could not read cached sprite '$safeName': ${describe(e)}")
            None
        }
      } else {
        warm(wikiName)
        None
      }
    }

  /** Fetch and store `wikiName` if we don't have it and aren't already trying.
   *  Returns immediately; the work happens on `ec`. */
  def warm(wikiName: String): Unit =
    CreatureSprites.safeFileName(wikiName).foreach { safeName =>
      if (!known404.contains(safeName) &&
          !Files.isReadable(fileFor(safeName)) &&
          inFlight.putIfAbsent(safeName, java.lang.Boolean.TRUE) == null) {
        fetch(safeName).map {
          case Some(bytes) if bytes.nonEmpty => store(safeName, bytes)
          case Some(_) =>
            logger.warn(s"Sprite '$safeName' came back empty; treating it as missing")
            known404.add(safeName)
          case None => known404.add(safeName)
        }.recover {
          // A transient failure must not be remembered as a 404, or one bad
          // minute would blank a sprite until the next restart.
          case NonFatal(e) => logger.warn(s"Could not fetch sprite '$safeName': ${describe(e)}")
        }.foreach(_ => inFlight.remove(safeName))
      }
    }

  /** Written to a temporary file and moved into place, so a fetch interrupted
   *  half way through can't leave a truncated gif that would then be served
   *  forever as if it were complete. */
  private def store(safeName: String, bytes: Array[Byte]): Unit =
    try {
      Files.createDirectories(directory)
      val temp = Files.createTempFile(directory, safeName, ".part")
      Files.write(temp, bytes)
      Files.move(temp, fileFor(safeName), StandardCopyOption.REPLACE_EXISTING)
      logger.debug(s"Cached creature sprite '$safeName' (${bytes.length} bytes)")
      // Measured here, with the bytes already in hand, because this is the only
      // moment a sprite that was not on disk becomes one that is.
      measure(safeName, bytes)
    } catch {
      case NonFatal(e) => logger.warn(s"Could not cache sprite '$safeName': ${describe(e)}")
    }

  /** An exception as a line of log worth reading.
   *
   *  `getMessage` on a `java.nio.file.FileSystemException` is the path and
   *  nothing else, so "Could not cache sprite 'X': cache/sprites/X.part" was
   *  every bit as true of a full disk, a missing directory and a permission
   *  problem — and told you which of the three it was in none of those cases.
   */
  private def describe(e: Throwable): String = {
    val detail = Option(e.getMessage).filter(_.nonEmpty).getOrElse("no detail")
    s"${e.getClass.getSimpleName}: $detail"
  }

  /** Measure `safeName` off the caller's thread, once. */
  private def measureLater(safeName: String): Unit =
    if (Files.isReadable(fileFor(safeName)) && measuring.add(safeName)) {
      Future(measure(safeName, Files.readAllBytes(fileFor(safeName))))
        .recover {
          // Left unmeasured rather than remembered as needing nothing, so a
          // transient read failure is retried on the next board instead of
          // settling in as "this sprite is fine".
          case NonFatal(e) => logger.warn(s"Could not measure sprite '$safeName': ${describe(e)}")
        }
        .onComplete(_ => measuring.remove(safeName))
    }

  private def measure(safeName: String, bytes: Array[Byte]): Unit =
    nudges.put(safeName, SpriteInk.nudgeOf(bytes).getOrElse(SpriteNudge.None))

  /** Measure everything already on disk, in the background, at startup.
   *
   *  Without this the first visitor after a restart is the one who measures the
   *  catalogue — not slowly, since nothing waits on it, but a board at a time.
   *  Doing it up front means the answers are there before anybody asks, and it
   *  costs one pass over a few hundred small files on a thread nobody is
   *  waiting for.
   */
  private def measureCached(): Unit =
    if (Files.isDirectory(directory)) Future {
      val listing = Files.list(directory)
      try
        listing.iterator().asScala
          // Sprites only. A cache directory can hold other things — an
          // interrupted write, an old probe file — and measuring those puts
          // junk in the memo and junk in the count below. Every name that can
          // ever be looked up came from `CreatureSprites.safeFileName`, so this
          // is exactly the set worth measuring.
          .filter(file => file.getFileName.toString.endsWith(".gif") && Files.isRegularFile(file))
          .foreach(file => measure(file.getFileName.toString, Files.readAllBytes(file)))
      finally listing.close()
      val shifted = nudges.values().asScala.count(!_.isZero)
      if (nudges.size > 0)
        logger.info(s"Measured ${nudges.size} cached creature sprites; $shifted sit off centre in their canvas")
    }.failed.foreach(e => logger.warn(s"Could not measure the cached sprites: ${describe(e)}"))

  private[web] def isCached(wikiName: String): Boolean =
    CreatureSprites.safeFileName(wikiName).exists(name => Files.isReadable(fileFor(name)))

  private[web] def missingCount: Int = known404.size
}
