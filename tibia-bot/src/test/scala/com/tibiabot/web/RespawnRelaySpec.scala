package com.tibiabot.web

import akka.actor.ActorSystem
import com.tibiabot.persistence.RedisCache
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/** The two halves of the relay against each other, through a cache that behaves
 *  like Redis in the ways this depends on — chiefly that `setIfAbsent` is
 *  genuinely atomic. */
class RespawnRelaySpec extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  // The reference config only: application.conf pulls in discord.conf, whose
  // substitutions only resolve with the deployment's environment present.
  private implicit val system: ActorSystem =
    ActorSystem("relay-spec", com.typesafe.config.ConfigFactory.defaultReference())
  private implicit val ec: ExecutionContext = system.dispatcher
  override def afterAll(): Unit = { system.terminate(); () }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private class FakeCache extends RedisCache {
    val store = TrieMap.empty[String, String]
    def get(key: String): Future[Option[String]] = Future.successful(store.get(key))
    def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] = {
      store.put(key, value); Future.unit
    }
    /** Atomic, which is the property the whole design leans on. */
    def setIfAbsent(key: String, value: String, ttl: FiniteDuration): Future[Boolean] =
      Future.successful(store.putIfAbsent(key, value).isEmpty)
    def delete(key: String): Future[Unit] = Future.successful { store.remove(key); () }
    def keysMatching(pattern: String): Future[List[String]] = {
      val regex = pattern.replace("*", ".*")
      Future.successful(store.keys.filter(_.matches(regex)).toList)
    }
    def close(): Unit = ()
  }

  /** Counts what it was asked to do, so a test can prove a command ran once. */
  private class CountingActions(answer: ActionResult = ActionResult(ok = true, "done"))
    extends RespawnActionPort {
    val claims = new AtomicInteger
    var lastClaim: Option[(String, String, String, Option[Int])] = None
    private val result = Future.successful(answer)
    def claim(guildId: String, userId: String, characterName: String,
              code: String, minutes: Option[Int]): Future[ActionResult] = {
      claims.incrementAndGet(); lastClaim = Some((guildId, userId, code, minutes)); result
    }
    def release(guildId: String, userId: String, code: Option[String]): Future[ActionResult] = result
    def extend(guildId: String, userId: String, extraMinutes: Int): Future[ActionResult] = result
    def book(guildId: String, userId: String, characterName: String, code: String,
             firstStart: java.time.ZonedDateTime, durationMinutes: Int, daysOfWeek: Int): Future[ActionResult] = {
      lastBooking = Some((code, firstStart.toInstant.toString, durationMinutes, daysOfWeek)); result
    }
    var lastBooking: Option[(String, String, Int, Int)] = None
    def cancelBooking(guildId: String, userId: String, scheduleId: Long): Future[ActionResult] = result
    def bookings(guildId: String, userId: String): List[BookingView] = Nil
    def bookingsOn(guildId: String, code: String): List[BookingView] = Nil
    def calendar(guildId: String, code: String, from: java.time.ZonedDateTime,
                 to: java.time.ZonedDateTime): Option[CalendarView] = None
    def forceLeave(guildId: String, actorId: String, code: String): Future[ActionResult] = {
      lastForceLeave = Some(code); result
    }
    var lastForceLeave: Option[String] = None
    def reassign(guildId: String, actorId: String, code: String, toUserId: String): Future[ActionResult] = result
    def grantStamina(guildId: String, actorId: String, targetUserId: String, minutes: Int): Future[ActionResult] = result
    def addSpawn(guildId: String, actorId: String, code: String, region: String,
                 name: String, creature: String): Future[ActionResult] = {
      lastAdd = Some((code, region, name, creature)); result
    }
    var lastAdd: Option[(String, String, String, String)] = None
    def removeSpawn(guildId: String, actorId: String, code: String): Future[ActionResult] = {
      lastRemove = Some(code); result
    }
    var lastRemove: Option[String] = None
    def setSpawnMax(guildId: String, actorId: String, code: String,
                    minutes: Option[Int]): Future[ActionResult] = {
      lastSpawnMax = Some((code, minutes)); result
    }
    var lastSpawnMax: Option[(String, Option[Int])] = None
    def extendHolder(guildId: String, actorId: String, code: String,
                     extraMinutes: Int): Future[ActionResult] = {
      lastExtend = Some((code, extraMinutes)); result
    }
    var lastExtend: Option[(String, Int)] = None
    def dropSlot(guildId: String, actorId: String, code: String,
                 startsAt: java.time.ZonedDateTime): Future[ActionResult] = {
      lastDrop = Some((code, startsAt.toInstant.toString)); result
    }
    var lastDrop: Option[(String, String)] = None
    def reassignSlot(guildId: String, actorId: String, code: String,
                     startsAt: java.time.ZonedDateTime, toUserId: String): Future[ActionResult] = {
      lastSlotMove = Some((code, startsAt.toInstant.toString, toUserId)); result
    }
    var lastSlotMove: Option[(String, String, String)] = None
  }

  private def relay(cache: RedisCache, timeout: FiniteDuration = 3.seconds) =
    new RelayedRespawnActions(cache, system.scheduler, timeout = timeout, pollEvery = 20.millis)

  /** Somebody the executing bot can see, at a tier it decides. The permission
   *  check now lives on this side of the relay — the issuer cannot make it for a
   *  guild it is not in — so a consumer in a test has to be told who people are
   *  just as the real one is. */
  private def asTier(tier: AccessTier): (String, String) => Option[GuildAccess] =
    (guildId, userId) => Some(GuildAccess(guildId, "Their Server", tier, List("Antica")))

  private def consumer(cache: RedisCache, local: RespawnActionPort,
                       owns: String => Boolean = _ => true, selfId: String = "bot-a",
                       resolve: (String, String) => Option[GuildAccess] = asTier(AccessTier.Admin)) =
    new RespawnCommandConsumer(cache, local, owns, selfId, resolve)

  "a relayed write" should {

    "reach the owning bot and bring its answer back" in {
      val cache = new FakeCache
      val local = new CountingActions(ActionResult(ok = true, "415 is yours."))
      val pending = relay(cache).claim("g1", "u1", "Bubble", "415", Some(120))
      // The consumer is the other process; sweeping is what it does on a timer.
      consumer(cache, local).sweep().futureValue
      pending.futureValue shouldBe ActionResult(ok = true, "415 is yours.")
      local.claims.get() shouldBe 1
      local.lastClaim shouldBe Some(("g1", "u1", "415", Some(120)))
    }

    "carry a refusal back unchanged rather than flattening it" in {
      val cache = new FakeCache
      val local = new CountingActions(ActionResult(ok = false, "You already hold that."))
      val pending = relay(cache).release("g1", "u1", Some("415"))
      consumer(cache, local).sweep().futureValue
      pending.futureValue shouldBe ActionResult(ok = false, "You already hold that.")
    }

    "survive a booking's instant and weekday mask intact" in {
      val cache = new FakeCache
      val local = new CountingActions()
      val when = java.time.Instant.parse("2026-08-09T19:00:00Z").atZone(java.time.ZoneOffset.UTC)
      val pending = relay(cache).book("g1", "u1", "Bubble", "415", when, 120, 5)
      consumer(cache, local).sweep().futureValue
      pending.futureValue.ok shouldBe true
      local.lastBooking shouldBe Some(("415", "2026-08-09T19:00:00Z", 120, 5))
    }

    // A catalogue change is a write like any other, and for a reason worth
    // stating: the insert itself would work from any bot, but the board post it
    // has to redraw afterwards belongs to whichever bot owns the forum.
    "hand a new spawn code to the bot that owns the forum" in {
      val cache = new FakeCache
      val local = new CountingActions()
      val pending = relay(cache).addSpawn("g1", "mod-1", "999", "Edron", "Deep Cave", "Orc Warlord")
      consumer(cache, local).sweep().futureValue
      pending.futureValue.ok shouldBe true
      local.lastAdd shouldBe Some(("999", "Edron", "Deep Cave", "Orc Warlord"))
    }

    // Empty values are dropped on the way out, so the far side has to read an
    // absent optional field as empty rather than refusing the whole command.
    "add a spawn that was given no city and no creature" in {
      val cache = new FakeCache
      val local = new CountingActions()
      val pending = relay(cache).addSpawn("g1", "mod-1", "999", "", "Deep Cave", "")
      consumer(cache, local).sweep().futureValue
      pending.futureValue.ok shouldBe true
      local.lastAdd shouldBe Some(("999", "", "Deep Cave", ""))
    }

    "hand a removal to the bot that owns the forum too" in {
      // Removing a code deletes the spawn's forum post as well as its row, so
      // this has even less business running anywhere else than adding one does.
      val cache = new FakeCache
      val local = new CountingActions()
      val pending = relay(cache).removeSpawn("g1", "mod-1", "999")
      consumer(cache, local).sweep().futureValue
      pending.futureValue.ok shouldBe true
      local.lastRemove shouldBe Some("999")
    }

    "hand an extension to the bot that owns the forum" in {
      val cache = new FakeCache
      val local = new CountingActions()
      val pending = relay(cache).extendHolder("g1", "mod-1", "415", 30)
      consumer(cache, local).sweep().futureValue
      pending.futureValue.ok shouldBe true
      local.lastExtend shouldBe Some(("415", 30))
    }

    // A calendar day crosses as the instant it starts on, because the slot a
    // moderator is pointing at may be one no row exists for yet.
    "hand a removed calendar day to the bot that owns the forum" in {
      val cache = new FakeCache
      val local = new CountingActions()
      val day = java.time.ZonedDateTime.parse("2026-08-13T11:00:00Z")
      val pending = relay(cache).dropSlot("g1", "mod-1", "415", day)
      consumer(cache, local).sweep().futureValue
      pending.futureValue.ok shouldBe true
      local.lastDrop shouldBe Some(("415", "2026-08-13T11:00:00Z"))
    }

    "hand a moved calendar day across with whose it becomes" in {
      val cache = new FakeCache
      val local = new CountingActions()
      val day = java.time.ZonedDateTime.parse("2026-08-13T11:00:00Z")
      val pending = relay(cache).reassignSlot("g1", "mod-1", "415", day, "u9")
      consumer(cache, local).sweep().futureValue
      pending.futureValue.ok shouldBe true
      local.lastSlotMove shouldBe Some(("415", "2026-08-13T11:00:00Z", "u9"))
    }

    // The thing the whole lease design exists to prevent.
    "run exactly once even if several bots sweep the same command" in {
      val cache = new FakeCache
      val local = new CountingActions()
      val pending = relay(cache).claim("g1", "u1", "", "415", None)
      val bots = List(consumer(cache, local, selfId = "bot-a"),
                      consumer(cache, local, selfId = "bot-b"),
                      consumer(cache, local, selfId = "bot-c"))
      Future.sequence(bots.map(_.sweep())).futureValue
      pending.futureValue.ok shouldBe true
      local.claims.get() shouldBe 1
    }

    "not run twice when the same bot sweeps again" in {
      val cache = new FakeCache
      val local = new CountingActions()
      val bot = consumer(cache, local)
      val pending = relay(cache).claim("g1", "u1", "", "415", None)
      bot.sweep().futureValue
      bot.sweep().futureValue
      bot.sweep().futureValue
      pending.futureValue.ok shouldBe true
      local.claims.get() shouldBe 1
    }

    // Leasing before checking ownership would consume the command and answer
    // "not mine", stealing it from the bot that should have run it.
    "be left alone by a bot that does not run the guild" in {
      val cache = new FakeCache
      val local = new CountingActions()
      relay(cache).claim("g1", "u1", "", "415", None)
      consumer(cache, local, owns = _ => false).sweep().futureValue
      local.claims.get() shouldBe 0
      // Still there for the right bot to pick up.
      cache.store.keys.exists(_.startsWith("tibia:respawn-cmd:")) shouldBe true
      cache.store.keys.exists(_.startsWith("tibia:respawn-lease:")) shouldBe false
    }

    "reach only the guild it names when several are waiting" in {
      val cache = new FakeCache
      val local = new CountingActions()
      relay(cache).claim("g1", "u1", "", "415", None)
      relay(cache).claim("g2", "u2", "", "416", None)
      consumer(cache, local, owns = _ == "g2").sweep().futureValue
      local.claims.get() shouldBe 1
      local.lastClaim.map(_._1) shouldBe Some("g2")
    }
  }

  "a relayed write that goes unanswered" should {

    // Timing out says nothing about whether it happened, so the wording must
    // not claim nothing changed.
    "give up without claiming the write did not happen" in {
      val cache = new FakeCache
      val result = relay(cache, timeout = 300.millis).claim("g1", "u1", "", "415", None).futureValue
      result.ok shouldBe false
      result shouldBe RelayedRespawnActions.NoAnswer
      result.message.toLowerCase should include("may still")
    }

    "leave the command in place, so a slow bot can still find it" in {
      val cache = new FakeCache
      relay(cache, timeout = 300.millis).claim("g1", "u1", "", "415", None).futureValue
      val local = new CountingActions()
      consumer(cache, local).sweep().futureValue
      local.claims.get() shouldBe 1
    }
  }

  /* The permission check moved to this end of the relay. The bot serving the
   * page is not in the guild and cannot read a member of it; the bot doing the
   * work is, and can — so it decides, and the issuer's job is only to say who is
   * asking. */
  "the executing bot" should {

    "refuse a moderator action from somebody who is only a member there" in {
      val cache = new FakeCache
      val pending = relay(cache).forceLeave("g1", "u1", "415")
      val local = new CountingActions()
      consumer(cache, local, resolve = asTier(AccessTier.Member)).sweep().futureValue

      local.lastForceLeave shouldBe None
      val answer = pending.futureValue
      answer.ok shouldBe false
      answer.message.toLowerCase should include("permission")
    }

    "let the same person do the things that are theirs to do" in {
      val cache = new FakeCache
      val pending = relay(cache).claim("g1", "u1", "", "415", None)
      val local = new CountingActions()
      consumer(cache, local, resolve = asTier(AccessTier.Member)).sweep().futureValue

      pending.futureValue.ok shouldBe true
      local.claims.get() shouldBe 1
    }

    // The visitor's own login proves they are in the guild, which is all the
    // issuing side can know. Whether they can see a tracked world there is this
    // side's to answer, and "no" has to mean no.
    "refuse anything at all from somebody it cannot resolve" in {
      val cache = new FakeCache
      val pending = relay(cache).claim("g1", "stranger", "", "415", None)
      val local = new CountingActions()
      consumer(cache, local, resolve = (_, _) => None).sweep().futureValue

      local.claims.get() shouldBe 0
      pending.futureValue.ok shouldBe false
    }

    // A refusal to resolve is not a reason to go ahead. Performing a write
    // because the check could not be made is the one failure nobody can undo.
    "refuse when resolving throws rather than assuming the best" in {
      val cache = new FakeCache
      val pending = relay(cache).claim("g1", "u1", "", "415", None)
      val local = new CountingActions()
      consumer(cache, local, resolve = (_, _) => throw new RuntimeException("JDA fell over"))
        .sweep().futureValue

      local.claims.get() shouldBe 0
      pending.futureValue.ok shouldBe false
    }

    // An action this build has never heard of cannot be known to be harmless,
    // so it is held to the stricter bar.
    "hold an action it does not recognise to the moderator bar" in {
      RespawnCommand.requiredTier(RespawnCommand.Claim) shouldBe AccessTier.Member
      RespawnCommand.requiredTier(RespawnCommand.DropSlot) shouldBe AccessTier.Moderator
      RespawnCommand.requiredTier("something-from-a-newer-build") shouldBe AccessTier.Moderator
    }
  }

  "a malformed command" should {
    "be answered rather than left to time out" in {
      val cache = new FakeCache
      cache.store.put("tibia:respawn-cmd:g1:bad", "{ not json")
      val local = new CountingActions()
      consumer(cache, local).sweep().futureValue
      local.claims.get() shouldBe 0
      val reply = cache.store.get(RespawnCommand.replyKey("bad")).flatMap(RespawnCommand.resultFromJson)
      reply.map(_.ok) shouldBe Some(false)
    }
  }

  "an unreachable cache" should {
    "tell the caller nothing was done, rather than hanging" in {
      val broken = new FakeCache {
        override def setEx(key: String, value: String, ttl: FiniteDuration): Future[Unit] =
          Future.failed(new RuntimeException("redis down"))
      }
      relay(broken).claim("g1", "u1", "", "415", None).futureValue shouldBe
        RelayedRespawnActions.Undeliverable
    }
  }

  "the router" should {
    val local = new CountingActions(ActionResult(ok = true, "local"))
    val remote = new CountingActions(ActionResult(ok = true, "relayed"))

    "keep a guild it runs at home" in {
      new RoutingRespawnActions(local, remote, _ => true)
        .claim("g1", "u1", "", "415", None).futureValue.message shouldBe "local"
    }

    "hand a guild it does not run to the relay" in {
      new RoutingRespawnActions(local, remote, _ => false)
        .claim("g1", "u1", "", "415", None).futureValue.message shouldBe "relayed"
    }

    // Reads are the same from any bot, so relaying one would add a round trip
    // to answer a question we can already answer.
    "read locally even for a guild it does not run" in {
      val readLocal = new CountingActions() {
        override def bookings(guildId: String, userId: String): List[BookingView] =
          List(BookingView(1, "415", "Cult Orcs", "Bubble", "u1",
            java.time.ZonedDateTime.now(), 120, 0, repeats = false, "booked"))
      }
      new RoutingRespawnActions(readLocal, remote, _ => false).bookings("g1", "u1") should have size 1
    }
  }
}
