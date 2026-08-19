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
final case class AccessQuery(id: String, guildId: String, userId: String,
                            /** Where to send the answer: the asking bot's id,
                             *  which names its reply channel. Empty from a bot
                             *  old enough to be waiting on a reply *key*
                             *  instead — see [[AccessQuery.replyKey]] — which
                             *  is how the two generations tell each other
                             *  apart during a rolling deploy. */
                            replyTo: String = "") {
  def toJson: String = JsObject(
    "id" -> JsString(id),
    "guildId" -> JsString(guildId),
    "userId" -> JsString(userId),
    "replyTo" -> JsString(replyTo)
  ).compactPrint
}

/** What the owning bot says about a visitor.
 *
 *  `None` for the access is a real answer — "this person cannot use the
 *  dashboard here" — and distinct from no answer at all, which means nobody who
 *  runs that guild was listening. The first excludes the guild from the picker
 *  for good; the second is worth retrying, and the caching reflects that.
 */
final case class AccessAnswer(access: Option[GuildAccess],
                             /** Which question this answers. Carried in the
                              *  body because a reply *channel* is shared by
                              *  every question one bot has out at once, where a
                              *  reply key named exactly one. Empty on the key
                              *  path, where the key is the identifier. */
                             id: String = "") {
  private def withId(fields: Map[String, JsValue]): JsObject =
    JsObject(if (id.isEmpty) fields else fields + ("id" -> JsString(id)))

  def toJson: String = access match {
    case None => withId(Map("access" -> JsNull)).compactPrint
    case Some(a) => withId(Map("access" -> JsObject(
      "guildId" -> JsString(a.guildId),
      "guildName" -> JsString(a.guildName),
      "tier" -> JsString(a.tier.name),
      "worlds" -> JsArray(a.worlds.map(JsString(_)).toVector),
      "iconUrl" -> a.iconUrl.map(JsString(_)).getOrElse(JsNull)
    ))).compactPrint
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
      } yield AccessQuery(id, guildId, userId, str("replyTo").getOrElse(""))
    }.toOption.flatten

  def answerFromJson(raw: String): Option[AccessAnswer] =
    scala.util.Try {
      val fields = raw.parseJson.asJsObject.fields
      val answerId = fields.get("id").collect { case JsString(v) => v }.getOrElse("")
      fields.get("access") match {
        case Some(JsNull) | None => Some(AccessAnswer(None, answerId))
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
          )), answerId)
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

  /** Where a bot listens for questions about the guilds it runs.
   *
   *  Addressed to one bot rather than broadcast, which is the whole saving: the
   *  asker already knows who runs the guild — the roster told it — so there is
   *  nothing to search for and nothing for uninvolved bots to wake up over. The
   *  key-based path had every bot in the fleet running `KEYS` four times a
   *  second across the entire Redis keyspace to find questions that were
   *  usually not theirs.
   */
  def questionChannel(botId: String): String = s"tibia:access-ask:$botId"

  /** Where a bot listens for the answers to its own questions. One channel per
   *  asker rather than one per question, so a subscription is set up once at
   *  startup instead of being taken out and torn down around every lookup —
   *  which is why an answer has to carry its [[AccessAnswer.id]]. */
  def replyChannel(botId: String): String = s"tibia:access-reply:$botId"
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
final case class GuildRoster(botId: String, guilds: List[RosterGuild],
                            /** Whether this bot is actually listening for
                             *  questions on its [[AccessQuery.questionChannel]]
                             *  — set only once the subscription succeeded, so
                             *  it says what is true rather than what this
                             *  version is capable of.
                             *
                             *  This is what lets the two relays coexist while a
                             *  fleet is part-way through a deploy: an asker
                             *  publishes only to a bot that has said it is
                             *  listening, and falls back to the old key for one
                             *  that has not. Once nothing in the fleet
                             *  advertises `false`, no key is ever written and
                             *  the sweep that reads them can go. */
                            pubSub: Boolean = false) {
  def toJson: String = JsObject(
    "botId" -> JsString(botId),
    "pubSub" -> JsBoolean(pubSub),
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
        pubSub = fields.get("pubSub").collect { case JsBoolean(v) => v }.getOrElse(false)
      } yield GuildRoster(botId,
        fields.get("guilds").collect { case JsArray(v) => v }.getOrElse(Vector.empty).flatMap {
          case obj: JsObject =>
            val f = obj.fields
            def str(key: String) = f.get(key).collect { case JsString(s) => s }
            str("id").filter(_.nonEmpty).map(id =>
              RosterGuild(id, str("name").getOrElse(""), str("iconUrl").filter(_.nonEmpty)))
          case _ => None
        }.toList, pubSub)
    }.toOption.flatten

  def key(botId: String): String = s"tibia:respawn-roster:$botId"
  def pattern: String = "tibia:respawn-roster:*"
}
