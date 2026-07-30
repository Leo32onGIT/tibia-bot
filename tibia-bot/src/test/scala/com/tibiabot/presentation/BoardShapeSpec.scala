package com.tibiabot.presentation

import com.tibiabot.domain.Respawn
import com.tibiabot.respawn.RespawnCatalogue
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The board image, drawn from the catalogue the bot actually ships.
 *
 *  Layout is checked through the pure parts — the grouping and the column
 *  balance — because the drawing itself has nothing worth asserting beyond "it
 *  produced a PNG of a sane size". What the image *looks* like was settled by
 *  rendering it and looking at it, which no test can do.
 */
class BoardShapeSpec extends AnyFunSuite with Matchers {

  private def seedSpawns: List[Respawn] =
    RespawnCatalogue.seed.zipWithIndex.map { case (entry, index) =>
      Respawn(index.toLong, entry.code, entry.name, entry.creature, entry.region,
        "", "", "0", Respawn.SourceSeed, "seed")
    }

  private def spawn(code: String, city: String, name: String = "Somewhere") =
    Respawn(code.hashCode.toLong, code, name, "", city, "", "", "0", Respawn.SourceSeed, "seed")

  test("cities and the spawns inside them are ordered by code as a number") {
    // String order would put Svargrond's 1001 above Thais's 101, which is the
    // bug this ordering exists to avoid.
    val blocks = RespawnBoardImage.blocksOf(List(
      spawn("1001", "Svargrond"), spawn("101", "Thais"), spawn("201", "Carlin"),
      spawn("104", "Thais"), spawn("102", "Thais")))

    blocks.map(_.city) shouldBe List("Thais", "Carlin", "Svargrond")
    blocks.head.rows.map(_.code) shouldBe List("101", "102", "104")
  }

  test("a spawn with no city is filed rather than dropped") {
    val blocks = RespawnBoardImage.blocksOf(List(spawn("999", "")))
    blocks.map(_.city) shouldBe List("Elsewhere")
  }

  test("columns come out as even as the city sizes allow") {
    val blocks = RespawnBoardImage.blocksOf(seedSpawns)
    val columns = RespawnBoardImage.balance(blocks, 4)

    columns should have size 4
    // Every city lands in exactly one column, in order, none lost.
    columns.flatten.map(_.city) shouldBe blocks.map(_.city)

    val heights = columns.map(_.map(_.height).sum)
    // The point of balancing: no column towers over the shortest. A greedy fill
    // left the last column carrying every leftover, which was close to double.
    (heights.max.toDouble / heights.min) should be < 1.5
  }

  test("balancing never splits a city across two columns") {
    val blocks = RespawnBoardImage.blocksOf(seedSpawns)
    val columns = RespawnBoardImage.balance(blocks, 4)
    columns.flatten.map(_.city).distinct should have size blocks.size
  }

  test("an empty catalogue is nothing to draw rather than a blank image") {
    RespawnBoardImage.balance(Nil, 4) shouldBe empty
    RespawnBoardImage.render(Nil) shouldBe None
  }

  test("the bundled catalogue renders to a PNG of a sane size and shape") {
    val png = RespawnBoardImage.render(seedSpawns)
    png should not be empty

    val bytes = png.get
    // A PNG, by its magic number, rather than by trusting ImageIO.
    bytes.take(4) shouldBe Array(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte)
    // Comfortably inside Discord's 8MB attachment limit.
    bytes.length should be < 2 * 1024 * 1024

    val image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))
    image.getWidth should be > 800
    image.getHeight should be > 800
    // Squarish on purpose: an expanded image is fitted to the window, so the
    // closer to square, the larger it renders.
    val ratio = image.getWidth.toDouble / image.getHeight
    ratio should (be > 0.5 and be < 2.0)
  }

  test("a bigger catalogue makes a taller board, not a clipped one") {
    // Widths and heights are measured from the text, so adding spawns has to
    // grow the image rather than run off the edge of it.
    val doubled = seedSpawns ++ seedSpawns.map(s => s.copy(id = s.id + 10000, code = s.code + "x"))
    val before = RespawnBoardImage.render(seedSpawns).map(read).get
    val after = RespawnBoardImage.render(doubled).map(read).get
    after.getHeight should be > before.getHeight
  }

  private def read(bytes: Array[Byte]): java.awt.image.BufferedImage =
    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))
}
