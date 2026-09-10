package com.tibiabot.presentation

import com.tibiabot.domain._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The PVP embed — the one guild-scoped part of the daily post. */
class PvpEmbedsSpec extends AnyFunSuite with Matchers {

  private val down = "<:lvldown:2>"

  /** Readable stand-ins for the nine bar emoji. */
  private val ink: ((String, String)) => String = { case (colour, _) => colour.head.toString }

  /** Levels default to the reference level per death, so a tally built without
   *  saying otherwise weighs exactly its own count and the older tests keep
   *  meaning what they meant. */
  private def tally(
      enemies: Int = 9,
      allies: Int = 4,
      fraggers: List[Fragger] = Nil,
      mostWanted: List[Repeat] = Nil,
      topEnemy: Option[TopKill] = None,
      topAlly: Option[TopKill] = None,
      enemyLevels: Long = -1,
      allyLevels: Long = -1
  ) = FragTally(
    enemies, allies,
    if (enemyLevels >= 0) enemyLevels else enemies.toLong * Ref,
    if (allyLevels >= 0) allyLevels else allies.toLong * Ref,
    fraggers, mostWanted, topEnemy, topAlly)

  /** The reference the scale below is built on: one death here weighs one. */
  private val Ref = 150
  private val scale = Bars.Scale(ceiling = 30, referenceLevel = Ref.toDouble)

  /** Only Bubble has a cached sheet, so every other row exercises the unknown
   *  case. Keyed lowercase, which is what the embed is expected to look up by. */
  private val vocations: String => String = Map("bubble" -> "Master Sorcerer").withDefaultValue("")

  private def pages(
      frags: FragTally,
      losses: List[ExperienceDelta] = Nil,
      jump: String => Option[String] = id => if (id.isEmpty) None else Some(s"https://discord.com/x/$id"),
      vocationOf: String => String = vocations
  ) = PvpEmbeds.build("Antica", frags, losses, _ => "<:enemy:9>", vocationOf, ink, scale, down, jump)

  /** The one page an ordinary day produces. */
  private def build(
      frags: FragTally,
      losses: List[ExperienceDelta] = Nil,
      jump: String => Option[String] = id => if (id.isEmpty) None else Some(s"https://discord.com/x/$id"),
      vocationOf: String => String = vocations
  ) = pages(frags, losses, jump, vocationOf).head

  private def loss(name: String, gained: Long) =
    ExperienceDelta(name.toLowerCase, name, "Elite Knight", 402, 402, 1L, gained)

  // --- the bar and the counts ---------------------------------------------

  test("the bar leads and the counts follow it") {
    val embed = build(tally())
    embed.getDescription should startWith("## :dagger: PVP\n")
    embed.getDescription should include("**9** enemies killed vs **4** allies killed")
  }

  test("the bar is one split run, not two bars") {
    // Green then red then track, in that order and once each — the two sides
    // share a run rather than being drawn as separate bars.
    val embed = build(tally(enemies = 9, allies = 3, enemyLevels = 9L * Ref, allyLevels = 3L * Ref))
    val bar = embed.getDescription.linesIterator.toList(1)
    bar.count(_ == 'g') should be > bar.count(_ == 'r')
    bar.count(_ == 'r') should be > 0
    bar.replaceAll("[^gre]", "") should fullyMatch regex "g+r+e*"
  }

  test("a quiet day leaves the rest of the bar as track") {
    val embed = build(tally(enemies = 1, allies = 0, enemyLevels = Ref.toLong, allyLevels = 0))
    val bar = embed.getDescription.linesIterator.toList(1)
    bar.count(_ == 'e') should be > 0
    bar.count(_ == 'r') shouldBe 0
  }

  test("a day of killing nobodies barely moves the bar") {
    // Twenty deaths, all of them a tenth of an ordinary local. The line below
    // still says twenty, because that is what happened; the bar says it was not
    // a war, because it was not.
    val embed = build(tally(enemies = 20, allies = 0, enemyLevels = 20L * (Ref / 10), allyLevels = 0))
    val description = embed.getDescription
    description should include("**20** enemies killed")
    description.linesIterator.toList(1).count(_ == 'g') should be <= 4
  }

  test("nobody died at all and the bar is all track") {
    val bar = build(tally(enemies = 0, allies = 0)).getDescription.linesIterator.toList(1)
    bar.count(_ == 'e') shouldBe Bars.Segments
    bar.count(_ == 'g') shouldBe 0
    bar.count(_ == 'r') shouldBe 0
  }

  // --- the fragger list ----------------------------------------------------

  test("fraggers are one merged list, each carrying its own side") {
    val frags = tally(fraggers = List(
      Fragger("Bubble", FragSide.Ally, 4), Fragger("Mateusz", FragSide.Enemy, 2)))
    val embed = build(frags)
    embed.getDescription should include("### Most Kills")
    embed.getDescription should include(
      ":fire: **[Bubble](https://www.tibia.com/community/?name=Bubble)** <:enemy:9> · **4 kills**")
    embed.getDescription should include("Mateusz")
    // one heading over both sides, not one per side
    embed.getDescription.linesIterator.count(_.startsWith("### Most Kills")) shouldBe 1
  }

