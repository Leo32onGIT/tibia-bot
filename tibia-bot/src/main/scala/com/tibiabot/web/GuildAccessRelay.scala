package com.tibiabot.web

import spray.json._

/** Asking the bot that runs a guild who somebody is in it.
 *
 *  Several bot identities share this fleet, and a guild's respawns are run by
 *  whichever one built its forum. A dashboard served by one bot can already
 *  *write* into a guild another runs — that is [[RespawnCommand]] — because
 *  everything a write needs is either in the shared database or was decided
 *  before the command was sent.
 *
 *  Reading who somebody is is the one thing that cannot cross that way. A
 *  visitor's tier comes from their roles and from which channels they can see,
 *  and only a bot actually in the guild can be told either. So the dashboard
 *  could show a guild it was not in — its settings, its spawns and its bookings
 *  are all in the shared database — but could never work out whether the person
 *  looking was allowed to, and left it out of the picker entirely.
 *
 *  ==Which way permission travels==
 *  [[RespawnCommand]] states that the *issuer* decides permission and the
 *  executor re-checks nothing. This is the exact reverse: the issuer is the one
 *  that cannot decide, so the answering bot resolves the visitor and the asker
 *  takes what it is given. That inversion is deliberate and worth naming,
 *  because the two messages otherwise look alike. It rests on the same footing
 *  as the write relay — both ends are this bot's own processes on a private
 *  Redis — and it is the safer half of the pair anyway: a write performs
 *  something, where this only ever describes what somebody may already do.
 */
final case class AccessQuery(id: String, guildId: String, userId: String) {
  def toJson: String = JsObject(
    "id" -> JsString(id),
    "guildId" -> JsString(guildId),
    "userId" -> JsString(userId)
  ).compactPrint
}

/** What the owning bot says about a visitor.
 *
 *  `None` for the access is a real answer — "this person cannot use the
 *  dashboard here" — and distinct from no answer at all, which means nobody who
 *  runs that guild was listening. The first excludes the guild from the picker
 *  for good; the second is worth retrying, and the caching reflects that.
 */
final case class AccessAnswer(access: Option[GuildAccess]) {
  def toJson: String = access match {
    case None => JsObject("access" -> JsNull).compactPrint
    case Some(a) => JsObject("access" -> JsObject(
      "guildId" -> JsString(a.guildId),
      "guildName" -> JsString(a.guildName),
      "tier" -> JsString(a.tier.name),
      "worlds" -> JsArray(a.worlds.map(JsString(_)).toVector),
      "iconUrl" -> a.iconUrl.map(JsString(_)).getOrElse(JsNull)
    )).compactPrint
  }
}

object AccessQuery {

  /** None on anything malformed, exactly as a relayed write is dropped rather
   *  than guessed at. A query that cannot be read simply times out for whoever
   *  sent it, and they show one fewer server. */
  def fromJson(raw: String): Option[AccessQuery] =
    scala.util.Try {
      val fields = raw.parseJson.asJsObject.fields
      def str(key: String) = fields.get(key).collect { case JsString(v) => v }.filter(_.nonEmpty)
      for {
        id <- str("id")
        guildId <- str("guildId")
        userId <- str("userId")
      } yield AccessQuery(id, guildId, userId)
    }.toOption.flatten

  def answerFromJson(raw: String): Option[AccessAnswer] =
    scala.util.Try {
      val fields = raw.parseJson.asJsObject.fields
      fields.get("access") match {
        case Some(JsNull) | None => Some(AccessAnswer(None))
        case Some(obj: JsObject) =>
          val f = obj.fields
          def str(key: String) = f.get(key).collect { case JsString(v) => v }
          for {
            guildId <- str("guildId").filter(_.nonEmpty)
            guildName <- str("guildName")
            tier <- str("tier").flatMap(AccessTier.byName)
          } yield AccessAnswer(Some(GuildAccess(
            guildId, guildName, tier,
            f.get("worlds").collect { case JsArray(v) => v.collect { case JsString(w) => w }.toList }
              .getOrElse(Nil),
            str("iconUrl")
          )))
        case _ => None
      }
    }.toOption.flatten

  /** The guild is in the key so a bot can tell at a glance which queries are
   *  its own to answer, without reading every payload — the same reason a
   *  relayed write carries it there. */
  def requestKey(guildId: String, id: String): String = s"tibia:access-q:$guildId:$id"
  def requestPattern: String = "tibia:access-q:*"

  def parseRequestKey(key: String): Option[(String, String)] = key.split(':') match {
    case Array("tibia", "access-q", guildId, id) if guildId.nonEmpty && id.nonEmpty => Some(guildId -> id)
    case _ => None
  }

  def replyKey(id: String): String = s"tibia:access-a:$id"
}

/** The guilds a bot runs the respawns for, published so the others know who to
 *  ask about what.
 *
 *  Without this the asker would have to send a query per guild in the visitor's
 *  Discord account and wait out a timeout on every one nobody runs — hundreds
 *  of keys and several seconds, to find the two that matter. Rosters turn that
 *  into one read and a query only where there is someone to answer it.
 *
 *  Published by every bot rather than only by secondaries: the same code then
 *  serves whichever of them happens to be showing the dashboard, and a fleet
 *  with no secondaries simply reads one roster it can already account for.
 */
final case class GuildRoster(botId: String, guilds: List[RosterGuild]) {
  def toJson: String = JsObject(
    "botId" -> JsString(botId),
    "guilds" -> JsArray(guilds.map(g => JsObject(
      "id" -> JsString(g.id),
      "name" -> JsString(g.name),
      "iconUrl" -> g.iconUrl.map(JsString(_)).getOrElse(JsNull)
    )).toVector)
  ).compactPrint
}

final case class RosterGuild(id: String, name: String, iconUrl: Option[String])

object GuildRoster {
  def fromJson(raw: String): Option[GuildRoster] =
    scala.util.Try {
      val fields = raw.parseJson.asJsObject.fields
      for {
        botId <- fields.get("botId").collect { case JsString(v) => v }.filter(_.nonEmpty)
      } yield GuildRoster(botId,
        fields.get("guilds").collect { case JsArray(v) => v }.getOrElse(Vector.empty).flatMap {
          case obj: JsObject =>
            val f = obj.fields
            def str(key: String) = f.get(key).collect { case JsString(s) => s }
            str("id").filter(_.nonEmpty).map(id =>
              RosterGuild(id, str("name").getOrElse(""), str("iconUrl").filter(_.nonEmpty)))
          case _ => None
        }.toList)
    }.toOption.flatten

  def key(botId: String): String = s"tibia:respawn-roster:$botId"
  def pattern: String = "tibia:respawn-roster:*"
}
