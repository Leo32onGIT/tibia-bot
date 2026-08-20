package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.awt.image.{BufferedImage, IndexColorModel}
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class SpriteInkSpec extends AnyWordSpec with Matchers {

  /** A gif of `size` square, with ink on rows `from` until `until` and the rest
   *  transparent — a creature standing somewhere in a canvas bigger than it is.
   *
   *  Written through ImageIO rather than assembled by hand so the bytes are a
   *  real gif with a real palette, which is what the measurement has to cope
   *  with. Index 0 is the transparent one.
   */
  private def gif(size: Int, from: Int, until: Int): Array[Byte] = {
    val palette = new IndexColorModel(
      8, 2,
      Array[Byte](0, 120.toByte), Array[Byte](0, 90), Array[Byte](0, 60),
      0)
    val image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_INDEXED, palette)
    val raster = image.getRaster
    for (y <- from until until; x <- 0 until size) raster.setSample(x, y, 0, 1)
    val out = new ByteArrayOutputStream()
    ImageIO.write(image, "gif", out)
    out.toByteArray
  }

  /** The same gif, rewritten to claim a taller canvas with the frame sitting at
   *  `top` on it.
   *
   *  This is what an animation frame looks like: a patch of the canvas rather
   *  than the whole of it, placed by its own header. ImageIO's writer will not
   *  produce one directly, so the two numbers are patched into the bytes — the
   *  logical screen height in the header, and the frame's top position in the
   *  image descriptor that follows the palette.
   */
  private def placedOnTallerCanvas(bytes: Array[Byte], canvas: Int, top: Int): Array[Byte] = {
    val patched = bytes.clone()
    def putShort(at: Int, value: Int): Unit = {
      patched(at) = (value & 0xFF).toByte
      patched(at + 1) = ((value >> 8) & 0xFF).toByte
    }
    // Logical screen height, bytes 8-9 of the header.
    putShort(8, canvas)
    val descriptor = patched.indexOf(0x2C.toByte)
    // Image descriptor: separator, left, top, width, height.
    putShort(descriptor + 3, top)
    patched
  }

  "boxOf" should {

    "find the creature in a canvas it does not fill" in {
      val box = SpriteInk.boxOf(gif(64, 24, 64))
      box shouldBe Some(InkBox(canvasHeight = 64, top = 24, bottom = 64))
    }

    "report a tightly cropped sprite as filling its canvas" in {
      SpriteInk.boxOf(gif(32, 0, 32)) shouldBe Some(InkBox(32, 0, 32))
    }

    // Not a sprite anybody can use, and measuring it would divide by an ink box
    // that does not exist.
    "answer nothing for a canvas with nothing drawn on it" in {
      SpriteInk.boxOf(gif(32, 0, 0)) shouldBe None
    }

    // What a fetch that came back as an error page looks like.
    "answer nothing for bytes that are not an image" in {
      SpriteInk.boxOf("<!DOCTYPE html><html>nope</html>".getBytes("UTF-8")) shouldBe None
    }

    "answer nothing for no bytes at all" in {
      SpriteInk.boxOf(Array.emptyByteArray) shouldBe None
    }

    // The fiddly one. A frame is only the part of the canvas it redraws, and
    // measuring it as though it started at the top would put a creature's feet
    // where its head is.
    "place a frame by its own offset rather than at the top of the canvas" in {
      val frame = gif(16, 0, 16)
      SpriteInk.boxOf(placedOnTallerCanvas(frame, canvas = 64, top = 48)) shouldBe
        Some(InkBox(canvasHeight = 64, top = 48, bottom = 64))
    }
  }

  "nudge" should {

    "move a creature drawn low in its canvas upwards" in {
      // Ink from 24 to 64 has its centre at 44, twelve rows below the canvas's
      // own centre of 32 — so up by twelve sixty-fourths.
      InkBox(64, 24, 64).nudge shouldBe (-12.0 / 64)
    }

    "move a creature drawn high in its canvas downwards" in {
      InkBox(64, 0, 40).nudge shouldBe (12.0 / 64)
    }

    "leave a creature that already fills its canvas alone" in {
      InkBox(64, 0, 64).nudge shouldBe 0.0
    }

    "leave a creature that is merely small but centred alone" in {
      InkBox(64, 16, 48).nudge shouldBe 0.0
    }
  }

  "nudgeOf" should {

    "answer with the shift a sprite drawn low in its canvas needs" in {
      SpriteInk.nudgeOf(gif(64, 24, 64)) shouldBe Some(-12.0 / 64)
    }

    // Under two pixels on a card. Absent rather than zero, so the common case
    // costs nothing in the payload.
    "answer nothing for a shift too small to see" in {
      // Ink 0 until 63 on a 64 canvas: half a row off centre.
      SpriteInk.nudgeOf(gif(64, 0, 63)) shouldBe None
    }

    "answer nothing for a sprite it cannot read" in {
      SpriteInk.nudgeOf("not a gif".getBytes("UTF-8")) shouldBe None
    }
  }
}
