package com.tibiabot.web

import spray.json._

/** A write, addressed to whichever bot runs a guild's respawns.
 *
 *  Several bot identities can share a guild, and only the one that built its
 *  respawn forum runs the lifecycle — so a dashboard served by one bot cannot
 *  perform a write in a guild another owns. Rather than refusing, the write is
 *  written to Redis and the owning process performs it.
 *
 *  Deliberately a flat bag of strings rather than a typed hierarchy per action.
 *  This crosses a process boundary between two builds that may not match: a
 *  field a newer version adds is simply absent to an older one, which is the
 *  failure mode we want. A sealed hierarchy would refuse to decode instead.
 */
final case class RespawnCommand(
  id: String,
  guildId: String,
  action: String,
  /** Who is asking. Every action is performed *as* this person, never as the
   *  bot — the executing process re-checks nothing about permissions, so the
   *  issuing process is trusted to have done that, and does. */
  actorId: String,
  params: Map[String, String]
) {
  def param(key: String): Option[String] = params.get(key).map(_.trim).filter(_.nonEmpty)
  def intParam(key: String): Option[Int] = param(key).flatMap(v => scala.util.Try(v.toInt).toOption)
  def longParam(key: String): Option[Long] = param(key).flatMap(v => scala.util.Try(v.toLong).toOption)

  def toJson: String = JsObject(
    "id" -> JsString(id),
    "guildId" -> JsString(guildId),
    "action" -> JsString(action),
    "actorId" -> JsString(actorId),
    "params" -> JsObject(params.map { case (k, v) => k -> (JsString(v): JsValue) })
  ).compactPrint
}

object RespawnCommand {
  val Claim = "claim"
  val Release = "release"
  val Extend = "extend"
  val Book = "book"
  val CancelBooking = "cancel-booking"
  val ForceLeave = "force-leave"
  val Reassign = "reassign"
  val GrantStamina = "grant-stamina"
  val AddSpawn = "add-spawn"
  val RemoveSpawn = "remove-spawn"
  val ExtendHolder = "extend-holder"
  val DropSlot = "drop-slot"
  val ReassignSlot = "reassign-slot"

  /** Every action a relayed command may name. An unrecognised one is answered
   *  rather than executed, so a newer build asking for something this one has
   *  never heard of fails visibly instead of silently doing nothing. */
  val Actions: Set[String] =
    Set(Claim, Release, Extend, Book, CancelBooking, ForceLeave, Reassign, GrantStamina,
        AddSpawn, RemoveSpawn, ExtendHolder, DropSlot, ReassignSlot)

  /** None on anything malformed. A command that cannot be read is dropped by
   *  the consumer and times out for the caller, which is the right outcome:
   *  guessing at a half-understood write would be worse than not doing it. */
  def fromJson(raw: String): Option[RespawnCommand] =
    scala.util.Try {
      val fields = raw.parseJson.asJsObject.fields
      def str(key: String) = fields.get(key).collect { case JsString(v) => v }
      for {
        id <- str("id").filter(_.nonEmpty)
        guildId <- str("guildId").filter(_.nonEmpty)
        action <- str("action").filter(Actions.contains)
        actorId <- str("actorId").filter(_.nonEmpty)
      } yield RespawnCommand(id, guildId, action, actorId,
        fields.get("params").collect { case JsObject(p) => p }.getOrElse(Map.empty).collect {
          case (key, JsString(value)) => key -> value
        })
    }.toOption.flatten

  def resultToJson(result: ActionResult): String =
    JsObject("ok" -> JsBoolean(result.ok), "message" -> JsString(result.message)).compactPrint

  def resultFromJson(raw: String): Option[ActionResult] =
    scala.util.Try {
      val fields = raw.parseJson.asJsObject.fields
      for {
        ok <- fields.get("ok").collect { case JsBoolean(v) => v }
        message <- fields.get("message").collect { case JsString(v) => v }
      } yield ActionResult(ok, message)
    }.toOption.flatten

  /** Where a command waits to be picked up. The guild is in the key so a
   *  consumer can tell what it owns without reading every payload. */
  def requestKey(guildId: String, id: String): String = s"tibia:respawn-cmd:$guildId:$id"
  def requestPattern: String = "tibia:respawn-cmd:*"

  /** Guild and command id back out of a request key. None if the key isn't one
   *  of ours — a shared Redis holds other things. */
  def parseRequestKey(key: String): Option[(String, String)] = key.split(':') match {
    case Array("tibia", "respawn-cmd", guildId, id) if guildId.nonEmpty && id.nonEmpty => Some(guildId -> id)
    case _ => None
  }

  /** Held by whichever process is executing a command. Winning this is what
   *  gives a single executor; see `RedisCache.setIfAbsent`. */
  def leaseKey(id: String): String = s"tibia:respawn-lease:$id"

  def replyKey(id: String): String = s"tibia:respawn-reply:$id"
}
