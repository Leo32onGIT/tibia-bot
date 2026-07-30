package com.tibiabot.persistence

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSettings, RespawnUserPrefs}
import com.tibiabot.persistence.jdbc.JdbcRespawnRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{ZoneOffset, ZonedDateTime}

/** Round-trips the respawn claim tables against a real Postgres (cancels without
 *  PGHOST).
 *
 *  This is where the feature's SQL is actually executed: the DDL, the
 *  `RETURNING *` inserts, the `make_interval` deadline arithmetic and the
 *  lazily-resetting stamina rows are all things a Scala-level unit test can't
 *  reach.
 */
class RespawnRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "888000888000888111" // numeric-only fake guild id
  private val now = ZonedDateTime.parse("2026-07-30T12:00:00Z")
  private val boundary = ZonedDateTime.parse("2026-07-30T08:00:00Z") // a server save

  private def freshRepo(): (JdbcRespawnRepository, String) = {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    val repo = new JdbcRespawnRepository(provider)
    // Creates the tables on first use, then clears anything a previous run left.
    repo.listRespawns(guildId).foreach(r => repo.removeRespawn(guildId, r.id))
    (repo, guildId)
  }

  test("settings round-trip, including the channel-only update") {
    val (repo, g) = freshRepo()
    val settings = RespawnSettings("0", "0", 120, 240, 20, 240, 10, 10)
    repo.saveSettings(g, settings)
    repo.settings(g) shouldBe Some(settings)

    // saveSettings is an upsert on a fixed id — a second call must not create a
    // second row or the read would become non-deterministic.
    repo.saveSettings(g, settings.copy(queueLimit = 5))
    repo.settings(g).map(_.queueLimit) shouldBe Some(5)

    repo.updateChannels(g, "111", "222")
    repo.settings(g).map(s => (s.forumChannel, s.boardThread)) shouldBe Some(("111", "222"))
    repo.settings(g).map(_.queueLimit) shouldBe Some(5) // untouched
  }

  test("catalogue: add, find by code and id, edit one field, remove") {
    val (repo, g) = freshRepo()
    val added = repo.addRespawn(g, "415", "Cult Orcs", "Orc Cult Fanatic", "Edron", "", "", Respawn.SourceSeed, "seed")
    added.code shouldBe "415"
    added.displayName shouldBe "415 — Cult Orcs"

    repo.findByCode(g, "415").map(_.name) shouldBe Some("Cult Orcs")
    repo.findByCode(g, "415").map(_.id) shouldBe Some(added.id)
    repo.findById(g, added.id).map(_.code) shouldBe Some("415")
    repo.findByCode(g, "nope") shouldBe None

    // A partial edit must leave the fields it wasn't given alone.
    repo.updateRespawn(g, added.id, name = None, creature = Some("Orc Cult Priest"), world = None, mapperLink = None)
    val edited = repo.findById(g, added.id)
    edited.map(_.creature) shouldBe Some("Orc Cult Priest")
    edited.map(_.name) shouldBe Some("Cult Orcs")

    repo.removeRespawn(g, added.id)
    repo.findByCode(g, "415") shouldBe None
  }

  test("codes are matched case-insensitively") {
    val (repo, g) = freshRepo()
    repo.addRespawn(g, "1415a", "Fury dungeon", "Fury", "Rathleton", "", "", Respawn.SourceSeed, "seed")
    repo.findByCode(g, "1415A").map(_.name) shouldBe Some("Fury dungeon")
  }

  test("re-adding an existing code returns the stored row without overwriting it") {
    val (repo, g) = freshRepo()
    val first = repo.addRespawn(g, "806", "Hydra Mountain", "Hydra", "Port Hope", "", "", Respawn.SourceSeed, "seed")
    // This is what makes `/respawn admin seed` safe to re-run over a guild's
    // own edits.
    val second = repo.addRespawn(g, "806", "SOMETHING ELSE", "Wrong", "Nowhere", "", "", Respawn.SourceCustom, "x")
    second.id shouldBe first.id
    second.name shouldBe "Hydra Mountain"
    second.creature shouldBe "Hydra"
  }

  test("seed import inserts only codes the guild doesn't already have") {
    val (repo, g) = freshRepo()
    repo.addRespawn(g, "415", "My Own Name", "", "Edron", "", "", Respawn.SourceCustom, "admin")

    val batch = List(("415", "Edron", "Cult Orcs", "Orc Cult Fanatic"), ("806", "Port Hope", "Hydra Mountain", "Hydra"))
    repo.importSeed(g, batch) shouldBe 1
    repo.findByCode(g, "415").map(_.name) shouldBe Some("My Own Name") // preserved
    repo.findByCode(g, "806").map(_.name) shouldBe Some("Hydra Mountain")

    repo.importSeed(g, batch) shouldBe 0 // idempotent
  }

  test("syncSeedCreatures updates seed rows, and only what actually changed") {
    val (repo, g) = freshRepo()
    repo.importSeed(g, List(("415", "Edron", "Cult Orcs", ""), ("806", "Port Hope", "Hydra Mountain", "Hydra")))

    // A better list arrives: 415 gains a creature, 806 already matches.
    repo.syncSeedCreatures(g, List(("415", "Orc Cult Fanatic"), ("806", "Hydra"))) shouldBe 1
    repo.findByCode(g, "415").map(_.creature) shouldBe Some("Orc Cult Fanatic")

    // Idempotent, which is what makes this safe to run on every boot.
    repo.syncSeedCreatures(g, List(("415", "Orc Cult Fanatic"), ("806", "Hydra"))) shouldBe 0
  }

  test("syncSeedCreatures leaves a hand-picked creature and a guild's own spawn alone") {
    val (repo, g) = freshRepo()
    repo.importSeed(g, List(("415", "Edron", "Cult Orcs", "Orc Cult Fanatic")))
    val own = repo.addRespawn(g, "999", "Our Spot", "Demon", "Home", "", "", Respawn.SourceCustom, "admin")

    // An admin picks a different monster in Discord: that pins the row.
    val seeded = repo.findByCode(g, "415").get
    repo.updateRespawn(g, seeded.id, name = None, creature = Some("Orc Cult Priest"),
      world = None, mapperLink = None)

    repo.syncSeedCreatures(g, List(("415", "Orc Cult Fanatic"), ("999", "Something Else"))) shouldBe 0
    // Their choice survives the next deploy...
    repo.findByCode(g, "415").map(_.creature) shouldBe Some("Orc Cult Priest")
    // ...and a spawn the guild added itself is never seed-managed at all.
    repo.findById(g, own.id).map(_.creature) shouldBe Some("Demon")
  }

  test("editing a name doesn't pin the creature") {
    val (repo, g) = freshRepo()
    repo.importSeed(g, List(("415", "Edron", "Cult Orcs", "")))
    val seeded = repo.findByCode(g, "415").get

    // Renaming is not a statement about which monster belongs on the card, so it
    // must not opt the row out of future image improvements.
    repo.updateRespawn(g, seeded.id, name = Some("Cult Orcs (east)"), creature = None,
      world = None, mapperLink = None)
    repo.syncSeedCreatures(g, List(("415", "Orc Cult Fanatic"))) shouldBe 1
    repo.findByCode(g, "415").map(_.creature) shouldBe Some("Orc Cult Fanatic")
    repo.findByCode(g, "415").map(_.name) shouldBe Some("Cult Orcs (east)")
  }

  test("an active claim gets a deadline and is found as the spawn's holder") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")

    repo.activeClaim(g, spawn.id) shouldBe None
    val claim = repo.insertActiveClaim(g, spawn.id, "u1", "One", "Char", now, now.plusMinutes(120), 120,
      RespawnClaim.KindAdHoc)
    claim.status shouldBe RespawnClaim.StatusActive
    claim.endsAt.map(_.toInstant) shouldBe Some(now.plusMinutes(120).toInstant)

    repo.activeClaim(g, spawn.id).map(_.userId) shouldBe Some("u1")
    repo.allActiveClaims(g).map(_.userId) should contain("u1")
    repo.openClaimsForUser(g, "u1").map(_.respawnId) shouldBe List(spawn.id)
  }

  test("queueing assigns increasing positions and refuses duplicates and a full queue") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    repo.insertActiveClaim(g, spawn.id, "holder", "H", "", now, now.plusMinutes(60), 60, RespawnClaim.KindAdHoc)

    repo.enqueueClaim(g, spawn.id, "u1", "One", "", 120, 2, RespawnClaim.KindAdHoc).map(_.queuePosition) shouldBe Some(1)
    repo.enqueueClaim(g, spawn.id, "u2", "Two", "", 60, 2, RespawnClaim.KindAdHoc).map(_.queuePosition) shouldBe Some(2)

    // Already queued.
    repo.enqueueClaim(g, spawn.id, "u1", "One", "", 120, 5, RespawnClaim.KindAdHoc) shouldBe None
    // Already holding it.
    repo.enqueueClaim(g, spawn.id, "holder", "H", "", 60, 5, RespawnClaim.KindAdHoc) shouldBe None
    // Queue full at the limit of 2.
    repo.enqueueClaim(g, spawn.id, "u3", "Three", "", 60, 2, RespawnClaim.KindAdHoc) shouldBe None

    repo.queueFor(g, spawn.id).map(_.userId) shouldBe List("u1", "u2")
  }

  test("a queued claim must be offered before it can be promoted") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val queued = repo.enqueueClaim(g, spawn.id, "u1", "One", "", 90, 20, RespawnClaim.KindAdHoc).get

    // Straight from the queue is refused: a spawn is never handed to somebody
    // who hasn't confirmed they still want it.
    repo.promoteClaim(g, queued.id, now) shouldBe None

    val offered = repo.offerClaim(g, queued.id, now.plusMinutes(10))
    offered.map(_.status) shouldBe Some(RespawnClaim.StatusOffered)
    offered.flatMap(_.offerExpiresAt).map(_.toInstant) shouldBe Some(now.plusMinutes(10).toInstant)
    // An offered claim is no longer in the visible queue, but still counts as
    // something its owner holds.
    repo.queueFor(g, spawn.id) shouldBe empty
    repo.offeredClaim(g, spawn.id).map(_.userId) shouldBe Some("u1")
    repo.openClaimsForUser(g, "u1").map(_.id) shouldBe List(queued.id)

    val promoted = repo.promoteClaim(g, queued.id, now)
    promoted.map(_.status) shouldBe Some(RespawnClaim.StatusActive)
    promoted.map(_.queuePosition) shouldBe Some(0)
    // 90 minutes, from the row's own duration — not the 120-minute default.
    promoted.flatMap(_.endsAt).map(_.toInstant) shouldBe Some(now.plusMinutes(90).toInstant)
    promoted.flatMap(_.offerExpiresAt) shouldBe None

    // Accepting twice must not resurrect the claim or double-charge stamina.
    repo.promoteClaim(g, queued.id, now) shouldBe None
  }

  test("only a queued claim can be offered, and only once") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val queued = repo.enqueueClaim(g, spawn.id, "u1", "One", "", 60, 20, RespawnClaim.KindAdHoc).get

    repo.offerClaim(g, queued.id, now.plusMinutes(10)) should not be empty
    // A second offer on the same row would let two sweeps both DM the same
    // person and both hold the spawn open for them.
    repo.offerClaim(g, queued.id, now.plusMinutes(10)) shouldBe None
  }

  test("an offer past its deadline is picked up for cancellation, and only then") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val queued = repo.enqueueClaim(g, spawn.id, "u1", "One", "", 60, 20, RespawnClaim.KindAdHoc).get
    repo.offerClaim(g, queued.id, now.plusMinutes(10))

    repo.expiredOffers(g, now) shouldBe empty
    repo.expiredOffers(g, now.plusMinutes(9)) shouldBe empty
    repo.expiredOffers(g, now.plusMinutes(10)).map(_.userId) shouldBe List("u1")

    repo.cancelClaim(g, queued.id, RespawnClaim.Outcome.Declined)
    repo.expiredOffers(g, now.plusMinutes(30)) shouldBe empty
    repo.offeredClaim(g, spawn.id) shouldBe None
  }

  test("a claim in limbo is not treated as expired until its handover window elapses") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val claim = repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now.minusHours(2), now, 120,
      RespawnClaim.KindAdHoc)

    // Its time is up, so without limbo it's expired work.
    repo.expiredClaims(g, now).map(_.id) shouldBe List(claim.id)

    repo.setLimbo(g, claim.id, now.plusMinutes(10))
    // Held open: still the spawn's holder, and not re-processed every sweep.
    repo.expiredClaims(g, now) shouldBe empty
    repo.expiredClaims(g, now.plusMinutes(5)) shouldBe empty
    repo.activeClaim(g, spawn.id).map(_.userId) shouldBe Some("u1")
    repo.activeClaim(g, spawn.id).flatMap(_.limboUntil).map(_.toInstant) shouldBe
      Some(now.plusMinutes(10).toInstant)

    // Window elapsed: now it ends for real.
    repo.expiredClaims(g, now.plusMinutes(10)).map(_.id) shouldBe List(claim.id)
  }

  test("limbo leaves the deadline alone, so an early release can't be refunded twice") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    // Released early: two hours still on the clock.
    val claim = repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now, now.plusHours(2), 120,
      RespawnClaim.KindAdHoc)
    repo.setLimbo(g, claim.id, now.plusMinutes(10))

    val held = repo.activeClaim(g, spawn.id)
    // `ends_at` untouched is what keeps the refund honest — the service reads
    // limboUntil to know the refund has already been settled.
    held.flatMap(_.endsAt).map(_.toInstant) shouldBe Some(now.plusHours(2).toInstant)
    held.map(_.durationMinutes) shouldBe Some(120)

    // A voluntarily released claim has a FUTURE ends_at, so the work list has to
    // find it by its limbo window rather than by its deadline.
    repo.expiredClaims(g, now.plusMinutes(10)).map(_.id) shouldBe List(claim.id)
  }

  test("cancelQueued clears exactly the named users, leaving the rest in order") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    repo.enqueueClaim(g, spawn.id, "u1", "One", "", 60, 20, RespawnClaim.KindAdHoc)
    repo.enqueueClaim(g, spawn.id, "u2", "Two", "", 60, 20, RespawnClaim.KindAdHoc)
    repo.enqueueClaim(g, spawn.id, "u3", "Three", "", 60, 20, RespawnClaim.KindAdHoc)

    repo.cancelQueued(g, spawn.id, Set("u1", "u3"), RespawnClaim.Outcome.NoStamina)
    repo.queueFor(g, spawn.id).map(_.userId) shouldBe List("u2")
    repo.cancelQueued(g, spawn.id, Set.empty, RespawnClaim.Outcome.NoStamina) // no-op, must not throw
  }

  test("expired and soon-to-expire claims are found by their deadlines") {
    val (repo, g) = freshRepo()
    val past = repo.addRespawn(g, "1", "Past", "", "R", "", "", Respawn.SourceSeed, "seed")
    val soon = repo.addRespawn(g, "2", "Soon", "", "R", "", "", Respawn.SourceSeed, "seed")
    val later = repo.addRespawn(g, "3", "Later", "", "R", "", "", Respawn.SourceSeed, "seed")

    repo.insertActiveClaim(g, past.id, "u1", "1", "", now.minusHours(3), now.minusMinutes(5), 175, RespawnClaim.KindAdHoc)
    val soonClaim = repo.insertActiveClaim(g, soon.id, "u2", "2", "", now, now.plusMinutes(5), 5, RespawnClaim.KindAdHoc)
    repo.insertActiveClaim(g, later.id, "u3", "3", "", now, now.plusHours(2), 120, RespawnClaim.KindAdHoc)

    repo.expiredClaims(g, now).map(_.userId) shouldBe List("u1")

    // Every running unwarned claim comes back, whatever its deadline: the lead
    // time is per member now, so there is no single window to filter on in SQL
    // and the service decides which of these are actually due.
    repo.unwarnedActiveClaims(g, now).map(_.userId) shouldBe List("u2", "u3")

    // Warned once, never again — otherwise every 30-second sweep would re-ping.
    repo.markWarned(g, soonClaim.id)
    repo.unwarnedActiveClaims(g, now).map(_.userId) shouldBe List("u3")
  }

  test("a claim handing over is not offered a reminder — its time is already up") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val claim = repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now, now.plusHours(2), 120,
      RespawnClaim.KindAdHoc)
    repo.unwarnedActiveClaims(g, now).map(_.id) shouldBe List(claim.id)

    repo.setLimbo(g, claim.id, now.plusMinutes(10))
    repo.unwarnedActiveClaims(g, now) shouldBe empty
  }

  test("setClaimDuration moves an active claim's deadline, and leaves a queued one without any") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val active = repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now, now.plusMinutes(120), 120,
      RespawnClaim.KindAdHoc)
    repo.markWarned(g, active.id)

    repo.setClaimDuration(g, active.id, 180, Some(now.plusMinutes(180)))
    val grown = repo.activeClaim(g, spawn.id)
    grown.map(_.durationMinutes) shouldBe Some(180)
    grown.flatMap(_.endsAt).map(_.toInstant) shouldBe Some(now.plusMinutes(180).toInstant)
    // Re-armed, so a claim whose deadline moved still gets its reminder.
    grown.map(_.warned) shouldBe Some(false)

    val queued = repo.enqueueClaim(g, spawn.id, "u2", "Two", "", 60, 20, RespawnClaim.KindAdHoc).get
    queued.endsAt shouldBe None
    repo.setClaimDuration(g, queued.id, 90, None)
    val requeued = repo.queueFor(g, spawn.id).find(_.id == queued.id)
    requeued.map(_.durationMinutes) shouldBe Some(90)
    // Still no deadline: inventing one would make the expiry sweep treat a claim
    // that hasn't started as though it had.
    requeued.flatMap(_.endsAt) shouldBe None
  }

  test("claim history is the finished rows, newest first, with why each ended") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")

    // Nothing is deleted, so history is simply the rows that already exist —
    // which is why this needs no separate audit table.
    val first = repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now.minusHours(4),
      now.minusHours(2), 120, RespawnClaim.KindAdHoc)
    repo.finishClaim(g, first.id, RespawnClaim.Outcome.Completed)
    val second = repo.insertActiveClaim(g, spawn.id, "u2", "Two", "", now.minusHours(2),
      now, 120, RespawnClaim.KindAdHoc)
    repo.cancelClaim(g, second.id, RespawnClaim.Outcome.Forced)

    val history = repo.claimHistory(g, spawn.id, 10)
    history.map(_.userId) shouldBe List("u2", "u1")
    history.map(_.outcome) shouldBe List(Some(RespawnClaim.Outcome.Forced),
      Some(RespawnClaim.Outcome.Completed))
    // ended_at is stamped by the database, so the audit shows when it really
    // stopped rather than when it was scheduled to.
    history.flatMap(_.endedAt) should have size 2

    // A claim still running is not history.
    repo.insertActiveClaim(g, spawn.id, "u3", "Three", "", now, now.plusHours(1), 60,
      RespawnClaim.KindAdHoc)
    repo.claimHistory(g, spawn.id, 10).map(_.userId) shouldBe List("u2", "u1")

    repo.claimHistory(g, spawn.id, 1).map(_.userId) shouldBe List("u2")
  }

  test("an already-ended claim keeps its original outcome") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val claim = repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now, now.plusHours(1), 60,
      RespawnClaim.KindAdHoc)

    repo.finishClaim(g, claim.id, RespawnClaim.Outcome.Completed)
    // A late second call — the sweep and a release racing, say — must not relabel
    // why it ended, or the audit trail would depend on ordering.
    repo.cancelClaim(g, claim.id, RespawnClaim.Outcome.Forced)
    repo.claimHistory(g, spawn.id, 10).map(_.outcome) shouldBe
      List(Some(RespawnClaim.Outcome.Completed))
  }

  test("member preferences round-trip, and distinguish 'off' from 'never chose'") {
    val (repo, g) = freshRepo()
    repo.userPrefs(g, "u1") shouldBe RespawnUserPrefs.none("u1")

    repo.saveUserPrefs(g, RespawnUserPrefs("u1", Some(180), Some(15)))
    repo.userPrefs(g, "u1") shouldBe RespawnUserPrefs("u1", Some(180), Some(15))

    // 0 is a real choice (reminders off) and must not read back as unset, which
    // is why the columns are nullable rather than zero-defaulted.
    repo.saveUserPrefs(g, RespawnUserPrefs("u1", Some(180), Some(0)))
    repo.userPrefs(g, "u1").warnMinutes shouldBe Some(0)
    repo.userPrefs(g, "u1").warnMinutesOr(10) shouldBe 0

    // Clearing back to unset means following the guild default again.
    repo.saveUserPrefs(g, RespawnUserPrefs("u1", None, None))
    repo.userPrefs(g, "u1").warnMinutes shouldBe None
    repo.userPrefs(g, "u1").warnMinutesOr(10) shouldBe 10
    repo.userPrefs(g, "u1").defaultDurationOr(120) shouldBe 120

    // A second save replaces the row rather than adding one.
    repo.saveUserPrefs(g, RespawnUserPrefs("u1", Some(60), None))
    repo.userPrefs(g, "u1").defaultDurationMinutes shouldBe Some(60)
  }

  test("member preferences survive teardown — they belong to the member, not the setup") {
    val (repo, g) = freshRepo()
    repo.saveUserPrefs(g, RespawnUserPrefs("u1", Some(180), Some(15)))
    repo.dropGuildData(g)
    repo.userPrefs(g, "u1") shouldBe RespawnUserPrefs("u1", Some(180), Some(15))
  }

  test("extending moves the deadline and re-arms the warning") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val claim = repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now, now.plusMinutes(5), 5, RespawnClaim.KindAdHoc)
    repo.markWarned(g, claim.id)

    repo.extendClaim(g, claim.id, now.plusMinutes(65), 65)
    val extended = repo.activeClaim(g, spawn.id)
    extended.flatMap(_.endsAt).map(_.toInstant) shouldBe Some(now.plusMinutes(65).toInstant)
    extended.map(_.durationMinutes) shouldBe Some(65)
    extended.map(_.warned) shouldBe Some(false)
  }

  test("finished and cancelled claims stop counting as held") {
    val (repo, g) = freshRepo()
    val a = repo.addRespawn(g, "1", "A", "", "R", "", "", Respawn.SourceSeed, "seed")
    val b = repo.addRespawn(g, "2", "B", "", "R", "", "", Respawn.SourceSeed, "seed")
    val finished = repo.insertActiveClaim(g, a.id, "u1", "1", "", now, now.plusHours(1), 60, RespawnClaim.KindAdHoc)
    val cancelled = repo.insertActiveClaim(g, b.id, "u1", "1", "", now, now.plusHours(1), 60, RespawnClaim.KindAdHoc)

    repo.openClaimsForUser(g, "u1") should have size 2
    repo.finishClaim(g, finished.id, RespawnClaim.Outcome.Completed)
    repo.cancelClaim(g, cancelled.id, RespawnClaim.Outcome.Released)
    repo.openClaimsForUser(g, "u1") shouldBe empty
    repo.activeClaim(g, a.id) shouldBe None
  }

  test("stamina reserves, refuses an overdraw, and refunds without going negative") {
    val (repo, g) = freshRepo()
    repo.setStaminaUsed(g, "u1", 0, boundary)

    repo.stamina(g, "u1", 240, boundary).remainingMinutes shouldBe 240
    repo.reserveStamina(g, "u1", 120, 240, boundary) shouldBe true
    repo.reserveStamina(g, "u1", 120, 240, boundary) shouldBe true // exactly empties the 4h tank
    repo.stamina(g, "u1", 240, boundary).remainingMinutes shouldBe 0

    // Nothing is written when it doesn't fit, so a refused claim can't quietly
    // consume part of the tank.
    repo.reserveStamina(g, "u1", 1, 240, boundary) shouldBe false
    repo.stamina(g, "u1", 240, boundary).usedMinutes shouldBe 240

    repo.refundStamina(g, "u1", 60, boundary)
    repo.stamina(g, "u1", 240, boundary).remainingMinutes shouldBe 60
    repo.refundStamina(g, "u1", 9999, boundary)
    repo.stamina(g, "u1", 240, boundary).usedMinutes shouldBe 0
  }

  test("a zero budget means unlimited, not that every claim is refused") {
    val (repo, g) = freshRepo()
    repo.setStaminaUsed(g, "u2", 0, boundary)
    repo.reserveStamina(g, "u2", 10000, 0, boundary) shouldBe true
  }

  test("a stamina row from an earlier server-save day reads as a full tank") {
    val (repo, g) = freshRepo()
    // Yesterday's tank, fully spent.
    repo.setStaminaUsed(g, "u3", 240, boundary.minusDays(1))
    repo.stamina(g, "u3", 240, boundary.minusDays(1)).usedMinutes shouldBe 240

    // Reading against today's boundary resets it lazily — no daily job needed,
    // and a bot that was down over 10:00 still comes back correct.
    repo.stamina(g, "u3", 240, boundary).usedMinutes shouldBe 0
    repo.reserveStamina(g, "u3", 240, 240, boundary) shouldBe true
    repo.stamina(g, "u3", 240, boundary).usedMinutes shouldBe 240
  }

  test("removing a respawn takes its claims with it") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now, now.plusHours(1), 60, RespawnClaim.KindAdHoc)
    repo.enqueueClaim(g, spawn.id, "u2", "Two", "", 60, 20, RespawnClaim.KindAdHoc)

    repo.removeRespawn(g, spawn.id)
    repo.openClaimsForUser(g, "u1") shouldBe empty
    repo.openClaimsForUser(g, "u2") shouldBe empty
  }

  test("thread ids round-trip and can be cleared") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    spawn.threadId shouldBe ""
    repo.setThreadId(g, spawn.id, "99887766")
    repo.findById(g, spawn.id).map(_.threadId) shouldBe Some("99887766")
    repo.setThreadId(g, spawn.id, "")
    repo.findById(g, spawn.id).map(_.threadId) shouldBe Some("")
  }

  test("timestamps survive the round-trip as absolute instants") {
    val (repo, g) = freshRepo()
    val spawn = repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    // Deliberately not UTC: the column is TIMESTAMPTZ, so what comes back must
    // be the same instant regardless of the zone it went in as.
    val inTokyo = now.withZoneSameInstant(ZoneOffset.ofHours(9))
    repo.insertActiveClaim(g, spawn.id, "u1", "One", "", inTokyo, inTokyo.plusMinutes(120), 120, RespawnClaim.KindAdHoc)
    repo.activeClaim(g, spawn.id).flatMap(_.endsAt).map(_.toInstant) shouldBe Some(now.plusMinutes(120).toInstant)
  }

  test("dropGuildData removes claims, catalogue and settings together") {
    val (repo, g) = freshRepo()
    repo.saveSettings(g, RespawnSettings("111", "222", 120, 240, 20, 240, 10, 10))
    val spawn = repo.addRespawn(g, "415", "My Own Name", "Orc Cult Fanatic", "Edron", "", "", Respawn.SourceCustom, "admin")
    repo.setThreadId(g, spawn.id, "5551234")
    repo.insertActiveClaim(g, spawn.id, "u1", "One", "", now, now.plusHours(1), 60, RespawnClaim.KindAdHoc)
    repo.enqueueClaim(g, spawn.id, "u2", "Two", "", 60, 20, RespawnClaim.KindAdHoc)

    repo.dropGuildData(g)

    repo.listRespawns(g) shouldBe empty
    repo.findByCode(g, "415") shouldBe None
    repo.openClaimsForUser(g, "u1") shouldBe empty
    repo.openClaimsForUser(g, "u2") shouldBe empty
    repo.allActiveClaims(g) shouldBe empty
    // No settings row means a later /setup is treated as a first-time setup and
    // builds a fresh forum, rather than adopting the retired archive channel.
    repo.settings(g) shouldBe None
  }

  test("dropGuildData leaves stamina alone — it is per user and per day, not per guild setup") {
    val (repo, g) = freshRepo()
    repo.setStaminaUsed(g, "u1", 120, boundary)
    repo.dropGuildData(g)
    // Removing and re-adding a world the same day must not hand everyone a
    // fresh tank; stamina resets on its own at the next server save.
    repo.stamina(g, "u1", 240, boundary).usedMinutes shouldBe 120
  }

  test("a guild with no database at all reads as unconfigured rather than throwing") {
    val provider = pgOrCancel()
    val repo = new JdbcRespawnRepository(provider)
    // The periodic sweep asks every guild the bot is in for its settings, and
    // guilds that never ran /setup have no `_<guildId>` database — that has to
    // read as "not configured", not blow up once per guild per cycle.
    repo.settings("888000888000888999") shouldBe None
  }

  test("dropGuildData is safe to run twice and on an empty guild") {
    val (repo, g) = freshRepo()
    repo.dropGuildData(g)
    repo.dropGuildData(g)
    repo.listRespawns(g) shouldBe empty
    repo.settings(g) shouldBe None
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }
}
