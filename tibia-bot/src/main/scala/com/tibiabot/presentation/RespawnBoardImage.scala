package com.tibiabot.presentation

import com.tibiabot.domain.Respawn
import com.tibiabot.respawn.RespawnCatalogue

import java.awt.image.BufferedImage
import java.awt.{Color, Font, FontMetrics, Graphics2D, RenderingHints}
import java.io.ByteArrayOutputStream
import scala.util.Try

/** The catalogue drawn as a PNG for the board post.
 *
 *  An image rather than embeds because the whole point of the board is being
 *  scanned: three hundred codes across four columns is a shape Discord's embeds
 *  cannot make, whatever they are given. The cost is real and worth stating —
 *  nothing here can be selected, copied or searched, so a code is read off the
 *  screen and typed into the Claim modal by hand.
 *
 *  Nothing about it survives being looked at inline: Discord fits an attachment
 *  into roughly 550x350, which puts this at a fifth of its size. It is drawn for
 *  the expanded view, which is why it is laid out squarish (the lightbox fits to
 *  the window, so the closer to square, the larger it renders) and spaced
 *  generously rather than packed tight.
 *
 *  Java2D, so there is no new dependency and nothing to install in the image.
 *  The names are set in a bundled font rather than the base image's (see
 *  `nameFont`); everything else uses the JDK's own sans. Either way no width is
 *  assumed — they are all measured, so a longer name or an added spawn grows the
 *  image instead of running off it.
 */
object RespawnBoardImage extends com.typesafe.scalalogging.StrictLogging {

  private val Background = new Color(0x1E1F22)
  private val CityColor = new Color(0xFFFFFF)
  private val CodeColor = new Color(0xF0B232)
  private val NameColor = new Color(0xE8E9EA)
  private val FooterColor = new Color(0x8A8E94)

  private val RowSize = 15
  private val CitySize = 17
  private val RowHeight = 19
  private val CityHeight = CitySize + 14
  /** Generous, because this is read expanded: the gaps are what separate one
   *  city from the next and one column from its neighbour, and a board packed
   *  tight enough to be compact is one nobody can scan. */
  private val CityGap = 24
  private val ColumnGap = 40
  /** Between a code and the name it belongs to. Fixed, so every name in a column
   *  starts at the same x however wide its code is. */
  private val CodeGap = 10
  private val Padding = 28
  private val FooterHeight = 26
  private val Columns = 4

