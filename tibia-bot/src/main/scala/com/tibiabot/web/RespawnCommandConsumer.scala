package com.tibiabot.web

import com.tibiabot.persistence.RedisCache
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Performs writes that another bot's dashboard handed over.
 *
 *  Every bot runs one of these, and each only touches commands for guilds whose
 *  respawns it runs — so exactly one process ever executes a given command, and
 *  it is the one whose lifecycle sweep will go on to send the reminders and
 *  handovers for it.
 *
 *  The lease is taken *before* the work, not after. That makes this at-most-once
 *  rather than at-least-once: a process that dies mid-claim will not pick the
 *  command up again on restart, and the person is told nothing was confirmed.
 *  The other way round — execute, then mark done — would re-run a claim that had
 *  already charged somebody's stamina, and stamina spent twice is not something
 *  they can see or undo.
 */
final class RespawnCommandConsumer(
  cache: RedisCache,
  local: RespawnActionPort,
  ownsGuild: String => Boolean,
  selfId: String,
  /** Who somebody is in a guild this bot runs, resolved locally. `None` when
   *  they may not use the dashboard there at all.
   *
   *  This is the permission check for every relayed command, and it belongs
   *  here because this is the only process that can make it: the bot serving
   *  the page is not in the guild and cannot read a member of it. Defaulted to
   *  a refusal so that a consumer stood up without one performs nothing rather
   *  than everything. */
  resolve: (String, String) => Option[GuildAccess] = (_, _) => None
)(implicit ec: ExecutionContext) extends StrictLogging {

  /** Ids finished in this process, so a reply still sitting in Redis is not
   *  re-leased on the next sweep. The lease alone would cover it, but only until
   *  the lease expires — this closes that window without lengthening it. */
  private val handled = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** One pass: pick up whatever is waiting for guilds this bot runs. */
  def sweep(): Future[Unit] =
    cache.keysMatching(RespawnCommand.requestPattern).flatMap { keys =>
      val mine = keys.flatMap(RespawnCommand.parseRequestKey)
        .filterNot { case (_, id) => handled.contains(id) }
        // Ownership is checked before leasing, never after. Leasing first and
        // then discovering it is not ours would consume the command and answer
        // "not mine" — stealing it from the bot that should have run it.
        .filter { case (guildId, _) => ownsGuild(guildId) }
      Future.traverse(mine) { case (guildId, id) => handle(guildId, id) }.map(_ => ())
    }.recover {
      case NonFatal(e) => logger.warn(s"Respawn command sweep failed: ${e.getMessage}")
    }

  private def handle(guildId: String, id: String): Future[Unit] =
    cache.setIfAbsent(RespawnCommand.leaseKey(id), selfId, RespawnCommandConsumer.LeaseTtl).flatMap {
      case false => Future.unit // somebody already has it
      case true =>
        handled.add(id)
        cache.get(RespawnCommand.requestKey(guildId, id)).flatMap {
          case None =>
            // Expired between listing and reading. Nothing to do and nobody
            // waiting by now.
            Future.unit
          case Some(raw) =>
            RespawnCommand.fromJson(raw) match {
              case None =>
                logger.warn(s"Dropping unreadable respawn command '$id' for guild '$guildId'")
                reply(id, ActionResult(ok = false, "That instruction could not be understood."))
              case Some(command) if !permitted(command) =>
                // Refused here rather than by whoever sent it, because only this
                // process is in the guild and can tell. Answered plainly: the
                // command was delivered and understood, and the person is simply
                // not entitled to it.
                logger.info(s"Refusing relayed '${command.action}' in guild '$guildId': " +
                  s"'${command.actorId}' is not ${RespawnCommand.requiredTier(command.action).name} there")
                reply(id, ActionResult(ok = false,
                  "You don't have permission to do that on this server."))
              case Some(command) =>
                execute(command).flatMap(result => reply(id, result)).recoverWith {
                  case NonFatal(e) =>
                    logger.warn(s"Relayed '${command.action}' failed for guild '$guildId': ${e.getMessage}", e)
                    reply(id, ActionResult(ok = false, "Something went wrong doing that."))
                }
            }
        }
    }.recover {
      case NonFatal(e) => logger.warn(s"Could not handle respawn command '$id': ${e.getMessage}")
    }

  /** Whether the person named on a command may have it done as them.
   *
   *  Resolved live, every time, and never from anything remembered: this is what
   *  actually grants the write, and somebody who lost the moderator role a
   *  minute ago must be refused now.
   *
   *  A resolution that throws is a refusal. The alternative — performing it
   *  because the check could not be made — is the one failure that cannot be
   *  taken back. */
  private def permitted(command: RespawnCommand): Boolean =
    try resolve(command.guildId, command.actorId)
      .exists(_.tier.atLeast(RespawnCommand.requiredTier(command.action)))
    catch {
      case NonFatal(e) =>
        logger.warn(s"Could not resolve '${command.actorId}' in guild '${command.guildId}', " +
          s"refusing '${command.action}': ${e.getMessage}")
        false
    }

  private def reply(id: String, result: ActionResult): Future[Unit] =
    cache.setEx(RespawnCommand.replyKey(id), RespawnCommand.resultToJson(result),
      RespawnCommandConsumer.ReplyTtl)

  /** Maps a command onto the local port. Anything it cannot satisfy is answered
   *  rather than ignored, so the caller learns why instead of timing out. */
  private[web] def execute(command: RespawnCommand): Future[ActionResult] = {
    val guildId = command.guildId
    val actor = command.actorId
    def missing(what: String) = Future.successful(ActionResult(ok = false, s"That instruction had no $what."))
    // The instant a calendar slot starts on, which is how one day is named
    // across the wire — a predicted slot has no row and so no id to send.
    def slotStart(c: RespawnCommand) = c.param("startsAt")
      .flatMap(s => scala.util.Try(java.time.Instant.parse(s).atZone(java.time.ZoneOffset.UTC)).toOption)

    command.action match {
      case RespawnCommand.Claim =>
        command.param("code").fold(missing("spawn"))(code =>
          local.claim(guildId, actor, command.param("character").getOrElse(""), code, command.intParam("minutes")))

      case RespawnCommand.Release =>
        local.release(guildId, actor, command.param("code"))

      case RespawnCommand.Extend =>
        command.intParam("minutes").filter(_ > 0).fold(missing("length"))(local.extend(guildId, actor, _))

      case RespawnCommand.Book =>
        val parsed = for {
          code <- command.param("code")
          start <- command.param("startsAt")
            .flatMap(s => scala.util.Try(java.time.Instant.parse(s).atZone(java.time.ZoneOffset.UTC)).toOption)
          minutes <- command.intParam("minutes").filter(_ > 0)
        } yield (code, start, minutes)
        parsed.fold(missing("time to book")) { case (code, start, minutes) =>
          local.book(guildId, actor, command.param("character").getOrElse(""), code, start, minutes,
            command.intParam("days").getOrElse(com.tibiabot.domain.RespawnSchedule.OneOff))
        }

      case RespawnCommand.CancelBooking =>
        command.longParam("scheduleId").fold(missing("booking"))(local.cancelBooking(guildId, actor, _))

      case RespawnCommand.ForceLeave =>
        command.param("code").fold(missing("spawn"))(local.forceLeave(guildId, actor, _))

      case RespawnCommand.Reassign =>
        (command.param("code"), command.param("toUserId")) match {
          case (Some(code), Some(to)) => local.reassign(guildId, actor, code, to)
          case _ => missing("spawn and recipient")
        }

      case RespawnCommand.GrantStamina =>
        (command.param("userId"), command.intParam("minutes")) match {
          case (Some(target), Some(minutes)) => local.grantStamina(guildId, actor, target, minutes)
          case _ => missing("person and amount")
        }

      case RespawnCommand.AddSpawn =>
        (command.param("code"), command.param("name")) match {
          case (Some(code), Some(name)) =>
            // Region and creature are genuinely optional — a spawn with no city
            // is grouped under "Elsewhere" and one with no creature simply has
            // no picture — so an absent field becomes empty rather than a
            // refusal. Blank values are dropped on the way out (see
            // RelayedRespawnActions.send), which is why they arrive as absent.
            local.addSpawn(guildId, actor, code,
              command.param("region").getOrElse(""), name, command.param("creature").getOrElse(""))
          case _ => missing("code and name")
        }

      case RespawnCommand.ExtendHolder =>
        (command.param("code"), command.intParam("minutes").filter(_ > 0)) match {
          case (Some(code), Some(minutes)) => local.extendHolder(guildId, actor, code, minutes)
          case _ => missing("spawn and length")
        }

      case RespawnCommand.RemoveSpawn =>
        command.param("code").fold(missing("spawn"))(local.removeSpawn(guildId, actor, _))

      case RespawnCommand.DropSlot =>
        (command.param("code"), slotStart(command)) match {
          case (Some(code), Some(start)) => local.dropSlot(guildId, actor, code, start)
          case _ => missing("spawn and day")
        }

      case RespawnCommand.ReassignSlot =>
        (command.param("code"), slotStart(command), command.param("toUserId")) match {
          case (Some(code), Some(start), Some(to)) => local.reassignSlot(guildId, actor, code, start, to)
          case _ => missing("spawn, day and somebody to give it to")
        }

      // Unreachable via fromJson, which refuses unknown actions — kept so a
      // future action added to the set without a branch here fails loudly
      // rather than silently doing nothing.
      case other =>
        logger.warn(s"No handler for relayed action '$other'")
        Future.successful(ActionResult(ok = false, "This bot does not know how to do that yet."))
    }
  }
}

object RespawnCommandConsumer {
  /** Held while a command runs. Comfortably longer than any single write, and
   *  short enough that a process which died holding one does not block the id
   *  forever — though nothing will retry it either, by design. */
  val LeaseTtl: FiniteDuration = 2.minutes

  /** A reply only has to outlive the caller's wait. */
  val ReplyTtl: FiniteDuration = 2.minutes

  /** How often to look. A relayed write costs about this much latency on top of
   *  the work itself, which is why it is a second rather than five. */
  val SweepEvery: FiniteDuration = 1.second
}
