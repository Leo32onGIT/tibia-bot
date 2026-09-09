package com.tibiabot.panels

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The hunted-list tags.
 *
 *  A fixed set because Discord has no emoji picker: a modal can hold a select
 *  whose options carry emoji, which is as close as the platform gets. The keys
 *  are what the database stores, so they are pinned here — renaming one orphans
 *  every entry already tagged with it.
 */
class ListTagsSpec extends AnyFunSuite with Matchers {

  test("the set is what the picker offers, by key") {
    ListTags.all.map(_.key) shouldBe List(
      "bot", "toxic", "carbomber", "bomb", "thief", "rat",
      "killer", "tank", "leader", "rich", "priority", "inactive", "unknown")
  }

  test("every tag has a label and an emoji") {
    ListTags.all.foreach { tag =>
      withClue(s"${tag.key}: ") {
        tag.label should not be empty
        tag.emoji should not be empty
      }
    }
  }

  /** Discord caps a select at 25 options, and the clearing choice takes one. */
  test("the set leaves room in the picker") {
    ListTags.all.size + 1 should be <= 25
  }

  test("keys are unique, since they are what the database stores") {
    ListTags.all.map(_.key).distinct should have size ListTags.all.size
  }

  test("a known key resolves to its tag") {
    ListTags.find("carbomber").map(_.label) shouldBe Some("Carbomber")
    ListTags.find("CARBOMBER").map(_.label) shouldBe Some("Carbomber")
  }

  /** An untagged entry, the clearing choice, and a key retired from the set all
   *  mean the same thing to a reader: no tag. None of them may throw. */
  test("no tag, the clearing choice and an unknown key all read as untagged") {
    ListTags.find("") shouldBe None
    ListTags.find(null) shouldBe None
    ListTags.find(ListTags.NoneKey) shouldBe None
    ListTags.find("retired-long-ago") shouldBe None
  }

  test("mark renders a leading space and the emoji, or nothing at all") {
    ListTags.mark("bot") shouldBe " 🤖"
    ListTags.mark("") shouldBe ""
    ListTags.mark(ListTags.NoneKey) shouldBe ""
    ListTags.mark("nonsense") shouldBe ""
  }

  test("valid accepts the picker's own answers and rejects invented ones") {
    ListTags.all.foreach(tag => ListTags.valid(tag.key) shouldBe true)
    ListTags.valid("") shouldBe true
    ListTags.valid(ListTags.NoneKey) shouldBe true
    ListTags.valid("nonsense") shouldBe false
  }
}
