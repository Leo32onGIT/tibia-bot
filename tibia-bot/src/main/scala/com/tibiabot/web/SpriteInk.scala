package com.tibiabot.web

import com.typesafe.scalalogging.StrictLogging

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.{ImageIO, ImageReader}
import javax.imageio.metadata.{IIOMetadata, IIOMetadataNode}
import scala.util.control.NonFatal

/** The drawn part of a sprite, within its canvas.
 *
 *  `right` and `bottom` are the row and column after the last with ink in them,
 *  so each pair reads as a half-open range like any other. An empty box cannot
 *  be built: [[SpriteInk.boxOf]] answers None for a sprite with nothing in it
 *  rather than a box of no size.
 */
final case class InkBox(canvasWidth: Int, canvasHeight: Int,
                        left: Int, top: Int, right: Int, bottom: Int) {

  /** How far the art wants shifting for the creature to sit where its canvas
   *  claims it does, as a fraction of canvas width. Negative moves it left. */
  def nudgeX: Double = (canvasWidth / 2.0 - (left + right) / 2.0) / canvasWidth

  /** The same downward. Negative moves it up. */
  def nudgeY: Double = (canvasHeight / 2.0 - (top + bottom) / 2.0) / canvasHeight
}

/** How far a sprite's art should move on each axis, as a fraction of that axis of
 *  the canvas. Fractions rather than pixels because the same file is painted at
 *  wildly different sizes — near natural size on a card, a dozen times it in the
 *  watermark — so each surface multiplies by whatever it painted. */
final case class SpriteNudge(x: Double, y: Double) {
  def isZero: Boolean = x == 0.0 && y == 0.0
}

object SpriteNudge {
  /** Measured, and wanting nothing. */
  val None: SpriteNudge = SpriteNudge(0.0, 0.0)
}

/** Where a creature actually is inside its sprite file.
 *
 *  Tibia sprites stand on the bottom of a cell often bigger than the creature —
 *  the Misguided Bully is a 64x64 file with the creature in its lower half.
 *  `object-fit: contain` centres the *canvas*, empty air included, so the creature
 *  comes out low. CSS cannot see past the canvas, so this is measured here and the
 *  page shifts by what it is told.
 *
 *  Both axes are measured though they differ in weight: of thirty real sprites,
 *  vertical offsets are the rule and run to a quarter of the canvas, while all but
 *  four are horizontally centred to within half a pixel. The exception is a sprite
 *  like the Mitmah Seer, drawn into its corner.
 *
 *  Note that a creature whose ink reaches the canvas edge was cut off by its own
 *  file, and centring brings that cut edge out from under the card's edge where it
 *  was hidden. That is the file being wrong, and the price of putting the creature
 *  where it belongs.
 *
 *  Bytes in, two numbers out: no cache, filesystem or config.
 *  [[CreatureSpriteCache]] decides when to ask. */
object SpriteInk extends StrictLogging {

  /** Alpha at or above which a pixel counts as drawn. GIF transparency is all
   *  or nothing, so anything above zero would do for the sprites we actually
   *  have; the threshold is for a soft-edged PNG, where a fringe nobody can see
   *  should not be what decides where the creature is. */
  private val InkAlpha = 16

  /** Below this, a shift is not worth carrying. Two per cent of a card's 84px
   *  box is under two pixels — invisible, and it would put a number in every
   *  row of the catalogue payload to say so. Applied per axis, so the common
   *  sprite that is centred sideways and low carries one number rather than
   *  two, and a sprite centred both ways carries none at all. */
  val Deadband = 0.02

  /** The shift for these bytes, or None for a sprite that needs none — which
   *  covers a canvas the creature already fills, a file we cannot read, and one
   *  with no ink in it at all. All three mean the same thing to a caller: leave
   *  the art where `object-fit` puts it. */
  def nudgeOf(bytes: Array[Byte]): Option[SpriteNudge] =
    boxOf(bytes)
      .map(box => SpriteNudge(worthMoving(box.nudgeX), worthMoving(box.nudgeY)))
      .filterNot(_.isZero)

  private def worthMoving(nudge: Double): Double =
    if (math.abs(nudge) < Deadband) 0.0 else nudge

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
    var left = Int.MaxValue
    var top = Int.MaxValue
    var right = -1
    var bottom = -1
    // How far the frames themselves reach, for a file whose canvas the metadata
    // will not state — a lower bound on it rather than a guess.
    var reachX = 0
    var reachY = 0
    var index = 0
    while (index < frames) {
      val frame = reader.read(index)
      val (offsetX, offsetY) = framePosition(reader, index)
      reachX = math.max(reachX, offsetX + frame.getWidth)
      reachY = math.max(reachY, offsetY + frame.getHeight)
      inkBounds(frame).foreach { case (l, t, r, b) =>
        left = math.min(left, offsetX + l)
        top = math.min(top, offsetY + t)
        right = math.max(right, offsetX + r + 1)
        bottom = math.max(bottom, offsetY + b + 1)
      }
      index += 1
    }
    val width = canvasSize(reader, "logicalScreenWidth").filter(_ > 0).getOrElse(reachX)
    val height = canvasSize(reader, "logicalScreenHeight").filter(_ > 0).getOrElse(reachY)
    if (right < 0 || width <= 0 || height <= 0) None
    else
      Some(InkBox(width, height,
        math.max(0, left), math.max(0, top),
        math.min(right, width), math.min(bottom, height)))
  }

  /** The outermost drawn pixels of this frame, as (left, top, right, bottom)
   *  inclusive. */
  private def inkBounds(frame: BufferedImage): Option[(Int, Int, Int, Int)] = {
    val width = frame.getWidth
    val height = frame.getHeight
    var left = Int.MaxValue
    var top = -1
    var right = -1
    var bottom = -1
    var y = 0
    while (y < height) {
      var x = 0
      while (x < width) {
        if ((frame.getRGB(x, y) >>> 24) >= InkAlpha) {
          if (x < left) left = x
          if (x > right) right = x
          if (top < 0) top = y
          bottom = y
        }
        x += 1
      }
      y += 1
    }
    if (right < 0) None else Some((left, top, right, bottom))
  }

  /** Where this frame sits on the canvas.
   *
   *  Java's GIF reader hands back each frame cropped to the part of the canvas
   *  it redraws — an animation that only moves a creature's feet is, to the
   *  reader, a picture of feet — and reading that as though it began at the top
   *  left of the canvas would put the ink somewhere it never was. The position
   *  is in the frame's own metadata, so that is where it is read from.
   *
   *  The origin when there is no metadata to read: a single-frame file is its
   *  own canvas, which is exactly what an offset of zero says.
   */
  private def framePosition(reader: ImageReader, index: Int): (Int, Int) =
    nativeTree(Option(reader.getImageMetadata(index)))
      .map(root => (attr(root, "ImageDescriptor", "imageLeftPosition").getOrElse(0),
                    attr(root, "ImageDescriptor", "imageTopPosition").getOrElse(0)))
      .getOrElse((0, 0))

  /** The canvas the frames are drawn on, as the file itself declares it. */
  private def canvasSize(reader: ImageReader, attribute: String): Option[Int] =
    nativeTree(Option(reader.getStreamMetadata))
      .flatMap(root => attr(root, "LogicalScreenDescriptor", attribute))

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
