package com.tibiabot.discord

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DiscordApiRouteSpec extends AnyWordSpec with Matchers {

  import DiscordApiRoute.{Other, operation}

  "DiscordApiRoute" should {

    // The distinction the whole breakdown exists for: both are PATCH, both are
    // this bot's highest-volume writes, and Discord rate-limits them under
    // completely different buckets — so they must never share a row.
    "separate a message edit from a channel edit" in {
      operation("PATCH", "/api/v10/channels/885120558963/messages/119357742001") shouldBe "PATCH message"
      operation("PATCH", "/api/v10/channels/885120558963") shouldBe "PATCH channel"
    }

    "name a message send" in {
      operation("POST", "/api/v10/channels/885120558963/messages") shouldBe "POST message"
    }

    // Opening a DM only ever happens as the first half of sending someone a DM,
    // so it is counted with the send it pays for rather than as channel work.
    "count opening a DM as part of sending a message" in {
      operation("POST", "/api/v10/users/@me/channels") shouldBe "POST message"
    }

    "separate a message delete from a channel delete" in {
      operation("DELETE", "/api/v10/channels/885120558963/messages/119357742001") shouldBe "DELETE message"
      operation("DELETE", "/api/v10/channels/885120558963") shouldBe "DELETE channel"
    }

    // The interaction-followup edit route carries no message id but is still a
    // message edit.
    "treat a webhook original-message edit as a message edit" in {
      operation("PATCH", "/api/v10/webhooks/1055/aTokenValue/messages/@original") shouldBe "PATCH message"
    }

    // Everything outside the named five collapses, so the two rows that matter
    // can't be buried under a row per endpoint.
    "fold every unnamed call into the catch-all" in {
      operation("GET", "/api/v10/users/183948374766") shouldBe Other
      operation("GET", "/api/v10/gateway/bot") shouldBe Other
      operation("PUT", "/api/v10/channels/885/messages/119/reactions/%F0%9F%94%94/@me") shouldBe Other
    }

    // Creating a channel is `/channels` with no id — deliberately not one of the
    // named five, and it must not be mistaken for editing an existing channel.
    "treat channel creation as a catch-all, not a channel edit" in {
      operation("POST", "/api/v10/guilds/774581304166/channels") shouldBe Other
    }

    // Without normalisation every channel id would become its own row and the
    // cardinality cap would fire within minutes.
    "collapse snowflakes so ids never become their own rows" in {
      val a = operation("PATCH", "/api/v10/channels/111111111111111111/messages/222222222222222222")
      val b = operation("PATCH", "/api/v10/channels/333333333333333333/messages/444444444444444444")
      a shouldBe b
      a shouldBe "PATCH message"
    }

    // The API version must survive normalisation — collapsing `/v10` would turn
    // every path into a different shape.
    "leave short numeric segments alone" in {
      operation("PATCH", "/api/v10/channels/885120558963") shouldBe "PATCH channel"
    }

    "only ever return one of the named five or the catch-all" in {
      val named = Set("PATCH message", "PATCH channel", "POST message", "DELETE message", "DELETE channel", Other)
      val paths = List(
        "/api/v10/channels/885120558963/messages",
        "/api/v10/channels/885120558963/messages/119357742001",
        "/api/v10/channels/885120558963",
        "/api/v10/guilds/774581304166/channels",
        "/api/v10/users/@me/channels",
        "/api/v10/gateway/bot"
      )
      for (method <- List("GET", "POST", "PATCH", "PUT", "DELETE"); path <- paths)
        named should contain(operation(method, path))
    }
  }
}