  test("a single kill reads as one kill") {
    build(tally(fraggers = List(Fragger("Sirmax", FragSide.Ally, 1)))).getDescription should include("**1 kill**")
  }

  // --- the four sections ---------------------------------------------------

  test("most deaths names the enemy and how often they died") {
    val embed = build(tally(mostWanted = List(Repeat("Grimjaw", 271, 4), Repeat("Draven", 355, 1))))
    embed.getDescription should include("### Most Deaths")
    embed.getDescription should include("**[Grimjaw](")
    embed.getDescription should include("*271* · **4 deaths**")
    embed.getDescription should include("**1 death**")
  }

  test("most exp lost uses the falling icon and no sign") {
    val embed = build(tally(), losses = List(loss("Vestrik", -24180400)))
    embed.getDescription should include("### Most Exp Lost")
    embed.getDescription should include(down + " **24,180,400**")
    embed.getDescription should not include "-24,180,400"
  }

  test("the two top kills each link back to their death") {
    val embed = build(tally(
      topEnemy = Some(TopKill("Vestrik", 402, FragSide.Enemy, "111")),
      topAlly = Some(TopKill("Sarnoxx", 388, FragSide.Ally, "222"))))
    embed.getDescription should include("### Top Enemy Killed")
    embed.getDescription should include("### Top Ally Killed")
    embed.getDescription should include("-# [Jump to the death](https://discord.com/x/111)")
    embed.getDescription should include("-# [Jump to the death](https://discord.com/x/222)")
  }

  test("a kill whose death was never posted still reads, without a dead link") {
    val embed = build(tally(topEnemy = Some(TopKill("Vestrik", 402, FragSide.Enemy, ""))))
    embed.getDescription should include("**[Vestrik](")
    embed.getDescription should include("*402*")
    embed.getDescription should not include "Jump to the death"
  }

  test("sections with nothing in them are absent rather than empty headings") {
    val embed = build(tally())
    embed.getDescription should not include "Most Kills"
    embed.getDescription should not include "Most Deaths"
    embed.getDescription should not include "Most Exp Lost"
    embed.getDescription should not include "Top Enemy Killed"
    embed.getDescription should not include "Top Ally Killed"
  }

  test("there are no fields") {
    build(tally(fraggers = List(Fragger("Bubble", FragSide.Ally, 4)))).getFields shouldBe empty
  }

  // --- vocations -----------------------------------------------------------

  test("a fragger carries the vocation from their cached sheet") {
    // Frag rows store a name and nothing else, so the icon has to be looked up.
    build(tally(fraggers = List(Fragger("Bubble", FragSide.Ally, 4)))).getDescription should
      include(":fire: **[Bubble](")
  }

  test("a name with no cached sheet opens with the name, not with a gap") {
    // Somebody the bot has never drawn a sheet for renders without an icon; the
    // row has to close up rather than start with the space one would have sat in.
    val embed = build(tally(fraggers = List(Fragger("Nosheet", FragSide.Ally, 2))))
    embed.getDescription.linesIterator.toList should contain(
      "**[Nosheet](https://www.tibia.com/community/?name=Nosheet)** <:enemy:9> · **2 kills**")
  }

  test("the lookup is by lowercased name, whatever casing the frag row kept") {
    val byLowercase: String => String = name => if (name == "bubble") "Elder Druid" else ""
    build(tally(fraggers = List(Fragger("Bubble", FragSide.Ally, 4))), vocationOf = byLowercase)
      .getDescription should include(":snowflake: **[Bubble](")
  }

  // --- limits --------------------------------------------------------------

  test("a war too long for one description spills onto a second embed") {
    // Long names across a full fragger list used to throw here — the embed was
    // built straight into a 4,096-character description. Nobody is dropped now.
    val long = "Abcdefghij Klmnopqrs Tuvwxyz"
    val frags = tally(
      fraggers = (1 to 60).toList.map(i => Fragger(long + i, FragSide.Ally, 20)),
      mostWanted = (1 to 5).toList.map(i => Repeat(long + i, 400, 6)))
    val built = pages(frags)
    built.size should be > 1
    built.foreach(_.getDescription.length should be <= 4096)
    val whole = built.map(_.getDescription).mkString("\n")
    (1 to 60).foreach(i => whole should include(long + i))
  }

  test("the fullest PVP embed fits inside Discord's limits") {
    val long = "Averylongcharactername"
    val frags = tally(
      enemies = 47, allies = 23,
      fraggers = (1 to 10).toList.map(i => Fragger(long + i, FragSide.Ally, 20 - i)),
      mostWanted = (1 to 5).toList.map(i => Repeat(long + i, 400 - i, 6 - i)),
      topEnemy = Some(TopKill(long + "Enemy", 402, FragSide.Enemy, "111")),
      topAlly = Some(TopKill(long + "Ally", 388, FragSide.Ally, "222")))
    val losses = (1 to 5).toList.map(i => loss(long + i, -20000000L + i))
    val built = pages(frags, losses)
    built should have size 1
    built.head.getDescription.length should be < 4096
    built.head.getLength should be < 6000
  }
}
