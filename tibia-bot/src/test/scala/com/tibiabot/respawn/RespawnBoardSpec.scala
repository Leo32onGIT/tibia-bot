package com.tibiabot.respawn

import com.tibiabot.domain.{Respawn, RespawnClaim}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.ZonedDateTime

class RespawnBoardSpec extends AnyWordSpec with Matchers {

  private val now = ZonedDateTime.parse("2026-08-07T12:00:00Z")

  private def spawn(id: Long, code: String) =
    Respawn(id, code, s"Spawn $code", "Dragon", "Edron", "", "", "", Respawn.SourceSeed, "seed")

  private def claim(id: Long, respawnId: Long, status: String, who: String = "u1",
                    queuePosition: Int = 0, startsAt: Option[ZonedDateTime] = None,
                    endsAt: Option[ZonedDateTime] = None) =
    RespawnClaim(id, respawnId, who, who, "", status, queuePosition, now, startsAt, endsAt,
      120, warned = false, RespawnClaim.KindAdHoc, None, None, None, None)

  "assembleBoard" should {

    "return one entry per catalogue row, in catalogue order" in {
      val respawns = List(spawn(1, "415"), spawn(2, "416"), spawn(3, "418"))
      val board = RespawnService.assembleBoard(respawns, Nil, Nil, Nil, Map.empty)
      board.map(_.respawn.code) shouldBe List("415", "416", "418")
    }

    "leave a spawn nobody has touched completely empty rather than absent" in {
      val board = RespawnService.assembleBoard(List(spawn(1, "415")), Nil, Nil, Nil, Map.empty)
      board.head.active shouldBe None
      board.head.queue shouldBe empty
      board.head.reservations shouldBe empty
      // The board's most-faded state, not missing data.
      board.head.lastActivity shouldBe None
    }

    "attach each claim to its own spawn and nobody else's" in {
      val respawns = List(spawn(1, "415"), spawn(2, "416"))
      val board = RespawnService.assembleBoard(
        respawns,
        active = List(claim(10, 1, RespawnClaim.StatusActive, "holder")),
        queued = List(claim(11, 2, RespawnClaim.StatusQueued, "waiter")),
        reserved = Nil, lastActivity = Map.empty)
      board.head.active.map(_.userId) shouldBe Some("holder")
      board.head.queue shouldBe empty
      board(1).active shouldBe None
      board(1).queue.map(_.userId) shouldBe List("waiter")
    }

    "order a queue by its position, whatever order the rows arrive in" in {
      val queued = List(
        claim(3, 1, RespawnClaim.StatusQueued, "third", queuePosition = 3),
        claim(1, 1, RespawnClaim.StatusQueued, "first", queuePosition = 1),
        claim(2, 1, RespawnClaim.StatusQueued, "second", queuePosition = 2))
      val board = RespawnService.assembleBoard(List(spawn(1, "415")), Nil, queued, Nil, Map.empty)
      board.head.queue.map(_.userId) shouldBe List("first", "second", "third")
    }

    "order bookings by when they start" in {
      val reserved = List(
        claim(2, 1, RespawnClaim.StatusReserved, "later", startsAt = Some(now.plusHours(5))),
        claim(1, 1, RespawnClaim.StatusReserved, "sooner", startsAt = Some(now.plusHours(1))))
      val board = RespawnService.assembleBoard(List(spawn(1, "415")), Nil, Nil, reserved, Map.empty)
      board.head.reservations.map(_.userId) shouldBe List("sooner", "later")
    }

    // Should never happen, but the board has to show something sane if it does,
    // and the claim about to end is the one whose end changes the spawn's state.
    "pick the earliest-ending claim if a spawn somehow has two active" in {
      val active = List(
        claim(2, 1, RespawnClaim.StatusActive, "later", endsAt = Some(now.plusHours(3))),
        claim(1, 1, RespawnClaim.StatusActive, "sooner", endsAt = Some(now.plusHours(1))))
      val board = RespawnService.assembleBoard(List(spawn(1, "415")), active, Nil, Nil, Map.empty)
      board.head.active.map(_.userId) shouldBe Some("sooner")
    }

    "not fall over on an active claim with no end recorded" in {
      val active = List(claim(1, 1, RespawnClaim.StatusActive, "endless", endsAt = None))
      val board = RespawnService.assembleBoard(List(spawn(1, "415")), active, Nil, Nil, Map.empty)
      board.head.active.map(_.userId) shouldBe Some("endless")
    }

    "carry last activity through for the fade" in {
      val touched = now.minusDays(3)
      val board = RespawnService.assembleBoard(
        List(spawn(1, "415"), spawn(2, "416")), Nil, Nil, Nil, Map(1L -> touched))
      board.head.lastActivity shouldBe Some(touched)
      board(1).lastActivity shouldBe None
    }

    // Claims for spawns no longer in the catalogue must not invent entries.
    "ignore claims whose spawn has since been removed" in {
      val board = RespawnService.assembleBoard(
        List(spawn(1, "415")),
        active = List(claim(10, 99, RespawnClaim.StatusActive)),
        queued = Nil, reserved = Nil, lastActivity = Map(99L -> now))
      board should have size 1
      board.head.active shouldBe None
    }

    "handle an empty catalogue" in {
      RespawnService.assembleBoard(Nil, Nil, Nil, Nil, Map.empty) shouldBe empty
    }
  }

