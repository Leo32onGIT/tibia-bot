package com.tibiabot.patreonapi

import com.tibiabot.domain.PatreonMember
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

/** Parses Patreon API v2 JSON:API fixtures — pins PatreonApiClient.parsePage
 *  against a real response shape without needing live credentials or an
 *  ActorSystem (see the companion object's doc comment). */
class PatreonApiClientSpec extends AnyFunSuite with Matchers {

  test("parses a page with a linked-Discord active patron and a next cursor") {
    val json =
      """{
        |"data": [
        |  { "type": "member", "id": "member-1", "attributes": {
        |      "full_name": "Alice Example", "patron_status": "active_patron", "currently_entitled_amount_cents": 500
        |    }, "relationships": { "user": { "data": { "type": "user", "id": "user-1" } } } }
        |],
        |"included": [
        |  { "type": "user", "id": "user-1", "attributes": {
        |      "social_connections": { "discord": { "user_id": "111222333", "scopes": ["identify"] }, "youtube": null }
        |    } }
        |],
        |"meta": { "pagination": { "cursors": { "next": "cursor-abc" } } }
        |}""".stripMargin.parseJson.asJsObject

    val (members, nextCursor) = PatreonApiClient.parsePage(json)
    members shouldBe List(PatreonMember("member-1", "Alice Example", Some("active_patron"), 500, Some("111222333")))
    nextCursor shouldBe Some("cursor-abc")
  }

  test("a member with no linked Discord account parses with discordUserId = None") {
    val json =
      """{
        |"data": [
        |  { "type": "member", "id": "member-2", "attributes": {
        |      "full_name": "Bob Example", "patron_status": "active_patron", "currently_entitled_amount_cents": 300
        |    }, "relationships": { "user": { "data": { "type": "user", "id": "user-2" } } } }
        |],
        |"included": [
        |  { "type": "user", "id": "user-2", "attributes": { "social_connections": { "discord": null } } }
        |],
        |"meta": { "pagination": { "cursors": { "next": null } } }
        |}""".stripMargin.parseJson.asJsObject

    val (members, nextCursor) = PatreonApiClient.parsePage(json)
    members shouldBe List(PatreonMember("member-2", "Bob Example", Some("active_patron"), 300, None))
    nextCursor shouldBe None
  }

  test("a former patron's status and zero pledge parse correctly") {
    val json =
      """{
        |"data": [
        |  { "type": "member", "id": "member-3", "attributes": {
        |      "full_name": "Carol Example", "patron_status": "former_patron", "currently_entitled_amount_cents": 0
        |    }, "relationships": { "user": { "data": { "type": "user", "id": "user-3" } } } }
        |],
        |"included": [
        |  { "type": "user", "id": "user-3", "attributes": { "social_connections": {} } }
        |]
        |}""".stripMargin.parseJson.asJsObject

    val (members, nextCursor) = PatreonApiClient.parsePage(json)
    members shouldBe List(PatreonMember("member-3", "Carol Example", Some("former_patron"), 0, None))
    nextCursor shouldBe None
  }

  test("no next cursor when meta/pagination/cursors is absent entirely") {
    val json = """{"data": [], "included": []}""".parseJson.asJsObject
    PatreonApiClient.parsePage(json) shouldBe (Nil, None)
  }

  test("a member missing its user relationship entirely is skipped, not a hard failure") {
    val json =
      """{
        |"data": [
        |  { "type": "member", "id": "member-4", "attributes": { "full_name": "No Relationship" } },
        |  { "type": "member", "id": "member-5", "attributes": {
        |      "full_name": "Has Relationship", "patron_status": "active_patron", "currently_entitled_amount_cents": 100
        |    }, "relationships": { "user": { "data": { "type": "user", "id": "user-5" } } } }
        |],
        |"included": [
        |  { "type": "user", "id": "user-5", "attributes": { "social_connections": {} } }
        |]
        |}""".stripMargin.parseJson.asJsObject

    val (members, _) = PatreonApiClient.parsePage(json)
    members.map(_.patreonMemberId) shouldBe List("member-4", "member-5")
    members.find(_.patreonMemberId == "member-4").flatMap(_.discordUserId) shouldBe None
  }
}
