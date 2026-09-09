package com.tibiabot.presentation

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Discord bounds a message's embeds two ways, and only one of them is obvious.
 *
 *  A hunted list long enough to page is two 4096-character embeds, which is
 *  under the ten-embed cap and well over the 6000-character one — so the send is
 *  rejected outright with MAX_EMBED_SIZE_EXCEEDED. That is a size only a real
 *  list reaches, which is exactly why it wants a test rather than a try.
 */
class ListEmbedBatchesSpec extends AnyFunSuite with Matchers {

  private def embed(chars: Int): MessageEmbed =
    new EmbedBuilder().setDescription("x" * chars).build()

  private def lengths(batches: List[List[MessageEmbed]]): List[Int] =
    batches.map(_.map(_.getLength).sum)

  test("embeds that fit in one message stay in one message") {
    val batched = ListEmbeds.batches(List(embed(100), embed(200)))
    batched should have size 1
    batched.head should have size 2
  }

  /** The case that actually broke: two full pages, four embeds under the count
   *  cap, comfortably over the character cap. */
  test("two full pages are split, because together they exceed 6000 characters") {
    val batched = ListEmbeds.batches(List(embed(4096), embed(4096)))
    batched should have size 2
    lengths(batched).foreach(_ should be <= MessageEmbed.EMBED_MAX_LENGTH_BOT)
  }

  test("no batch ever exceeds either cap") {
    val many = List.fill(25)(embed(1000))
    val batched = ListEmbeds.batches(many)
    batched.foreach { batch =>
      batch.size should be <= 10
      batch.map(_.getLength).sum should be <= MessageEmbed.EMBED_MAX_LENGTH_BOT
    }
  }

  test("the ten-embed cap still applies when everything is small") {
    val batched = ListEmbeds.batches(List.fill(12)(embed(10)))
    batched should have size 2
    batched.head should have size 10
    batched(1) should have size 2
  }

  test("nothing is lost or reordered") {
    val embeds = (1 to 9).map(i => embed(1000 * (i % 3 + 1))).toList
    val batched = ListEmbeds.batches(embeds)
    batched.flatten shouldBe embeds
  }

  test("an empty list produces no messages rather than one empty one") {
    ListEmbeds.batches(Nil) shouldBe empty
  }

  /** `paginate` caps a description at 4096 and JDA refuses more, so no embed off
   *  that path can exceed the message cap on its own. The guard still matters for
   *  any other caller: given one, it gets a message rather than being dropped or
   *  looped on. Exercised through the limit rather than an impossible embed. */
  test("an embed too big for any message is still given one") {
    val batched = ListEmbeds.batches(List(embed(100), embed(200), embed(100)), maxLength = 150)
    batched.flatten should have size 3
    batched should have size 3
    batched(1).head.getLength should be > 150
  }

  /** Where the buttons go when a list needs more than one message.
   *
   *  Under the last one. A list long enough to span several is a list you have
   *  scrolled to the bottom of, so the controls belong where that leaves you —
   *  and it is the last message a button press then edits, which is why
   *  PanelButtons puts the *last* batch back on Cancel rather than the first.
   *
   *  Asserted on the batching rather than on JDA's send calls, which a unit test
   *  cannot reach: what both sites agree on is "leading pages plain, final page
   *  with buttons", and that is decided entirely by where `batches` splits.
   */
  test("a multi-message list leaves exactly one page to carry the buttons") {
    val pages = ListEmbeds.batches(List.fill(4)(embed(4000)))
    pages.size should be > 1
    // init = the plain messages, last = the one the buttons hang under
    pages.init.foreach(_ should not be empty)
    pages.last should not be empty
    pages.init.flatten.size + pages.last.size shouldBe 4
  }

  test("a list that fits one message puts everything on the page with the buttons") {
    val pages = ListEmbeds.batches(List(embed(500), embed(500)))
    pages should have size 1
    pages.last should have size 2
    pages.init shouldBe empty
  }
}
