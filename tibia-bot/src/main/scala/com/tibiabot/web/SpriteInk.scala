package com.tibiabot.web

import com.typesafe.scalalogging.StrictLogging

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.{ImageIO, ImageReader}
import javax.imageio.metadata.{IIOMetadata, IIOMetadataNode}
import scala.util.control.NonFatal

/** The vertical span of the drawn part of a sprite, within its canvas.
 *
 *  `top` is the first row with ink in it and `bottom` the row after the last,
 *  so the pair reads as a half-open range like any other. An empty box cannot
 *  be built: [[SpriteInk.boxOf]] answers None for a sprite with nothing in it
 *  rather than a box of no height.
 */
final case class InkBox(canvasHeight: Int, top: Int, bottom: Int) {

  /** How far the art wants shifting for the creature to sit where its canvas
   *  claims it does, as a fraction of canvas height. Negative moves it up.
   *
   *  A fraction rather than a count of pixels because the same file is painted
   *  at wildly different sizes — a little over natural size on a card, a dozen
   *  times it in the window's watermark — so a pixel count right for one would
   *  be absurd on the other. Each surface multiplies by whatever it painted.
   */
  def nudge: Double = {
    val inkCentre = (top + bottom) / 2.0
    (canvasHeight / 2.0 - inkCentre) / canvasHeight
  }
}

/** Where a creature actually is inside its sprite file.
 *
 *  Tibia sprites are drawn standing on the bottom of their cell, and the cell
 *  is often bigger than the creature — a 64x64 canvas holding a creature in its
 *  lower half is ordinary. `object-fit: contain` centres the *canvas*, so it
 *  centres the empty air along with the creature and the art comes out low.
 *  Nothing in CSS can see past the canvas, so the answer is measured here and
 *  sent to the page, which shifts by what it is told.
 *
 *  Deliberately free of everything else: bytes in, one number out. No cache, no
 *  filesystem, no config. [[CreatureSpriteCache]] decides when to ask.
 */
object SpriteInk extends StrictLogging {

  /** Alpha at or above which a pixel counts as drawn. GIF transparency is all
   *  or nothing, so anything above zero would do for the sprites we actually
   *  have; the threshold is for a soft-edged PNG, where a fringe nobody can see
   *  should not be what decides where the creature is. */
  private val InkAlpha = 16

  /** Below this, a nudge is not worth carrying. Two per cent of a card's 84px
   *  box is under two pixels — invisible, and it would put a number in every
   *  row of the catalogue payload to say so. A well-cropped sprite therefore
   *  measures as wanting nothing at all, which is the common case and the one
   *  that should cost nothing. */
  val Deadband = 0.02

  /** The shift for these bytes, or None for a sprite that needs none — which
   *  covers a canvas the creature already fills, a file we cannot read, and one
   *  with no ink in it at all. All three mean the same thing to a caller: leave
   *  the art where `object-fit` puts it. */
  def nudgeOf(bytes: Array[Byte]): Option[Double] =
    boxOf(bytes).map(_.nudge).filter(nudge => math.abs(nudge) >= Deadband)

  /** Where the ink is, across every frame of an animation.
   *
   *  The union rather than the first frame: a creature that leans out on one
   *  frame of its idle animation is still part of the picture, and measuring
   *  frame zero alone would shift the whole thing to centre a pose it holds for
   *  a tenth of a second.
   */
  def boxOf(bytes: Array[Byte]): Option[InkBox] =
    try {
      val stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))
      if (stream == null) None
      else
        try {
          val readers = ImageIO.getImageReaders(stream)
          // Not an image we know how to read. Silent: a sprite that arrived as
          // an error page is already reported where it was fetched, and this is
          // not the place to say so a second time.
          if (!readers.hasNext) None
          else {
            val reader = readers.next()
            try {
              reader.setInput(stream)
              measure(reader)
            } finally reader.dispose()
          }
        } finally stream.close()
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Could not measure a creature sprite: ${e.getClass.getSimpleName}: ${e.getMessage}")
        None
    }

  private def measure(reader: ImageReader): Option[InkBox] = {
    val frames = reader.getNumImages(true)
    var top = Int.MaxValue
    var bottom = -1
    // How far down the frames themselves reach, for a file whose canvas the
    // metadata will not state — a lower bound on the canvas rather than a guess.
    var reach = 0
    var index = 0
    while (index < frames) {
      val frame = reader.read(index)
      val offset = frameTop(reader, index)
      reach = math.max(reach, offset + frame.getHeight)
      inkRows(frame).foreach { case (first, last) =>
        top = math.min(top, offset + first)
        bottom = math.max(bottom, offset + last + 1)
      }
      index += 1
    }
    val canvas = canvasHeight(reader).filter(_ > 0).getOrElse(reach)
    if (bottom < 0 || canvas <= 0) None
    else Some(InkBox(canvas, math.max(0, top), math.min(bottom, canvas)))
  }

  /** The first and last row of this frame with anything drawn on it. */
  private def inkRows(frame: BufferedImage): Option[(Int, Int)] = {
    val width = frame.getWidth
    val height = frame.getHeight
    var first = -1
    var last = -1
    var y = 0
    while (y < height) {
      var x = 0
      var drawn = false
      while (x < width && !drawn) {
        if ((frame.getRGB(x, y) >>> 24) >= InkAlpha) drawn = true
        x += 1
      }
      if (drawn) {
        if (first < 0) first = y
        last = y
      }
      y += 1
    }
    if (first < 0) None else Some((first, last))
  }

  /** Where this frame sits on the canvas.
   *
   *  Java's GIF reader hands back each frame cropped to the part of the canvas
   *  it redraws — an animation that only moves a creature's feet is, to the
   *  reader, a picture of feet — and reading that as though it began at the top
   *  of the canvas would put the ink somewhere it never was. The position is in
   *  the frame's own metadata, so that is where it is read from.
   *
   *  Zero when there is no metadata to read: a single-frame file is its own
   *  canvas, which is exactly what an offset of zero says.
   */
  private def frameTop(reader: ImageReader, index: Int): Int =
    nativeTree(Option(reader.getImageMetadata(index)))
      .flatMap(root => attr(root, "ImageDescriptor", "imageTopPosition"))
      .getOrElse(0)

  /** The canvas the frames are drawn on, as the file itself declares it. */
  private def canvasHeight(reader: ImageReader): Option[Int] =
    nativeTree(Option(reader.getStreamMetadata))
      .flatMap(root => attr(root, "LogicalScreenDescriptor", "logicalScreenHeight"))

  /** A metadata tree in the reader's own format, where there is one.
   *
   *  Every part of this is optional — a format may have no native metadata, and
   *  a reader may refuse the tree — and none of it is worth failing a
   *  measurement over, so each absence reads as "no position stated". */
  private def nativeTree(metadata: Option[IIOMetadata]): Option[IIOMetadataNode] =
    try {
      metadata.flatMap(m =>
        Option(m.getNativeMetadataFormatName)
          .map(format => m.getAsTree(format).asInstanceOf[IIOMetadataNode]))
    } catch {
      case NonFatal(_) => None
    }

  private def attr(root: IIOMetadataNode, tag: String, name: String): Option[Int] = {
    val nodes = root.getElementsByTagName(tag)
    if (nodes.getLength == 0) None
    else
      Option(nodes.item(0).asInstanceOf[IIOMetadataNode].getAttribute(name))
        .filter(_.nonEmpty)
        .flatMap(value => scala.util.Try(value.toInt).toOption)
  }
}
