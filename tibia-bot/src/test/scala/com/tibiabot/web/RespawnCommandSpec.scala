package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RespawnCommandSpec extends AnyWordSpec with Matchers {

  private val command = RespawnCommand("c1", "g1", RespawnCommand.Claim, "u1",
    Map("code" -> "415", "minutes" -> "120"))

  "the wire format" should {

    "survive a round trip" in {
      RespawnCommand.fromJson(command.toJson) shouldBe Some(command)
    }

    // The two ends may be different builds. A field one side does not know
    // about has to be ignorable, not fatal.
    "ignore fields it does not recognise" in {
      val extra = """{"id":"c1","guildId":"g1","action":"claim","actorId":"u1",
                     |"params":{"code":"415"},"somethingNew":"from a later build"}""".stripMargin
      RespawnCommand.fromJson(extra).map(_.id) shouldBe Some("c1")
    }

    "refuse anything missing a field it cannot do without" in {
      List(
        """{"guildId":"g1","action":"claim","actorId":"u1"}""",
        """{"id":"c1","action":"claim","actorId":"u1"}""",
        """{"id":"c1","guildId":"g1","actorId":"u1"}""",
        """{"id":"c1","guildId":"g1","action":"claim"}""",
        """{"id":"","guildId":"g1","action":"claim","actorId":"u1"}"""
      ).foreach(raw => withClue(s"$raw: ")(RespawnCommand.fromJson(raw) shouldBe None))
    }

    // Guessing at a half-understood write is worse than not doing it.
    "refuse an action it has never heard of" in {
      RespawnCommand.fromJson(
        """{"id":"c1","guildId":"g1","action":"selfDestruct","actorId":"u1"}""") shouldBe None
    }

    "refuse anything that is not JSON at all" in {
      RespawnCommand.fromJson("not json") shouldBe None
      RespawnCommand.fromJson("") shouldBe None
      RespawnCommand.fromJson("[1,2,3]") shouldBe None
    }

    "cope with a command that carries no parameters" in {
      val bare = RespawnCommand("c1", "g1", RespawnCommand.Release, "u1", Map.empty)
      RespawnCommand.fromJson(bare.toJson) shouldBe Some(bare)
    }
  }

  "parameters" should {
    "read numbers, and refuse ones that are not" in {
      command.intParam("minutes") shouldBe Some(120)
      command.intParam("absent") shouldBe None
      // Plenty of real spawn codes are not numbers at all ("1406a"), so a code
      // must never be quietly read as one.
      val lettered = RespawnCommand("c1", "g1", RespawnCommand.Claim, "u1", Map("code" -> "1406a"))
      lettered.intParam("code") shouldBe None
      lettered.param("code") shouldBe Some("1406a")
    }

    "treat a blank as absent rather than as an empty answer" in {
      RespawnCommand("c1", "g1", RespawnCommand.Claim, "u1", Map("code" -> "   ")).param("code") shouldBe None
    }
  }

  "results" should {
    "survive a round trip, refusals included" in {
      val ok = ActionResult(ok = true, "415 is yours.")
      val no = ActionResult(ok = false, "You already hold that.")
      RespawnCommand.resultFromJson(RespawnCommand.resultToJson(ok)) shouldBe Some(ok)
      RespawnCommand.resultFromJson(RespawnCommand.resultToJson(no)) shouldBe Some(no)
    }

    "refuse a malformed reply rather than inventing one" in {
      RespawnCommand.resultFromJson("""{"ok":true}""") shouldBe None
      RespawnCommand.resultFromJson("nonsense") shouldBe None
    }
  }

  "keys" should {
    "round-trip a request key" in {
      val key = RespawnCommand.requestKey("g1", "c1")
      RespawnCommand.parseRequestKey(key) shouldBe Some(("g1", "c1"))
    }

    // A shared Redis holds other things; a consumer must not try to run them.
    "ignore keys that are not ours" in {
      List("tibia:secondary-status:123", "something:else", "tibia:respawn-cmd:g1",
           "tibia:respawn-cmd:g1:c1:extra", "")
        .foreach(key => withClue(s"$key: ")(RespawnCommand.parseRequestKey(key) shouldBe None))
    }

    "keep the lease and reply apart from the request" in {
      val id = "c1"
      val keys = Set(RespawnCommand.requestKey("g1", id), RespawnCommand.leaseKey(id), RespawnCommand.replyKey(id))
      keys should have size 3
    }

    "match its own request key with the discovery pattern" in {
      val pattern = RespawnCommand.requestPattern.replace("*", ".*")
      RespawnCommand.requestKey("g1", "c1").matches(pattern) shouldBe true
    }
  }
}
