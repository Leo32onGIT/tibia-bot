package com.tibiabot.observer

import com.tibiabot.domain.{ObserverStatus, ObserverToken}
import com.tibiabot.persistence.{CoveredArea, ObserverCoverageRepository, ObserverRepository}
import com.tibiabot.tracking.ApiCallMetrics
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** What `/observer` shows a member of a guild's raid-area coverage: which worlds,
 *  which areas are covered by any working link, and which of those are the
 *  member's own. The stores are in memory and nothing calls the API. */
class ObserverCoverageSpec extends AnyFunSuite with Matchers {

  private def token(guildId: String, userId: String, worlds: String, status: ObserverStatus = ObserverStatus.Linked) =
    ObserverToken(1L, guildId, userId, Some(worlds), Some("Keeper of Tibia"), status, Instant.EPOCH, Instant.EPOCH)

  private final class Links(tokens: List[ObserverToken]) extends ObserverRepository {
    def all(): List[ObserverToken] = tokens
    def forUser(guildId: String, userId: String): Option[ObserverToken] =
      tokens.find(t => t.guildId == guildId && t.userId == userId)
    def upsert(guildId: String, userId: String, tokenEnc: String, status: ObserverStatus,
               accountLabel: Option[String], world: Option[String]): ObserverToken = ???
    def tokenEncFor(guildId: String, userId: String): Option[String] = None
    def setStatus(id: Long, status: ObserverStatus, world: Option[String]): Unit = ()
    def delete(guildId: String, userId: String): Boolean = false
    def deleteGuild(guildId: String): Unit = ()
    def deleteUser(guildId: String, userId: String): Unit = ()
  }

  /** The areas working links cover: the member's Carlin and Hrodmir on Victoris,
   *  and another guild's member's Carlin, Thais, an unnamed area on Victoris, and
   *  Venore on Antica. Thais's name came in with the rules. */
  private object Coverage extends ObserverCoverageRepository {
    private val rows = List(
      CoveredArea("g1", "u1", "Victoris", 3), CoveredArea("g1", "u1", "Victoris", 23),
      CoveredArea("g2", "u2", "Victoris", 3), CoveredArea("g2", "u2", "Victoris", 40), CoveredArea("g2", "u2", "Victoris", 99),
      CoveredArea("g2", "u2", "Antica", 41))
    def setAreas(guildId: String, userId: String, areas: Map[String, List[Int]]): Unit = ()
    def clearLink(guildId: String, userId: String): Unit = ()
    def clearGuild(guildId: String): Unit = ()
    def liveAreas(worlds: List[String]): List[CoveredArea] =
      rows.filter(r => worlds.exists(_.equalsIgnoreCase(r.world)))
    def setNames(names: Map[Int, String]): Unit = ()
    def names(): Map[Int, String] = Map(40 -> "Thais", 41 -> "venore")
  }

  private def service(tokens: List[ObserverToken]) =
    new ObserverService(new Links(tokens), TokenCrypto.fromSecret("test-secret"),
      new ObserverApiClient("http://127.0.0.1:9", sharedToken = "", deviceIdentification = "Violent Bot",
        clientVersion = "1.1.6", metrics = new ApiCallMetrics()),
      enabled = true, guildWorlds = _ => List("Victoris", "Antica"), coverage = Coverage)

  test("a linked member sees only the worlds set up here that their account is on, their own areas marked") {
    val view = service(List(token("g1", "u1", "Ombra, Victoris"))).panel("g1", "u1")
    view.token.map(_.userId) shouldBe Some("u1")
    // Area 99 has no name, so there's nothing to show it as.
    view.worlds shouldBe List(WorldCoverage("Victoris", Map("Carlin" -> true, "Hrodmir" -> true, "Thais" -> false)))
  }

  test("a member with no token sees every world set up here, none of the areas theirs") {
    service(Nil).panel("g1", "u9").worlds shouldBe List(
      WorldCoverage("Victoris", Map("Carlin" -> false, "Hrodmir" -> false, "Thais" -> false)),
      WorldCoverage("Antica", Map("Venore" -> false)))
  }

  test("a linked account with no character on any world set up here sees no coverage") {
    service(List(token("g1", "u1", "Xyla"))).panel("g1", "u1").worlds shouldBe Nil
  }

  test("the raid areas are the catalogue's, alphabetical") {
    ObserverAreas.raidAreas.size shouldBe 15
    ObserverAreas.raidAreas.take(3) shouldBe List("Ab'Dendriel", "Carlin", "Edron")
  }
}
