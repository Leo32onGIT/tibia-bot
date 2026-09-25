package com.tibiabot.presentation

import com.tibiabot.domain._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The PVP card — the one guild-scoped part of the daily post. */
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

  /** Bubble's sheet has a level too, for the Most Kills rows that store none. */
  private val levels: String => Option[Int] = Map("bubble" -> 766).get

  private def pages(
      frags: FragTally,
      losses: List[ExperienceDelta] = Nil,
      jump: String => Option[String] = id => if (id.isEmpty) None else Some(s"https://discord.com/x/$id"),
      vocationOf: String => String = vocations,
      levelOf: String => Option[Int] = levels
  ) = PvpEmbeds.build("Antica", frags, losses, _ => "<:enemy:9>", vocationOf, levelOf, ink, scale, down, jump)

  private def build(
      frags: FragTally,
      losses: List[ExperienceDelta] = Nil,
      jump: String => Option[String] = id => if (id.isEmpty) None else Some(s"https://discord.com/x/$id"),
      vocationOf: String => String = vocations,
      levelOf: String => Option[Int] = levels
  ) = pages(frags, losses, jump, vocationOf, levelOf)

  private def loss(name: String, gained: Long) =
    ExperienceDelta(name.toLowerCase, name, "Elite Knight", 402, 402, 1L, gained)

  // --- the bar and the counts ---------------------------------------------

  test("the bar leads and the counts follow it") {
    val embed = build(tally())
    embed.text should startWith("## :dagger: PVP\n")
    embed.text should include("**9** enemies killed vs **4** allies killed")
  }

  test("the bar is one split run, not two bars") {
    // Green then red then track, in that order and once each — the two sides
    // share a run rather than being drawn as separate bars.
    val embed = build(tally(enemies = 9, allies = 3, enemyLevels = 9L * Ref, allyLevels = 3L * Ref))
    val bar = embed.text.linesIterator.toList(1)
    bar.count(_ == 'g') should be > bar.count(_ == 'r')
    bar.count(_ == 'r') should be > 0
    bar.replaceAll("[^gre]", "") should fullyMatch regex "g+r+e*"
  }

  test("a quiet day leaves the rest of the bar as track") {
    val embed = build(tally(enemies = 1, allies = 0, enemyLevels = Ref.toLong, allyLevels = 0))
    val bar = embed.text.linesIterator.toList(1)
    bar.count(_ == 'e') should be > 0
    bar.count(_ == 'r') shouldBe 0
  }

  test("a day of killing nobodies barely moves the bar") {
    // Twenty deaths, all of them a tenth of an ordinary local. The line below
    // still says twenty, because that is what happened; the bar says it was not
    // a war, because it was not.
    val embed = build(tally(enemies = 20, allies = 0, enemyLevels = 20L * (Ref / 10), allyLevels = 0))
    val description = embed.text
    description should include("**20** enemies killed")
    description.linesIterator.toList(1).count(_ == 'g') should be <= 4
  }

  test("nobody died at all and the bar is all track") {
    val bar = build(tally(enemies = 0, allies = 0)).text.linesIterator.toList(1)
    bar.count(_ == 'e') shouldBe Bars.Segments
    bar.count(_ == 'g') shouldBe 0
    bar.count(_ == 'r') shouldBe 0
  }

  // --- the fragger list ----------------------------------------------------

  test("fraggers are one merged list, each carrying its own side") {
    val frags = tally(fraggers = List(
      Fragger("Bubble", FragSide.Ally, 4), Fragger("Mateusz", FragSide.Enemy, 2)))
    val embed = build(frags)
    embed.text should include("-# ᴍᴏsᴛ ᴋɪʟʟs")
    embed.text should include(
      ":fire: **766** — **[Bubble](https://www.tibia.com/community/?name=Bubble)** <:enemy:9> · **4 kills**")
    embed.text should include("Mateusz")
    // one label over both sides, not one per side
    embed.text.linesIterator.count(_.startsWith("-# ᴍᴏsᴛ ᴋɪʟʟs")) shouldBe 1
  }

  test("a single kill reads as one kill") {
    build(tally(fraggers = List(Fragger("Sirmax", FragSide.Ally, 1)))).text should include("**1 kill**")
  }

  // --- the four sections ---------------------------------------------------

  test("most deaths names the enemy and how often they died") {
    val embed = build(tally(mostWanted = List(Repeat("Grimjaw", 271, 4), Repeat("Draven", 355, 1))))
    embed.text should include("-# ᴍᴏsᴛ ᴅᴇᴀᴛʜs")
    embed.text should include("**[Grimjaw](")
    embed.text should include("**271** — **[Grimjaw](https://www.tibia.com/community/?name=Grimjaw)** <:enemy:9> · **4 deaths**")
    embed.text should include("**1 death**")
  }

  test("most exp lost uses the falling icon and no sign") {
    val embed = build(tally(), losses = List(loss("Vestrik", -24180400)))
    embed.text should include("-# ᴍᴏsᴛ ᴇxᴘ ʟᴏsᴛ")
    embed.text should include(down + " **24,180,400**")
    embed.text should not include "-24,180,400"
  }

  test("the two top kills each link back to their death") {
    val embed = build(tally(
      topEnemy = Some(TopKill("Vestrik", 402, FragSide.Enemy, "111")),
      topAlly = Some(TopKill("Sarnoxx", 388, FragSide.Ally, "222"))))
    embed.text should include("-# ᴛᴏᴘ ᴇɴᴇᴍʏ ᴋɪʟʟᴇᴅ")
    embed.text should include("-# ᴛᴏᴘ ᴀʟʟʏ ᴋɪʟʟᴇᴅ")
    embed.text should include("[:link:](https://discord.com/x/111)")
    embed.text should include("[:link:](https://discord.com/x/222)")
  }

  test("the link closes the row, after the name and side") {
    // A cell of its own at the end, not a line of subtext under the row and not
    // another marker beside the name — the markers say what the character is,
    // the link is somewhere to go.
    val embed = build(tally(topEnemy = Some(TopKill("Bubble", 402, FragSide.Enemy, "111"))))
    embed.text.linesIterator.toList should contain(
      ":fire: **402** — **[Bubble](https://www.tibia.com/community/?name=Bubble)** <:enemy:9> " +
        "· [:link:](https://discord.com/x/111)")
    // the only small grey line is the section's label
    embed.text.linesIterator.filter(_.startsWith("-# ")).toList shouldBe List("-# ᴛᴏᴘ ᴇɴᴇᴍʏ ᴋɪʟʟᴇᴅ")
  }

  test("a row with no link does not end in a dangling separator") {
    val embed = build(tally(topAlly = Some(TopKill("Bubble", 402, FragSide.Ally, ""))))
    embed.text.linesIterator.toList should contain(
      ":fire: **402** — **[Bubble](https://www.tibia.com/community/?name=Bubble)** <:enemy:9>")
  }

  test("a kill whose death was never posted still reads, without a dead link") {
    val embed = build(tally(topEnemy = Some(TopKill("Vestrik", 402, FragSide.Enemy, ""))))
    embed.text should include("**[Vestrik](")
    embed.text should include("**402** —")
    embed.text should not include ":link:"
  }

  test("sections with nothing in them are absent rather than empty labels") {
    // The title, the bar and the counts are one block; nothing follows them.
    build(tally()).blocks should have size 1
  }

  test("the card is red, and each section is a small-caps label of its own") {
    val card = build(tally(
      fraggers = List(Fragger("Bubble", FragSide.Ally, 4)),
      mostWanted = List(Repeat("Grimjaw", 271, 4)),
      topEnemy = Some(TopKill("Vestrik", 402, FragSide.Enemy, "111")),
      topAlly = Some(TopKill("Sarnoxx", 388, FragSide.Ally, "222"))),
      losses = List(loss("Vestrik", -24180400)))
    card.colour shouldBe PvpEmbeds.PvpColor
    card.blocks.map(_.linesIterator.next()) shouldBe List(
      "## :dagger: PVP", "-# ᴍᴏsᴛ ᴋɪʟʟs", "-# ᴍᴏsᴛ ᴅᴇᴀᴛʜs", "-# ᴍᴏsᴛ ᴇxᴘ ʟᴏsᴛ",
      "-# ᴛᴏᴘ ᴇɴᴇᴍʏ ᴋɪʟʟᴇᴅ", "-# ᴛᴏᴘ ᴀʟʟʏ ᴋɪʟʟᴇᴅ")
  }

  // --- vocations -----------------------------------------------------------

  test("a fragger carries the vocation from their cached sheet") {
    // Frag rows store a name and nothing else, so the icon has to be looked up.
    build(tally(fraggers = List(Fragger("Bubble", FragSide.Ally, 4)))).text should
      include(":fire: **766** — **[Bubble](")
  }

  test("a name with no cached sheet opens with the name, not with a gap") {
    // Somebody the bot has never drawn a sheet for renders without an icon; the
    // row has to close up rather than start with the space one would have sat in.
    val embed = build(tally(fraggers = List(Fragger("Nosheet", FragSide.Ally, 2))))
    embed.text.linesIterator.toList should contain(
      "**[Nosheet](https://www.tibia.com/community/?name=Nosheet)** <:enemy:9> · **2 kills**")
  }

  test("a fragger's level is looked up by lowercased name, and a killer nothing knows reads without one") {
    val byLowercase: String => Option[Int] = name => if (name == "sirmax") Some(512) else None
    val embed = build(tally(fraggers = List(Fragger("Sirmax", FragSide.Ally, 3), Fragger("Nobody", FragSide.Enemy, 1))),
      levelOf = byLowercase)
    embed.text should include("**512** — **[Sirmax](")
    // no level means no dash either: the row opens with the name
    embed.text.linesIterator.toList should contain(
      "**[Nobody](https://www.tibia.com/community/?name=Nobody)** <:enemy:9> · **1 kill**")
  }

  test("the lookup is by lowercased name, whatever casing the frag row kept") {
    val byLowercase: String => String = name => if (name == "bubble") "Elder Druid" else ""
    build(tally(fraggers = List(Fragger("Bubble", FragSide.Ally, 4))), vocationOf = byLowercase)
      .text should include(":snowflake: **766** — **[Bubble](")
  }

  // --- limits --------------------------------------------------------------

  test("a war too long for one message is carried on to the next, nobody dropped") {
    val long = "Abcdefghij Klmnopqrs Tuvwxyz"
    val frags = tally(
      fraggers = (1 to 60).toList.map(i => Fragger(long + i, FragSide.Ally, 20)),
      mostWanted = (1 to 5).toList.map(i => Repeat(long + i, 400, 6)))
    val packed = StatisticsCard.pack(List(pages(frags)))
    packed.size should be > 1
    packed.foreach(_.flatMap(_._2).map(_.length).sum should be <= StatisticsCard.MaxText)
    val whole = packed.flatten.flatMap(_._2).mkString("\n")
    (1 to 60).foreach(i => whole should include(long + i))
  }

  test("the fullest ordinary PVP card fits one V2 message on its own") {
    val long = "Averylongcharactername"
    val frags = tally(
      enemies = 47, allies = 23,
      fraggers = (1 to 10).toList.map(i => Fragger(long + i, FragSide.Ally, 20 - i)),
      mostWanted = (1 to 5).toList.map(i => Repeat(long + i, 400 - i, 6 - i)),
      topEnemy = Some(TopKill(long + "Enemy", 402, FragSide.Enemy, "111")),
      topAlly = Some(TopKill(long + "Ally", 388, FragSide.Ally, "222")))
    val losses = (1 to 5).toList.map(i => loss(long + i, -20000000L + i))
    pages(frags, losses).text.length should be < StatisticsCard.MaxText
  }
}
