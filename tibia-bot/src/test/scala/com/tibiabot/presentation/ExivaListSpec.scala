package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the exiva block a death post gains when its button is pressed. The
 *  icons are injected, so this loads no Config; descriptions are written the
 *  way TibiaBot's death block writes them. */
class ExivaListSpec extends AnyFunSuite with Matchers {

  private val Exiva = ":exiva:"
  private val Indent = ":indent:"

  private def killer(name: String, level: Option[Int] = None): String = {
    val levelText = level.map(l => s" [$l]").getOrElse("")
    s"**[$name$levelText](${Urls.charUrl(name)})**"
  }

  private def death(killers: String*): String =
    s":guild: *Leader* of the [Red Rose](${Urls.guildUrl("Red Rose")})\n" +
      s"Killed <t:1758000000:R> at level 412\nby ${killers.mkString(", ")}."

  test("render: the first line carries the exiva icon and the rest are indented") {
    ExivaList.render(Seq("Alpha", "Beta"), Exiva, Indent) shouldBe
      "\n:exiva: `exiva \"Alpha\"`\n:indent: `exiva \"Beta\"`"
    ExivaList.render(Nil, Exiva, Indent) shouldBe ""
  }

  test("killersIn: reads the linked names, and the level beside each") {
    ExivaList.killersIn(death(killer("Violent Beams", Some(412)), killer("Bubble"))) shouldBe
      Seq(("Violent Beams", Some(412)), ("Bubble", None))
  }

  test("killersIn: the victim's guild link is not a killer") {
    ExivaList.killersIn(death(killer("Bubble", Some(300)))).map(_._1) shouldBe Seq("Bubble")
  }

  test("killersIn: a creature killer has no link and so no exiva") {
    val description = death("a **dragon lord**", killer("Bubble", Some(300)))
    ExivaList.killersIn(description).map(_._1) shouldBe Seq("Bubble")
  }

  test("killersIn: a summon is read as the summoner it links to") {
    val summon = s"a :summon:**fire elemental of [Bobeek [700]](${Urls.charUrl("Bobeek")})**"
    ExivaList.killersIn(death(summon)) shouldBe Seq(("Bobeek", Some(700)))
  }

  test("sectionFor: names the hardest killers, hardest first") {
    val description = death(
      killer("Low", Some(100)), killer("High", Some(900)), killer("Mid", Some(500)))
    ExivaList.sectionFor(description, Exiva, Indent) shouldBe
      "\n:exiva: `exiva \"High\"`\n:indent: `exiva \"Mid\"`\n:indent: `exiva \"Low\"`"
  }

  test("sectionFor: a description that already carries a block gains nothing") {
    val description = death(killer("Bubble", Some(300))) +
      ExivaList.render(Seq("Bubble"), Exiva, Indent)
    ExivaList.sectionFor(description, Exiva, Indent) shouldBe ""
  }

  test("sectionFor: a death with nobody to chase gains nothing") {
    ExivaList.sectionFor(death("a **dragon lord**"), Exiva, Indent) shouldBe ""
  }

  test("sectionFor: a description with no room left drops lines from the bottom") {
    val description = death(
      killer("Aaa", Some(900)), killer("Bbb", Some(800)), killer("Ccc", Some(700)))
    val full = ExivaList.sectionFor(description, Exiva, Indent)
    val oneLine = "\n:exiva: `exiva \"Aaa\"`"
    // Padded so only the first of the three lines still fits under 4096.
    val padded = description + " " * (4096 - description.length - oneLine.length)
    full.count(_ == '\n') shouldBe 3
    ExivaList.sectionFor(padded, Exiva, Indent) shouldBe oneLine
  }
}
