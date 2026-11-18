package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.test.SpriteSheets
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * What the sprite sheet suites share: a sheet drawn at the frame's corner, the frame the shared timeline puts on each
 * slot, and the cell index a written or previewed frame shows.
 */
internal object SheetReads {
  /**
   * The sheet drawn at the top start corner with no margin, so its first texel sits on the frame's first pixel.
   */
  fun overlayOf(
    sheet: SpriteSheet,
    scale: Float = SCALE,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
    png: ByteArray = SpriteSheets.png(),
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(png),
      corner = Corner.TopStart,
      margin = 0f,
      scale = scale,
      visibleDuring = visibleDuring,
      animation = animation,
      sheet = sheet,
    )

  /**
   * The timeline [sheet] counts its frames on, built the way the sheet builds its own.
   */
  fun timelineOf(sheet: SpriteSheet): FrameTimeline {
    val first = sheet.frameDurations.first()
    return if (sheet.frameDurations.all { it == first }) {
      FrameTimeline.uniform(sheet.frameCount, first, sheet.plays)
    } else {
      FrameTimeline(sheet.frameDurations, sheet.plays)
    }
  }

  /**
   * The frame [spec]'s sheet shows on [slot] of an export at [rate] over [span].
   */
  fun expectedFrame(
    spec: ImageOverlay,
    span: TimeRange,
    slot: Long,
    rate: Int,
  ): Int = timelineOf(checkNotNull(spec.sheet)).frameOnSlot(spec.drawnRangeWithin(span).start, slot, rate)

  /**
   * The cell index in the drawn rectangle [rect] of a frame of [frame] size, `null` when a square is neither black nor
   * white. [texels] reads one pixel of the frame as packed ARGB.
   */
  fun indexIn(
    texels: (x: Int, y: Int) -> Int,
    rect: NormalizedRect,
    frame: Size,
  ): Int? {
    val cell = cellIn(texels, rect, frame)
    return runCatching { SpriteSheets.indexOf(cell) }.getOrNull()
  }

  /**
   * The cell of [rect] as packed ARGB, each texel read at the middle of the pixels it covers.
   *
   * A texel near black or near white is snapped to it and a texel near the ring color to that, so a lossy encode reads
   * the way the fixture is painted. Anything else stays as read.
   */
  fun cellIn(
    texels: (x: Int, y: Int) -> Int,
    rect: NormalizedRect,
    frame: Size,
  ): IntArray {
    val left = evenPixel(rect.left * frame.width)
    val top = evenPixel(rect.top * frame.height)
    val stepX = rect.width * frame.width / SpriteSheets.CELL.width
    val stepY = rect.height * frame.height / SpriteSheets.CELL.height
    return IntArray(SpriteSheets.CELL.width * SpriteSheets.CELL.height) { index ->
      val column = index % SpriteSheets.CELL.width
      val row = index / SpriteSheets.CELL.width
      val x = (left + (column + 0.5f) * stepX).toInt().coerceIn(0, frame.width - 1)
      val y = (top + (row + 0.5f) * stepY).toInt().coerceIn(0, frame.height - 1)
      snapped(texels(x, y))
    }
  }

  /**
   * The pixels of the drawn rectangle [rect] of an RGB24 [frame] of [size] as packed ARGB.
   *
   * The rectangle is read from its first even pixel, the way ffmpeg places an overlay on a subsampled frame.
   */
  fun regionIn(
    frame: ByteArray,
    size: Size,
    rect: NormalizedRect,
  ): IntArray {
    val texels = packed(frame, size.width, RGB)
    val left = evenPixel(rect.left * size.width)
    val top = evenPixel(rect.top * size.height)
    val width = (rect.right * size.width).roundToInt() - left
    return IntArray(width * ((rect.bottom * size.height).roundToInt() - top)) {
      texels(left + it % width, top + it / width)
    }
  }

  /**
   * Whether the pixel one in from the frame's first corner is the ring color, which is how a sheet drawn there shows.
   */
  fun showsRing(texels: (x: Int, y: Int) -> Int): Boolean = snapped(texels(1, 1)) == SpriteSheets.RING

  // ffmpeg's overlay on a subsampled frame puts an odd offset on the even pixel below it.
  private fun evenPixel(at: Float): Int = at.roundToInt() / 2 * 2

  private fun snapped(argb: Int): Int {
    val channels = intArrayOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    return when {
      channels.all { it < DARK } -> BLACK
      channels.all { it > LIGHT } -> WHITE
      distance(argb, SpriteSheets.RING) < RING_NEAR -> SpriteSheets.RING
      else -> argb or OPAQUE
    }
  }

  private fun distance(
    a: Int,
    b: Int,
  ): Int =
    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
      abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
      abs((a and 0xFF) - (b and 0xFF))

  /**
   * An RGB24 or RGBA texel of [frame], read as packed ARGB.
   */
  fun packed(
    frame: ByteArray,
    width: Int,
    channels: Int,
  ): (Int, Int) -> Int =
    { x, y ->
      val at = (y * width + x) * channels
      OPAQUE or ((frame[at].toInt() and 0xFF) shl 16) or ((frame[at + 1].toInt() and 0xFF) shl 8) or
        (frame[at + 2].toInt() and 0xFF)
    }

  /**
   * Calls [onFrame] with each frame of [path] in presentation order as RGB24 bytes, and answers how many there were.
   *
   * Decoded as written with nothing dropped or repeated, and one frame held at a time.
   */
  fun forEachFrame(
    path: String,
    frame: Size,
    onFrame: (index: Int, rgb: ByteArray) -> Unit,
  ): Int = readFrames(path, frame.width * frame.height * RGB, listOf("-pix_fmt", "rgb24"), onFrame)

  /**
   * Calls [onFrame] with the luma plane of each frame of [path] as the codes the file stores, one little endian sample
   * of [bytesPerSample] bytes a pixel, and answers how many frames there were.
   */
  fun forEachLuma(
    path: String,
    frame: Size,
    bytesPerSample: Int,
    onFrame: (index: Int, luma: ByteArray) -> Unit,
  ): Int =
    readFrames(
      path,
      frame.width * frame.height * bytesPerSample,
      listOf("-vf", "extractplanes=y", "-pix_fmt", if (bytesPerSample == 1) "gray" else "gray10le"),
      onFrame,
    )

  private fun readFrames(
    path: String,
    bytes: Int,
    format: List<String>,
    onFrame: (Int, ByteArray) -> Unit,
  ): Int {
    val process =
      ProcessBuilder(
        listOf(hostFfmpeg, "-hide_banner", "-loglevel", "error", "-i", path, "-fps_mode", "passthrough") +
          format +
          listOf("-f", "rawvideo", "-"),
      ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    var count = 0
    process.inputStream.use { input ->
      while (true) {
        val frame = input.readNBytes(bytes)
        if (frame.size < bytes) break
        onFrame(count++, frame)
      }
    }
    process.waitFor()
    return count
  }

  /**
   * A flat clip long enough to export [length] of at [rate], with nothing on it the sheet could be mistaken for.
   */
  fun backdrop(
    rate: Int,
    length: Duration,
  ): File = indexedClip(rate, length, blue = BACKDROP_BLUE)

  const val SCALE = 0.35f
  const val RGB = 3
  const val RGBA = 4
  const val OPAQUE = 0xFF000000.toInt()
  private const val BACKDROP_BLUE = 60
  private const val DARK = 64
  private const val LIGHT = 192
  private const val RING_NEAR = 120
  private const val BLACK = 0xFF000000.toInt()
  private const val WHITE = 0xFFFFFFFF.toInt()
}
