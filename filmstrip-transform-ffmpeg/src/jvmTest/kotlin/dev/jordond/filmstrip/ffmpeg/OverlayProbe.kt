package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.OverlayConstants.CHANNEL_DRIFT
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.containScale
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertTrue

// The colours, tolerances and geometry the export and preview tests both read overlays against. Each suite keeps its
// own pixel reader, since one reads a written file and the other reads raw preview frames.
internal object OverlayConstants {
  // A flat overlay and a fill in colours no bar of the testsrc fixture carries, far from each other on every channel
  // and clear of both ends of the range, so a blend of the two is well conditioned, nothing clips, and a probe that
  // lands on the wrong one cannot read as the right one.
  const val OVERLAY_COLOUR = 0xFFE67814.toInt()
  const val BAR_FILL = 0xFF2050A0.toInt()

  // The side of every flat overlay raster these tests draw.
  val OVERLAY_SIZE = Size(64, 64)

  // Read over a block rather than a pixel: the fixtures carry seeded noise, and a re-encode moves one pixel by more
  // than a step of the ramps these tests measure.
  const val PROBE_SIDE = 8

  // How far off half the encoder and a 4:2:0 round trip are allowed to leave the halved signal. Halving linear light
  // instead would read about three quarters of the signal, well outside this, which is what makes the band worth
  // asserting rather than a plain "darker".
  const val SIGNAL_DRIFT = 0.05f

  // What a full eight-bit channel reads as, which is what the shared matrix's zero-to-one answer is scaled by.
  const val FULL_SCALE = 255f

  // SIGNAL_DRIFT's width in code values. A matrix answers per channel rather than as one signal, so each channel is
  // given the same band the halved signal gets. Every pixel read back has also been through a YUV round trip, which
  // moves a flat colour by a few code values.
  const val CHANNEL_DRIFT = (SIGNAL_DRIFT * FULL_SCALE).toInt()
}

// Where the shared contain scale puts a source of [source]'s size, centred on [frame], as a fraction of it.
internal fun containedRect(
  source: Size,
  frame: Size,
): NormalizedRect {
  val scale = containScale(source, frame)
  val width = source.width * scale / frame.width
  val height = source.height * scale / frame.height
  val left = (1f - width) / 2
  val top = (1f - height) / 2
  return NormalizedRect(left = left, top = top, right = left + width, bottom = top + height)
}

internal fun channelsOf(argb: Int): Triple<Int, Int, Int> =
  Triple(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)

// Whether every channel of this reading sits within [drift] of [expected].
internal fun Triple<Int, Int, Int>.isNear(
  expected: Triple<Int, Int, Int>,
  drift: Int = CHANNEL_DRIFT,
): Boolean = toList().zip(expected.toList()).all { (got, want) -> abs(got - want) <= drift }

internal fun assertNear(
  read: Triple<Int, Int, Int>,
  expected: Triple<Int, Int, Int>,
  what: String,
) {
  assertTrue(read.isNear(expected), "$what read $read, expected $expected")
}

// A PNG of [size] filled flat with [argb]. An overlay is sized off the picture's own header through ImageIO, so the
// bytes have to be a format this process decodes.
internal fun pngOf(
  argb: Int,
  size: Size,
): ByteArray {
  val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
  val graphics = image.createGraphics()
  graphics.color = Color(argb, true)
  graphics.fillRect(0, 0, size.width, size.height)
  graphics.dispose()
  return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
