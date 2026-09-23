package com.tibiabot.observer

import com.tibiabot.persistence.RedisCache
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import java.util.UUID
import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/** Hands the Observer account operations — linking a token, and clearing the bot's
 *  rules from an account on unlink — from a bot that does not talk to the Observer
 *  API to the one that does.
 *
 *  Every Observer request leaves from one place: the primary, which runs the
 *  sidecar. A secondary only reads the feeds the primary publishes (see
 *  [[ObserverFeed]]), but a member of one of *its* servers can still press Add or
 *  Remove on `/observer`, and those need the API. So the secondary publishes the
 *  request here and waits for the primary's answer, the way the dashboard hands a
 *  respawn write to the bot that runs the guild.
 *
 *  ==Transport==
 *  The request is published on [[ObserverRelay.Channel]]; the answer comes back as
 *  a short-lived key the asker polls. A publish that reached nobody is a definite
 *  "the primary is not listening", known at once rather than after the timeout.
 *  The primary writes the link to the shared database itself, so the answer only
 *  says how it went — no credential ever travels back.
 *
 *  The asker blocks (it is called from an interaction worker, which already waits
 *  on the sidecar the same way on a primary); the server side runs each request on
 *  its own small pool, never on the Redis connection's thread. */
final class ObserverRelay(
  cache: RedisCache,
  linkTimeout: FiniteDuration = 45.seconds,
  clearTimeout: FiniteDuration = 15.seconds,
  pollEvery: FiniteDuration = 250.millis,
  newId: () => String = () => UUID.randomUUID().toString
)(implicit ec: ExecutionContext) extends StrictLogging {
  import ObserverRelay._

  /** Ask the primary to link a member's access token. */
  def link(guildId: String, userId: String, accessToken: String): Reply =
    ask(Request(newId(), OpLink, guildId, userId, Some(accessToken)), linkTimeout)

  /** Ask the primary to clear the bot's rules from a member's account, before the
   *  link is deleted — the primary needs the stored credential to do it. */
  def clearRules(guildId: String, userId: String): Reply =
    ask(Request(newId(), OpClearRules, guildId, userId, None), clearTimeout)

  private def ask(request: Request, timeout: FiniteDuration): Reply = {
    val reached = Try(Await.result(cache.publish(Channel, encode(request)), 5.seconds)).getOrElse(0L)
    if (reached == 0L) Failed("the bot that talks to Tibia Observer is not listening")
    else {
      val key = replyKey(request.id)
      val deadline = System.nanoTime() + timeout.toNanos
      var answer = Option.empty[Reply]
      while (answer.isEmpty && System.nanoTime() < deadline) {
        Thread.sleep(pollEvery.toMillis)
        answer = Try(Await.result(cache.get(key), 5.seconds)).toOption.flatten.flatMap(decodeReply)
      }
      answer.foreach(_ => cache.delete(key))
      answer.getOrElse(Failed(s"no answer within ${timeout.toSeconds}s"))
    }
  }

  /** Where the requests are run, off the Redis connection's thread: each one is a
   *  few blocking sidecar calls. Two threads, since a link and an unlink landing
   *  together should not queue behind each other. */
  private lazy val serving: ExecutionContext = {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val factory: ThreadFactory = (r: Runnable) => {
      val thread = new Thread(r, s"observer-relay-${count.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2, factory))
  }

  /** Answer requests for as long as this process lives — the primary's side. The
   *  returned Future fails when the subscription could not be set up, which the
   *  caller should log: every secondary would otherwise wait out its timeout. */
  def serve(handle: Request => Reply): Future[Unit] =
    cache.subscribe(Channel) { body =>
      decodeRequest(body) match {
        case None => logger.warn("Dropped an unreadable Observer relay request")
        case Some(request) =>
          Future(handle(request))(serving)
            .recover { case NonFatal(ex) =>
              logger.warn(s"Observer relay request '${request.op}' failed", ex)
              Failed(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
            }(serving)
            .flatMap(reply => cache.setEx(replyKey(request.id), encode(reply), ReplyTtl))
      }
    }
}

object ObserverRelay {

  /** Requests go out here; only the primary listens. */
  val Channel: String = "tibia:observer:ops"

  /** How long an answer waits to be collected — past any asker's timeout. */
  val ReplyTtl: FiniteDuration = 2.minutes

  def replyKey(id: String): String = s"tibia:observer:reply:$id"

  val OpLink = "link"
  val OpClearRules = "clear-rules"

  /** `token` is the member's single-use access code, present only for a link. */
  final case class Request(id: String, op: String, guildId: String, userId: String, token: Option[String])

  sealed trait Reply
  case object Linked extends Reply
  case object InvalidToken extends Reply
  case object Done extends Reply
  final case class Failed(reason: String) extends Reply

  def encode(request: Request): String =
    JsObject(
      Map("id" -> JsString(request.id), "op" -> JsString(request.op),
          "guildId" -> JsString(request.guildId), "userId" -> JsString(request.userId)) ++
        request.token.map(t => "token" -> JsString(t))
    ).compactPrint

  def decodeRequest(body: String): Option[Request] =
    Try {
      val o = body.parseJson.asJsObject
      def str(key: String) = o.fields.get(key).collect { case JsString(s) => s }
      for (id <- str("id"); op <- str("op"); guild <- str("guildId"); user <- str("userId"))
        yield Request(id, op, guild, user, str("token"))
    }.toOption.flatten

  def encode(reply: Reply): String = (reply match {
    case Linked         => JsObject("outcome" -> JsString("linked"))
    case InvalidToken   => JsObject("outcome" -> JsString("invalid-token"))
    case Done           => JsObject("outcome" -> JsString("done"))
    case Failed(reason) => JsObject("outcome" -> JsString("failed"), "reason" -> JsString(reason))
  }).compactPrint

  def decodeReply(body: String): Option[Reply] =
    Try {
      val o = body.parseJson.asJsObject
      o.fields.get("outcome").collect {
        case JsString("linked")        => Linked
        case JsString("invalid-token") => InvalidToken
        case JsString("done")          => Done
        case JsString("failed") =>
          Failed(o.fields.get("reason").collect { case JsString(r) => r }.getOrElse("unknown"))
      }
    }.toOption.flatten
}
