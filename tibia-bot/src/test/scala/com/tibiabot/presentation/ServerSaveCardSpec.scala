package com.tibiabot.presentation

import com.tibiabot.domain.MiniWorldChange
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.Component
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.tree.MessageComponentTree
import net.dv8tion.jda.api.entities.MessageEmbed
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** The server-save message as one card. Emoji are passed in so these run
 *  without loading Config, and are written out at full length, as Discord
 *  stores them, wherever a length is being checked. */
class ServerSaveCardSpec extends AnyFunSuite with Matchers {

  private val letter = "<:letter:1195388031755096114>"
  private val indent = "<:indent:1025915320285798451>"

  private def block(text: String, thumbnail: String): MessageEmbed =
    new EmbedBuilder().setDescription(text).setThumbnail(thumbnail).setColor(Embeds.BrandColor).build()

  private def mwc(changes: List[MiniWorldChange]): MessageEmbed =
    ObserverEmbeds.serverSaveMwcEmbed("Antica", changes, "<:raid:1552190974762033254>").get

  private val boss = block(s"The boosted boss today is:\n### $indent<:archfoe:1024710113728155738> **[Ferumbras Mortal Shell](https://tibia.fandom.com/wiki/Ferumbras_Mortal_Shell)**",
    "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Ferumbras_Mortal_Shell.gif")
  private val creature = block(s"The boosted creature today is:\n### $indent<:levelup:1075222553624326164> **[Dragon Lord](https://tibia.fandom.com/wiki/Dragon_Lord)**",
    "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Dragon_Lord.gif")
  private val rashid = block(s"Today Rashid can be found in:\n### $indent<:gold:1133502093039251486> **[Liberty Bay](https://tibia.fandom.com/wiki/Rashid)**",
    "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Rashid.gif")
  private val dream = block(s"The Dream Courts boss for **Antica** is:\n### $indent<a:dreamscar:1504728980010438717> **[Izcandar the Banished](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**",
    "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Izcandar_the_Banished.gif")
  private val drome = block(s"The current Drome cycle will end:\n### $indent<:drome:1507620940278923294> <t:1790000000:R>",
    "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Phant.gif")

  private val day = List(mwc(List(MiniWorldChange("Antica", "Fury Gate", "The Fury Gate has opened near Venore."))),
    boss, creature, rashid, dream, drome)

  test("each block becomes one section of a single card, in order, with the button under it") {
    val parts = ServerSaveCard.components(day, letter)
    parts should have size 2
    val card = parts.head.asInstanceOf[Container].getComponents.asScala.toList
    card.collect { case s: Section => s } should have size day.size
    card.collect { case s: Separator => s } should have size (day.size - 1)
    // Sections and dividers alternate, starting and ending on a section.
    card.zipWithIndex.foreach { case (part, i) =>
      if (i % 2 == 0) part shouldBe a[Section] else part shouldBe a[Separator]
    }
    val button = parts(1).asInstanceOf[ActionRow].getComponents.asScala.head.asInstanceOf[Button]
    button.getCustomId shouldBe "boosted list"
    button.getLabel shouldBe "Server Save Notifications"
  }

  test("a card reads back into the blocks it was built from, so a rebuild keeps its boss and creature") {
    val back = ServerSaveCard.blocksOfCard(ServerSaveCard.components(day, letter))
    back.map(_.getDescription) shouldBe day.map(_.getDescription)
    back.map(_.getThumbnail.getUrl) shouldBe day.map(_.getThumbnail.getUrl)
    ObserverEmbeds.boostedEmbedsOf(back).map(_.getDescription) shouldBe List(boss, creature).map(_.getDescription)
  }

  test("the busiest day fits inside one V2 message") {
    val manyChanges = (1 to 40).toList.map(i => MiniWorldChange("Antica", s"Change number $i", "x" * 150))
    val tree = MessageComponentTree.of(ServerSaveCard.components(mwc(manyChanges) :: day.tail, letter).asJava)
    tree.findAll(classOf[TextDisplay]).asScala.map(_.getContent.length).sum should be <= 4000
    tree.findAll(classOf[Component]).size should be <= 40
  }
}
