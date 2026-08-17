package com.tibiabot.web

import com.typesafe.scalalogging.StrictLogging

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Creature sprites, fetched once from the wiki and served from our own domain
 *  thereafter.
 *
 *  The point is not only to stop leaning on a third party. tibiawiki geoblocks
 *  some regions, so a page that hotlinks it simply has no art for those
 *  visitors — while the VPS the bot runs on can reach it perfectly well. Moving
 *  the fetch server-side turns "broken for some people" into "works for
 *  everyone", which is a different kind of win from the one the BattlEye icons
 *  were vendored for. A board also shows dozens of sprites at once rather than
 *  one thumbnail per Discord post, so the request volume is nothing we should
 *  be sending somebody else's way.
 *
 *  Misses never block the page. A sprite that isn't on disk yet is reported
 *  absent and fetched in the background, so the first viewer sees the
 *  placeholder and everybody after them sees the real thing. That keeps a slow
 *  or unreachable wiki from turning into a slow dashboard.
 *
 *  `fetch` is injected so the whole thing can be exercised without network.
 */
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

  private def fileFor(safeName: String): Path = directory.resolve(safeName)

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
      logger.info(s"Cached creature sprite '$safeName' (${bytes.length} bytes)")
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

  private[web] def isCached(wikiName: String): Boolean =
    CreatureSprites.safeFileName(wikiName).exists(name => Files.isReadable(fileFor(name)))

  private[web] def missingCount: Int = known404.size
}
