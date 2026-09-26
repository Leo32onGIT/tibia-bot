package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OnlineListGroupingSpec extends AnyFunSuite with Matchers {

  test("collapses rows into one bucket per guild, keeping each guild's messages") {
    val rows = List(
      "Bona Fide" -> "msgA",
      "Bona Fide" -> "msgB",
      "Red Rising" -> "msgC")
    val grouped = OnlineListGrouping.groupByGuild(rows).toMap
    grouped("Bona Fide") should contain theSameElementsAs List("msgA", "msgB")
    grouped("Red Rising") shouldBe List("msgC")
  }

  test("orders guilds by descending member count") {
    val rows = List(
      "Small" -> "s1",
      "Big" -> "b1",
      "Big" -> "b2",
      "Big" -> "b3",
      "Mid" -> "m1",
      "Mid" -> "m2")
    OnlineListGrouping.groupByGuild(rows).map(_._1) shouldBe List("Big", "Mid", "Small")
  }

  test("always places the guildless bucket last, even when it is the largest") {
    val rows = List(
      "" -> "n1",
      "" -> "n2",
      "" -> "n3",
      "Guild" -> "g1")
    val ordered = OnlineListGrouping.groupByGuild(rows)
    ordered.last._1 shouldBe ""
    ordered.last._2 should have size 3
    ordered.head._1 shouldBe "Guild"
  }

  test("empty input yields an empty list") {
    OnlineListGrouping.groupByGuild(Nil) shouldBe Nil
  }

  test("a single guildless group is returned as the only (last) bucket") {
    OnlineListGrouping.groupByGuild(List("" -> "x", "" -> "y")) shouldBe List("" -> List("x", "y"))
  }

  // --- labels ---

  private val others: Int => String = n => OnlineListGrouping.label("Others", n)

  test("a label is a small grey line with its name in bold capitals and the count") {
    OnlineListGrouping.label("No Guild", 3) shouldBe "-# **NO GUILD** · 3"
  }

  test("an icon leads the label") {
    OnlineListGrouping.label("Allies", 12, ":ally:") shouldBe "-# :ally: **ALLIES** · 12"
  }

  test("a guild's label links its name to the guild's page, keeping the page's own spelling") {
    OnlineListGrouping.guildLabel("Bona Fide", 2) shouldBe s"-# **[BONA FIDE](${Urls.guildUrl("Bona Fide")})** · 2"
  }

  test("withHeaders prefixes each guild bucket with its label and count, lines following") {
    val grouped = List("Bona Fide" -> List("m1", "m2"))
    OnlineListGrouping.withHeaders(grouped, others) shouldBe List(
      OnlineListGrouping.guildLabel("Bona Fide", 2), "m1", "m2")
  }

  test("withHeaders applies the caller's guildless label to the empty-name bucket") {
    val grouped = List("" -> List("n1", "n2", "n3"))
    OnlineListGrouping.withHeaders(grouped, n => OnlineListGrouping.label("No Guild", n)) shouldBe List(
      "-# **NO GUILD** · 3", "n1", "n2", "n3")
  }

  test("withHeaders preserves bucket order (guilds first, guildless last) end to end") {
    val grouped = OnlineListGrouping.groupByGuild(
      List("Big" -> "b1", "Big" -> "b2", "" -> "n1"))
    OnlineListGrouping.withHeaders(grouped, others) shouldBe List(
      OnlineListGrouping.guildLabel("Big", 2), "b1", "b2", "-# **OTHERS** · 1", "n1")
  }

  // --- combinedChannelBody ---

  test("combinedChannelBody labels allies and enemies when all three categories are present") {
    val out = OnlineListGrouping.combinedChannelBody(
      alliesList = List("a1"),
      enemiesList = List("e1"),
      neutralsList = List("n1"),
      flattenedNeutralsList = List("-# **OTHERS** · 1", "n1"),
      allyEmoji = ":ally:", enemyEmoji = ":enemy:")
    out shouldBe List(
      "-# :ally: **ALLIES** · 1", "a1",
      "-# :enemy: **ENEMIES** · 1", "e1",
      "-# **OTHERS** · 1", "n1")
  }

  test("combinedChannelBody adds no section header when allies are the only category") {
    OnlineListGrouping.combinedChannelBody(
      alliesList = List("a1", "a2"),
      enemiesList = Nil, neutralsList = Nil, flattenedNeutralsList = Nil,
      allyEmoji = ":ally:", enemyEmoji = ":enemy:") shouldBe List("a1", "a2")
  }

  test("combinedChannelBody drops a lone Others label when neutrals are the only category") {
    OnlineListGrouping.combinedChannelBody(
      alliesList = Nil, enemiesList = Nil,
      neutralsList = List("n1", "n2"),
      flattenedNeutralsList = List("-# **OTHERS** · 2", "n1", "n2"),
      allyEmoji = ":ally:", enemyEmoji = ":enemy:") shouldBe List("n1", "n2")
  }

  test("combinedChannelBody keeps neutral guild labels when they are present") {
    val flattened = List(OnlineListGrouping.guildLabel("GuildX", 1), "g1", "-# **OTHERS** · 1", "n1")
    OnlineListGrouping.combinedChannelBody(
      alliesList = Nil, enemiesList = Nil,
      neutralsList = List("g1", "n1"),
      flattenedNeutralsList = flattened,
      allyEmoji = ":ally:", enemyEmoji = ":enemy:") shouldBe flattened
  }

  test("combinedChannelBody labels both allies and enemies when neutrals are absent") {
    OnlineListGrouping.combinedChannelBody(
      alliesList = List("a1"), enemiesList = List("e1"),
      neutralsList = Nil, flattenedNeutralsList = Nil,
      allyEmoji = ":ally:", enemyEmoji = ":enemy:") shouldBe List(
      "-# :ally: **ALLIES** · 1", "a1",
      "-# :enemy: **ENEMIES** · 1", "e1")
  }
}