  private val cityFont = new Font(Font.SANS_SERIF, Font.BOLD, CitySize)
  private val codeFont = new Font(Font.SANS_SERIF, Font.BOLD, RowSize)
  private val footerFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12)

  /** Lato Bold, bundled rather than borrowed from the base image.
   *
   *  The names are the bulk of the board and the JDK's own DejaVu sets them
   *  around 200px wider than this at the same size, which is width the image
   *  cannot spare. Shipping the file also pins what the board looks like: read
   *  off the container, a base-image update that changed its fonts would silently
   *  reflow every board in every guild.
   *
   *  Falls back to the JDK's sans if the resource is somehow missing, because a
   *  board drawn in the wrong font beats no board at all. Loaded once — parsing a
   *  650KB TTF per render would be paid on every redraw. */
  private lazy val nameFont: Font = {
    val loaded = Try {
      val stream = getClass.getResourceAsStream("/fonts/Lato-Bold.ttf")
      require(stream != null, "/fonts/Lato-Bold.ttf is not on the classpath")
      try Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(RowSize.toFloat)
      finally stream.close()
    }
    loaded.failed.foreach(error =>
      logger.warn("Could not load the bundled board font; falling back to the JDK's sans", error))
    loaded.getOrElse(new Font(Font.SANS_SERIF, Font.BOLD, RowSize))
  }

  /** A city and the spawns in it, in code order. */
  private[presentation] final case class Block(city: String, rows: List[Respawn]) {
    /** What the block costs vertically, which is all the balancer needs to know. */
    def height: Int = CityHeight + rows.size * RowHeight + CityGap
  }

  /** The catalogue grouped into cities, both the cities and the spawns inside
   *  them in code order — numerically, so 101 precedes 1001. */
  private[presentation] def blocksOf(spawns: List[Respawn]): List[Block] =
    spawns
      .groupBy(spawn => if (spawn.region.trim.isEmpty) "Elsewhere" else spawn.region.trim)
      .toList
      .map { case (city, rows) => Block(city, rows.sortBy(row => RespawnCatalogue.sortKey(row.code))) }
      .sortBy(block => RespawnCatalogue.sortKey(block.rows.head.code))

  /** Split blocks into `count` columns, keeping them in order, so that the
   *  tallest column is as short as it can be.
   *
   *  Binary search on that height rather than filling each column to an equal
   *  share: the greedy version leaves whatever is left over in the last column,
   *  which on this catalogue put a quarter of the page under empty space.
   */
  private[presentation] def balance(blocks: List[Block], count: Int): List[List[Block]] = {
    if (blocks.isEmpty || count < 1) return Nil
    val heights = blocks.map(_.height)
    val low = math.max(heights.max, heights.sum / count)

    def columnsNeeded(limit: Int): Int =
      heights.foldLeft((1, 0)) { case ((columns, used), height) =>
        if (used > 0 && used + height > limit) (columns + 1, height) else (columns, used + height)
      }._1

    var lo = low
    var hi = heights.sum
    while (lo < hi) {
      val mid = lo + (hi - lo) / 2
      if (columnsNeeded(mid) <= count) hi = mid else lo = mid + 1
    }

    val (packed, last) = blocks.foldLeft((List.empty[List[Block]], List.empty[Block])) {
      case ((done, current), block) =>
        if (current.nonEmpty && current.map(_.height).sum + block.height > lo)
          (done :+ current, List(block))
        else (done, current :+ block)
    }
    if (last.isEmpty) packed else packed :+ last
  }

  /** Render the catalogue, or None when there is nothing to draw — a guild whose
   *  catalogue is empty gets no attachment rather than a blank image. */
  def render(spawns: List[Respawn]): Option[Array[Byte]] = {
    val blocks = blocksOf(spawns)
    if (blocks.isEmpty) return None

    val columns = balance(blocks, Columns)

    // Measured against a throwaway surface: the image cannot be created until
    // its width is known, and its width is the text it has to hold.
    val scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
    val cityMetrics = scratch.getFontMetrics(cityFont)
    val codeMetrics = scratch.getFontMetrics(codeFont)
    val nameMetrics = scratch.getFontMetrics(nameFont)
    val footerMetrics = scratch.getFontMetrics(footerFont)

    // Every name in a column starts at the same x, whatever its code measures.
    // A column mixing `101` with `1406a` staggered them otherwise, which turns a
    // list you scan down into a ragged edge that has to be read.
    val codeWidths = columns.map { column =>
      column.flatMap(_.rows).map(row => codeMetrics.stringWidth(row.code)).foldLeft(0)(math.max)
    }
    val widths = columns.zip(codeWidths).map { case (column, codeWidth) =>
      val names = column.flatMap(_.rows).map(row => nameMetrics.stringWidth(row.name)).foldLeft(0)(math.max)
      val cities = column.map(block => cityMetrics.stringWidth(block.city)).foldLeft(0)(math.max)
      math.max(cities, codeWidth + CodeGap + names)
    }
    val tallest = columns.map(_.map(_.height).sum).foldLeft(0)(math.max)

    val width = Padding * 2 + ColumnGap * (columns.size - 1) + widths.sum
    val height = Padding * 2 + tallest + FooterHeight

    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setColor(Background)
      g.fillRect(0, 0, width, height)

      var x = Padding
      columns.zip(widths).zip(codeWidths).foreach { case ((column, columnWidth), codeWidth) =>
        var y = Padding + CitySize
        column.foreach { block =>
          g.setFont(cityFont)
          g.setColor(CityColor)
          g.drawString(block.city, x, y)
          y += CityHeight - 8
          block.rows.foreach { row =>
            g.setFont(codeFont)
            g.setColor(CodeColor)
            g.drawString(row.code, x, y)
            g.setFont(nameFont)
            g.setColor(NameColor)
            g.drawString(row.name, x + codeWidth + CodeGap, y)
            y += RowHeight
          }
          y += CityGap
        }
        x += columnWidth + ColumnGap
      }

      g.setFont(footerFont)
      g.setColor(FooterColor)
      val count = s"${spawns.size} respawn codes"
      g.drawString(count, Padding, height - Padding)
      val mark = "Violent Bot"
      g.drawString(mark, width - Padding - footerMetrics.stringWidth(mark), height - Padding)
    } finally {
      g.dispose()
      scratch.dispose()
    }

    val bytes = new ByteArrayOutputStream()
    javax.imageio.ImageIO.write(image, "png", bytes)
    Some(bytes.toByteArray)
  }

  /** A fingerprint of everything this image would show, so a caller can tell
   *  whether a board already posted is still the right one.
   *
   *  Redrawing costs a REST edit per guild and the board changes on the order of
   *  once a month, so a bot that redrew on every boot would spend its rate limit
   *  reposting an identical picture. Comparing fingerprints makes the redraw
   *  follow the catalogue instead of the restart.
   *
   *  Over exactly the three fields that are drawn — a code, its city and its name
   *  — and no others. Creature is deliberately absent: it decides the sprite on
   *  the dashboard and appears nowhere on this image, so a curated creature
   *  change must not trigger a redraw that would look identical. Sorted, because
   *  the row order out of the database is not promised and reordering alone
   *  changes nothing about what is drawn.
   */
  def digestOf(spawns: List[Respawn]): String = {
    // The three fields are separated by a character a spawn name cannot hold,
    // so moving a letter from the end of one to the start of the next is a
    // different fingerprint rather than the same concatenation.
    val canonical = spawns
      .map(spawn => s"${spawn.code.trim}${spawn.region.trim}${spawn.name.trim}")
      .sorted
      .mkString("")
    val bytes = java.security.MessageDigest.getInstance("SHA-256")
      .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    bytes.take(16).map(b => f"${b & 0xff}%02x").mkString
  }

  /** The attachment's name, which is also what Discord shows if the image fails
   *  to load. */
  val FileName: String = "respawn-codes.png"
}
