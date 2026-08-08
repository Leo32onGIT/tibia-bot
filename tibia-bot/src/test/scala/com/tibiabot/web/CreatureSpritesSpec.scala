package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CreatureSpritesSpec extends AnyWordSpec with Matchers {

  "safeFileName" should {

    // The shapes real wiki file names actually take, from Urls.creatureFileName.
    "accept the file names the catalogue really produces" in {
      CreatureSprites.safeFileName("Dragon") shouldBe Some("Dragon.gif")
      CreatureSprites.safeFileName("Orc_Warlord") shouldBe Some("Orc_Warlord.gif")
      CreatureSprites.safeFileName("Sign_(Library)") shouldBe Some("Sign_(Library).gif")
      CreatureSprites.safeFileName("Mooh'Tah_Warrior") shouldBe Some("Mooh'Tah_Warrior.gif")
      CreatureSprites.safeFileName("Two-Headed_Turtle") shouldBe Some("Two-Headed_Turtle.gif")
      CreatureSprites.safeFileName("Lizard_High_Guard") shouldBe Some("Lizard_High_Guard.gif")
    }

    "trim surrounding whitespace" in {
      CreatureSprites.safeFileName("  Dragon  ") shouldBe Some("Dragon.gif")
    }

    "refuse an empty or blank name" in {
      CreatureSprites.safeFileName("") shouldBe None
      CreatureSprites.safeFileName("   ") shouldBe None
    }

    // The whole reason this exists: the input is guild-editable and names a
    // file we read from disk.
    "refuse every shape of path traversal" in {
      CreatureSprites.safeFileName("..") shouldBe None
      CreatureSprites.safeFileName("../../etc/passwd") shouldBe None
      CreatureSprites.safeFileName("..\\..\\windows\\system32") shouldBe None
      CreatureSprites.safeFileName("/etc/passwd") shouldBe None
      CreatureSprites.safeFileName("C:\\Windows\\System32") shouldBe None
      CreatureSprites.safeFileName("Dragon/../../secret") shouldBe None
    }

    // A dot is never needed, so forbidding it removes traversal as an
    // expressible idea rather than something to detect.
    "refuse a dot anywhere, extension included" in {
      CreatureSprites.safeFileName("Dragon.gif") shouldBe None
      CreatureSprites.safeFileName("Dragon.") shouldBe None
      CreatureSprites.safeFileName(".hidden") shouldBe None
    }

    "refuse separators and shell or URL punctuation" in {
      List("Dragon/Lord", "Dragon\\Lord", "Dragon;rm", "Dragon|cat", "Dragon&whoami",
           "Dragon?x=1", "Dragon#frag", "Dragon%2e%2e", "Dragon<script>", "Dragon Lord")
        .foreach(name => withClue(s"'$name': ")(CreatureSprites.safeFileName(name) shouldBe None))
    }

    "refuse a null byte, which some filesystems truncate on" in {
      CreatureSprites.safeFileName("Dragon\u0000.png") shouldBe None
    }

    "refuse control characters and newlines" in {
      CreatureSprites.safeFileName("Dragon\nLord") shouldBe None
      CreatureSprites.safeFileName("Dragon\tLord") shouldBe None
    }

    // Non-ASCII is refused rather than transliterated: normalisation is exactly
    // where filename checks tend to go wrong, and no real wiki name needs it.
    "refuse non-ASCII rather than trying to fold it" in {
      CreatureSprites.safeFileName("Dragón") shouldBe None
      CreatureSprites.safeFileName("Драгон") shouldBe None
      CreatureSprites.safeFileName("Dragon\u202Egfp") shouldBe None
    }

    "refuse a name long enough to threaten a path limit" in {
      CreatureSprites.safeFileName("A" * 65) shouldBe None
      CreatureSprites.safeFileName("A" * 64) shouldBe Some("A" * 64 + ".gif")
    }

    // Refused, never rewritten into something that passes.
    "never return a name containing anything it was given and rejected" in {
      CreatureSprites.safeFileName("Dragon/../Lord") shouldBe None
    }
  }

  "urlFor" should {
    "serve a safe name from our own domain" in {
      CreatureSprites.urlFor("Orc_Warlord") shouldBe Some("/dashboard/sprites/Orc_Warlord.gif")
    }

    "give nothing for a name that failed the check" in {
      CreatureSprites.urlFor("../../etc/passwd") shouldBe None
    }
  }

  "wikiNameOf" should {
    "round-trip a name that urlFor produced" in {
      val url = CreatureSprites.urlFor("Orc_Warlord").get
      CreatureSprites.wikiNameOf(url.split('/').last) shouldBe Some("Orc_Warlord")
    }

    "handle the punctuation real names carry" in {
      CreatureSprites.wikiNameOf("Sign_(Library).gif") shouldBe Some("Sign_(Library)")
      CreatureSprites.wikiNameOf("Mooh'Tah_Warrior.gif") shouldBe Some("Mooh'Tah_Warrior")
    }

    "refuse a segment with no extension" in {
      CreatureSprites.wikiNameOf("Dragon") shouldBe None
    }

    "refuse another extension" in {
      CreatureSprites.wikiNameOf("Dragon.png") shouldBe None
      CreatureSprites.wikiNameOf("Dragon.gif.exe") shouldBe None
    }

    // Ending in .gif says nothing about the rest, so the stripped name goes
    // back through the same check everything else does.
    "refuse traversal dressed up with the right extension" in {
      CreatureSprites.wikiNameOf("../../etc/passwd.gif") shouldBe None
      CreatureSprites.wikiNameOf("..%2F..%2Fsecret.gif") shouldBe None
      CreatureSprites.wikiNameOf("Dragon.gif.gif") shouldBe None
    }

    "refuse a bare extension" in {
      CreatureSprites.wikiNameOf(".gif") shouldBe None
    }
  }

  "placeholderUrl" should {
    // Served from this domain rather than the wiki, which is what makes it
    // immune to the geoblock the real sprites exist to work around.
    "point at our own avatar rather than anything remote" in {
      CreatureSprites.placeholderUrl should startWith("/dashboard/")
      CreatureSprites.placeholderUrl should not include "tibiawiki"
    }
  }
}
