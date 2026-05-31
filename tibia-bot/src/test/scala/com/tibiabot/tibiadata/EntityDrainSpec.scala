package com.tibiabot.tibiadata

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import com.tibiabot.tibiadata.response.WorldResponse
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/** Pins the akka-http behaviour that TibiaDataClient's leak fix relies on: a
 *  non-JSON response fails unmarshalling on the content-type check WITHOUT
 *  consuming the entity, so recoverUnmarshal's `discardEntityBytes()` is both
 *  necessary (to free the pooled connection) and safe (no double-materialise).
 *  If an akka-http upgrade changed this, the fix could silently leak or throw —
 *  this catches it. */
class EntityDrainSpec extends AnyFunSuite with Matchers with JsonSupport {

  test("a non-JSON response fails with UnsupportedContentType and its entity is then drainable") {
    // akka reference config only — the app's discord.conf has env substitutions
    // (POSTGRES_HOST) that aren't set in tests and would fail system startup.
    implicit val system: ActorSystem = ActorSystem("entity-drain-test", ConfigFactory.defaultReference())
    try {
      // a streamed (chunked) entity, mimicking a real connection-bound response
      val entity = HttpEntity.Chunked.fromData(
        ContentTypes.`text/plain(UTF-8)`,
        Source.single(ByteString("upstream error page, not json")))
      val response = HttpResponse(entity = entity)

      // the JSON unmarshaller rejects on the content-type check, before reading the body
      intercept[UnsupportedContentTypeException] {
        Await.result(Unmarshal(response).to[WorldResponse], 5.seconds)
      }

      // because the body was never read, draining it now succeeds (the fix is safe)
      noException should be thrownBy Await.result(response.discardEntityBytes().future(), 5.seconds)
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
  }
}
