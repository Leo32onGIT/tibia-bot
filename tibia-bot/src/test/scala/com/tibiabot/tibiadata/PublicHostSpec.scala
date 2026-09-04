package com.tibiabot.tibiadata

import org.apache.pekko.http.scaladsl.model.Uri
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

/** Which of the two TibiaData counters a request is filed under. The split is
 *  taken from the host actually called rather than from `TIBIADATA_HOST`,
 *  because that setting points at the public API in local development. */
class PublicHostSpec extends AnyWordSpec with Matchers {

  "the public host test" should {

    "recognise the shared API the character and world endpoints use" in {
      TibiaDataClient.isPublicHost(Uri("https://api.tibiadata.com/v4/character/Bubble")) shouldBe true
      TibiaDataClient.isPublicHost(Uri("https://api.tibiadata.com/v4/highscores/Antica/swordfighting/all/1")) shouldBe true
    }

    "treat every other host as an instance we run" in {
      // The three shapes TIBIADATA_HOST actually takes: container DNS on blue,
      // the private network address red reaches it on, and CI's localhost.
      TibiaDataClient.isPublicHost(Uri("http://tibiadata-api:8080/v4/highscores/Antica/magiclevel/monks/1")) shouldBe false
      TibiaDataClient.isPublicHost(Uri("http://10.124.0.3:8081/v4/boostablebosses")) shouldBe false
      TibiaDataClient.isPublicHost(Uri("http://localhost:8081/v4/creatures")) shouldBe false
    }

    "read the public API as public even when it is what TIBIADATA_HOST points at" in {
      // The local .env does exactly this, so a config-derived split would file
      // every development request as load on an instance that does not exist.
      TibiaDataClient.isPublicHost(Uri("https://api.tibiadata.com/v4/creatures")) shouldBe true
    }

    "ignore case, since a host name is not case sensitive" in {
      TibiaDataClient.isPublicHost(Uri("https://API.TibiaData.com/v4/worlds")) shouldBe true
    }

    "call a hostless URI public rather than inflating the figure that has to be true" in {
      TibiaDataClient.isPublicHost(Uri("/v4/worlds")) shouldBe true
    }
  }
}
