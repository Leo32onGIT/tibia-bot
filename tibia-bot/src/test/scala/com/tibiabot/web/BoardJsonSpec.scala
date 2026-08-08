package com.tibiabot.web

import com.tibiabot.domain.{Respawn, RespawnClaim}
import com.tibiabot.respawn.RespawnBoardEntry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import java.time.ZonedDateTime

class BoardJsonSpec extends AnyWordSpec with Matchers {

  private val now = ZonedDateTime.parse("2026-08-07T12:00:00Z")

  private def spawn(creature: String = "Dragon") =
    Respawn(1, "415", "Cult Orcs", creature, "Edron", "", "", "", Respawn.SourceSeed, "seed")

  private def claim(status: String, startsAt: Option[ZonedDateTime] = None,
                    endsAt: Option[ZonedDateTime] = None, character: String = "") =
    RespawnClaim(1, 1, "u1", "Nubbz", character, status, 0, now, startsAt, endsAt,
      120, warned = false, RespawnClaim.KindAdHoc, None, None, None, None)

  private def json(entry: RespawnBoardEntry, tier: AccessTier = AccessTier.Member,
                   viewerId: String = "someone-else") =
    RespawnDashboardRoute.boardJson(List(entry), tier, viewerId, None)

  private def firstSpawn(o: JsObject): JsObject =
    o.fields("spawns").asInstanceOf[JsArray].elements.head.asJsObject

  "boardJson" should {

    "carry the visitor's tier so the page can hide tools it would be refused" in {
      json(RespawnBoardEntry(spawn(), None, Nil, Nil, None), AccessTier.Moderator)
        .fields("tier") shouldBe JsString("moderator")
    }

    "describe a free spawn without inventing a holder" in {
      val fields = firstSpawn(json(RespawnBoardEntry(spawn(), None, Nil, Nil, None))).fields
      fields("code") shouldBe JsString("415")
      fields("name") shouldBe JsString("Cult Orcs")
      fields("state") shouldBe JsString("free")
      fields.contains("holder") shouldBe false
      fields.contains("endsAt") shouldBe false
    }

    // The page draws the progress bar and keeps it moving between polls, so it
    // needs both ends rather than a minutes-remaining that is stale on arrival.
    "give both ends of a live hunt rather than a countdown" in {
      val held = claim(RespawnClaim.StatusActive,
        startsAt = Some(now.minusMinutes(30)), endsAt = Some(now.plusMinutes(90)), character = "Bubble")
      val fields = firstSpawn(json(RespawnBoardEntry(spawn(), Some(held), Nil, Nil, None))).fields
      fields("state") shouldBe JsString("claimed")
      fields("holder") shouldBe JsString("Bubble")
      fields("startsAt") shouldBe JsString("2026-08-07T11:30:00Z")
      fields("endsAt") shouldBe JsString("2026-08-07T13:30:00Z")
    }

    "omit the window for a hunt missing either end rather than guessing one" in {
      val held = claim(RespawnClaim.StatusActive, startsAt = Some(now), endsAt = None)
      val fields = firstSpawn(json(RespawnBoardEntry(spawn(), Some(held), Nil, Nil, None))).fields
      fields("state") shouldBe JsString("claimed")
      fields.contains("startsAt") shouldBe false
      fields.contains("endsAt") shouldBe false
    }

    "point the sprite at our own domain, never the wiki" in {
      val fields = firstSpawn(json(RespawnBoardEntry(spawn("Orc_Warlord"), None, Nil, Nil, None))).fields
      fields("sprite") shouldBe JsString("/dashboard/sprites/Orc_Warlord.gif")
    }

    // The page falls back to the avatar placeholder, which is why absence is
    // fine and a broken URL would not be.
    "omit the sprite for a spawn with no creature set" in {
      firstSpawn(json(RespawnBoardEntry(spawn(""), None, Nil, Nil, None))).fields.contains("sprite") shouldBe false
    }

    "omit the sprite rather than emit an unsafe name" in {
      firstSpawn(json(RespawnBoardEntry(spawn("../../etc/passwd"), None, Nil, Nil, None)))
        .fields.contains("sprite") shouldBe false
    }

    "report the queue length" in {
      val queue = List(claim(RespawnClaim.StatusQueued), claim(RespawnClaim.StatusQueued))
      firstSpawn(json(RespawnBoardEntry(spawn(), None, queue, Nil, None)))
        .fields("queueLength") shouldBe JsNumber(2)
    }

    "give the next booking's start so the card can say when" in {
      val slot = claim(RespawnClaim.StatusReserved, startsAt = Some(now.plusHours(3)))
      val fields = firstSpawn(json(RespawnBoardEntry(spawn(), None, Nil, List(slot), None))).fields
      fields("state") shouldBe JsString("booked")
      fields("nextAt") shouldBe JsString("2026-08-07T15:00:00Z")
    }

    "send last activity as an instant for the fade" in {
      val fields = firstSpawn(json(RespawnBoardEntry(spawn(), None, Nil, Nil, Some(now.minusDays(3))))).fields
      fields("lastActivity") shouldBe JsString("2026-08-04T12:00:00Z")
    }

    "omit last activity for a spawn nobody has ever claimed" in {
      firstSpawn(json(RespawnBoardEntry(spawn(), None, Nil, Nil, None)))
        .fields.contains("lastActivity") shouldBe false
    }

    // Rendered in the reader's own zone, so everything time-like has to be an
    // absolute instant rather than anything pre-formatted here.
    "send every time as a UTC instant" in {
      val held = claim(RespawnClaim.StatusActive, startsAt = Some(now), endsAt = Some(now.plusHours(2)))
      val entry = RespawnBoardEntry(spawn(), Some(held), Nil, Nil, Some(now.minusDays(1)))
      val fields = firstSpawn(json(entry)).fields
      List("startsAt", "endsAt", "lastActivity").foreach { key =>
        fields(key).asInstanceOf[JsString].value should endWith("Z")
      }
    }

    "handle an empty board" in {
      val out = RespawnDashboardRoute.boardJson(Nil, AccessTier.Member, "u1", None)
      out.fields("spawns") shouldBe JsArray()
    }
  }