  "state" should {

    def entry(active: Option[RespawnClaim] = None, reservations: List[RespawnClaim] = Nil) =
      RespawnBoardEntry(spawn(1, "415"), active, Nil, reservations, None)

    def reserved(askedAt: Option[ZonedDateTime] = None, requester: Option[String] = None,
                 startsAt: Option[ZonedDateTime] = Some(now.plusHours(2))) =
      claim(1, 1, RespawnClaim.StatusReserved, startsAt = startsAt)
        .copy(askedAt = askedAt, requesterUserId = requester)

    "read as free with nothing on it" in {
      entry().state shouldBe RespawnBoardEntry.Free
    }

    "read as claimed while somebody holds it" in {
      entry(active = Some(claim(1, 1, RespawnClaim.StatusActive))).state shouldBe RespawnBoardEntry.Claimed
    }

    // A card has one state, and "somebody is in there right now" is the one
    // that matters — a booking for later must not hide a live hunt.
    "let a live hunt outrank a booking for later" in {
      entry(active = Some(claim(1, 1, RespawnClaim.StatusActive)),
            reservations = List(reserved())).state shouldBe RespawnBoardEntry.Claimed
    }

    "read an untouched booking as open to being asked for" in {
      entry(reservations = List(reserved())).state shouldBe RespawnBoardEntry.Booked
    }

    // A question between two people about a future evening, which the card is
    // not the place for: whichever way it is answered, nothing about the spawn
    // this minute changes. The calendar still draws it.
    "look straight past a booking somebody is waiting on an answer for" in {
      entry(reservations = List(reserved(askedAt = Some(now), requester = Some("u2")))).state shouldBe
        RespawnBoardEntry.Free
    }

    "fall through to the next settled booking rather than to free" in {
      val contested = reserved(askedAt = Some(now), requester = Some("u2"))
      val settled = reserved(startsAt = Some(now.plusHours(6)))
      val card = entry(reservations = List(contested, settled))
      card.state shouldBe RespawnBoardEntry.Booked
      card.nextReservation.flatMap(_.startsAt) shouldBe settled.startsAt
    }

    // keepOccurrence clears the requester but deliberately leaves asked_at, so
    // that pair is what "settled" looks like in the data.
    "read an answered booking as confirmed" in {
      entry(reservations = List(reserved(askedAt = Some(now), requester = None))).state shouldBe
        RespawnBoardEntry.Confirmed
    }

    // The other way a booking gets settled: its owner pressed Confirm on the
    // reminder, with nobody having asked at all. Reading the card off asked_at
    // alone would show this one as merely booked.
    "read a booking its owner confirmed as confirmed, with nobody having asked" in {
      entry(reservations = List(reserved().copy(confirmedAt = Some(now)))).state shouldBe
        RespawnBoardEntry.Confirmed
    }

    "always be one of the known states" in {
      List(entry(), entry(active = Some(claim(1, 1, RespawnClaim.StatusActive))),
        entry(reservations = List(reserved())),
        entry(reservations = List(reserved(askedAt = Some(now)))),
        entry(reservations = List(reserved(askedAt = Some(now), requester = Some("u2")))))
        .foreach(e => RespawnBoardEntry.States should contain(e.state))
    }
  }

  "holderLabel" should {
    "name the holder as the guild calls them, not by their character" in {
      val held = claim(1, 1, RespawnClaim.StatusActive, "nubbz").copy(characterName = "Bubble", nickname = "Nubz")
      RespawnBoardEntry(spawn(1, "415"), Some(held), Nil, Nil, None).holderLabel shouldBe Some("Nubz")
    }

    // Every row written before nicknames were kept, which is most of the history.
    "fall back to the account name when the guild has no name for them" in {
      val held = claim(1, 1, RespawnClaim.StatusActive, "Nubbz").copy(characterName = "Bubble")
      RespawnBoardEntry(spawn(1, "415"), Some(held), Nil, Nil, None).holderLabel shouldBe Some("Nubbz")
    }

    // Nothing live looks like this; a card with a blank where a name goes would
    // be worse than one naming the character.
    "fall back to the character when there is no Discord name at all" in {
      val held = claim(1, 1, RespawnClaim.StatusActive, "").copy(characterName = "Bubble")
      RespawnBoardEntry(spawn(1, "415"), Some(held), Nil, Nil, None).holderLabel shouldBe Some("Bubble")
    }

    "name whoever booked it next when nobody is on it" in {
      val slot = claim(1, 1, RespawnClaim.StatusReserved, "Kharsek", startsAt = Some(now.plusHours(1)))
      RespawnBoardEntry(spawn(1, "415"), None, Nil, List(slot), None).holderLabel shouldBe Some("Kharsek")
    }

    "name nobody on a free spawn" in {
      RespawnBoardEntry(spawn(1, "415"), None, Nil, Nil, None).holderLabel shouldBe None
    }
  }
}