  // Which button a card offers turns on these, and the holder's *name* cannot
  // answer it: two people can share a character name, and a viewer with no
  // character set has none to compare against.
  "viewer identity" should {

    "mark a spawn the viewer is holding" in {
      val held = claim(RespawnClaim.StatusActive, startsAt = Some(now), endsAt = Some(now.plusHours(2)))
      firstSpawn(json(RespawnBoardEntry(spawn(), Some(held), Nil, Nil, None), viewerId = "u1"))
        .fields("mine") shouldBe JsBoolean(true)
    }

    "not mark somebody else's hold as the viewer's" in {
      val held = claim(RespawnClaim.StatusActive, startsAt = Some(now), endsAt = Some(now.plusHours(2)))
      firstSpawn(json(RespawnBoardEntry(spawn(), Some(held), Nil, Nil, None), viewerId = "u2"))
        .fields("mine") shouldBe JsBoolean(false)
    }

    "mark a queue the viewer is standing in" in {
      val queued = List(claim(RespawnClaim.StatusQueued))
      val fields = firstSpawn(json(RespawnBoardEntry(spawn(), None, queued, Nil, None), viewerId = "u1")).fields
      fields("queued") shouldBe JsBoolean(true)
      fields("mine") shouldBe JsBoolean(false)
    }

    "not mark a queue the viewer is not in" in {
      val queued = List(claim(RespawnClaim.StatusQueued))
      firstSpawn(json(RespawnBoardEntry(spawn(), None, queued, Nil, None), viewerId = "u2"))
        .fields("queued") shouldBe JsBoolean(false)
    }

    "mark a booking the viewer made" in {
      val slot = claim(RespawnClaim.StatusReserved, startsAt = Some(now.plusHours(2)))
      firstSpawn(json(RespawnBoardEntry(spawn(), None, Nil, List(slot), None), viewerId = "u1"))
        .fields("booked") shouldBe JsBoolean(true)
    }
  }

  "limits" should {

    "be absent when the guild never set the respawn system up" in {
      RespawnDashboardRoute.boardJson(Nil, AccessTier.Member, "u1", None)
        .fields.contains("limits") shouldBe false
    }

    "cap a claim at what stamina allows when that is the tighter limit" in {
      val limits = BoardLimits(Some(95), Some(240), maxDurationMinutes = 240,
        defaultDurationMinutes = 120, resetsAt = now)
      val out = RespawnDashboardRoute.boardJson(Nil, AccessTier.Member, "u1", Some(limits))
        .fields("limits").asJsObject.fields
      // 95 minutes left snaps down to a whole 30, and stamina is named as the
      // limit doing the stopping.
      out("claimableMinutes") shouldBe JsNumber(90)
      out("boundBy") shouldBe JsString("stamina")
    }

    "cap at the server's ceiling when stamina is the looser limit" in {
      val limits = BoardLimits(Some(600), Some(600), maxDurationMinutes = 240,
        defaultDurationMinutes = 120, resetsAt = now)
      val out = RespawnDashboardRoute.boardJson(Nil, AccessTier.Member, "u1", Some(limits))
        .fields("limits").asJsObject.fields
      out("claimableMinutes") shouldBe JsNumber(240)
      out("boundBy") shouldBe JsString("server limit")
    }

    // Stamina.remainingMinutes reports Int.MaxValue when a guild has it off,
    // which must not reach the page as a figure.
    "omit the tank entirely when the guild has stamina switched off" in {
      val limits = BoardLimits(None, None, maxDurationMinutes = 240,
        defaultDurationMinutes = 120, resetsAt = now)
      val out = RespawnDashboardRoute.boardJson(Nil, AccessTier.Member, "u1", Some(limits))
        .fields("limits").asJsObject.fields
      out.contains("remainingMinutes") shouldBe false
      out("claimableMinutes") shouldBe JsNumber(240)
    }

    "leave nothing claimable when the tank is empty" in {
      val limits = BoardLimits(Some(10), Some(240), maxDurationMinutes = 240,
        defaultDurationMinutes = 120, resetsAt = now)
      RespawnDashboardRoute.boardJson(Nil, AccessTier.Member, "u1", Some(limits))
        .fields("limits").asJsObject.fields("claimableMinutes") shouldBe JsNumber(0)
    }
  }
}
