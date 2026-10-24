package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.PictureAlpha
import dev.jordond.filmstrip.ffmpeg.internal.YuvPlanes
import dev.jordond.filmstrip.ffmpeg.internal.decodedPlanes
import dev.jordond.filmstrip.ffmpeg.internal.yuv4mpegPlanes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.YcbcrEvaluator
import dev.jordond.filmstrip.media.evaluator
import dev.jordond.filmstrip.media.ycbcrReading
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `yuv4mpegpipe` reader and the plane evaluation the ffmpeg picture decoder lands a picture through, run on y4m
 * streams built here, so no ffmpeg runs.
 *
 * Every expected texel is core's evaluator for BT.709 at the stream's depth, packed the way each sample type stores
 * it. The samples are distinct mid codes, so a plane, texel or byte read out of order lands elsewhere.
 */
class PicturePlanesTest {
  @Test
  fun `an odd sized eight-bit frame is read texel by texel in rows`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val planes = assertNotNull(yuv4mpegPlanes(y4m(size, EIGHT, "444", planeSamples(size, EIGHT)), EIGHT))
    assertEquals(ODD_WIDTH, planes.size.width)
    assertEquals(ODD_HEIGHT, planes.size.height)
    assertEquals(EIGHT, planes.bitDepth)
    assertLanded(planes, planeSamples(size, EIGHT))
  }

  @Test
  fun `ten and twelve bit frames are read as little-endian samples`() {
    for (bitDepth in listOf(TEN, TWELVE)) {
      val size = Size(ODD_WIDTH, ODD_HEIGHT)
      val samples = planeSamples(size, bitDepth)
      val stream = y4m(size, bitDepth, "444p$bitDepth", samples)
      val planes = assertNotNull(yuv4mpegPlanes(stream, bitDepth), "$bitDepth bits")
      assertEquals(bitDepth, planes.bitDepth)
      assertLanded(planes, samples)
    }
  }

  @Test
  fun `a frame of another layout or depth is not read`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    assertNull(yuv4mpegPlanes(y4m(size, EIGHT, "420jpeg", planeSamples(size, EIGHT)), EIGHT))
    assertNull(yuv4mpegPlanes(y4m(size, TEN, "444p10", planeSamples(size, TEN)), TWELVE))
    assertNull(yuv4mpegPlanes(y4m(size, EIGHT, "444", planeSamples(size, EIGHT)), TEN))
    assertNull(yuv4mpegPlanes(y4m(size, TEN, "444p10", planeSamples(size, TEN)), EIGHT))
  }

  @Test
  fun `a frame that ends early is not read`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val whole = y4m(size, TEN, "444p10", planeSamples(size, TEN)).readAllBytes()
    assertNull(yuv4mpegPlanes(ByteArrayInputStream(whole.copyOf(whole.size - 1)), TEN))
  }

  @Test
  fun `a header up to 255 characters is read and a longer one is not`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val samples = planeSamples(size, EIGHT)
    val header = "YUV4MPEG2 W$ODD_WIDTH H$ODD_HEIGHT C444 X"
    val longest = header + "x".repeat(MAX_HEADER - 1 - header.length)
    assertNotNull(yuv4mpegPlanes(y4m(longest, size, EIGHT, samples), EIGHT))
    assertNull(yuv4mpegPlanes(y4m(longest + "x", size, EIGHT, samples), EIGHT))
    assertNull(yuv4mpegPlanes(y4m(header + "x".repeat(OVERLONG), size, EIGHT, samples), EIGHT))
  }

  @Test
  fun `tokens the reader does not use are passed over wherever they sit`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val samples = planeSamples(size, TWELVE)
    val header = "YUV4MPEG2 F25:1 W$ODD_WIDTH Ip A1:1 H$ODD_HEIGHT C444p12 XYSCSS=444P12 XCOLORRANGE=FULL"
    val planes = assertNotNull(yuv4mpegPlanes(y4m(header, size, TWELVE, samples, frame = "FRAME Ip"), TWELVE))
    assertLanded(planes, samples)
  }

  @Test
  fun `whatever follows the frame is read to the end`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val frame = y4m(size, EIGHT, "444", planeSamples(size, EIGHT)).readAllBytes()
    val stream = ByteArrayInputStream(frame + "FRAME\n".encodeToByteArray() + ByteArray(TRAILING))
    assertNotNull(yuv4mpegPlanes(stream, EIGHT))
    assertEquals(-1, stream.read())
  }

  @Test
  fun `eight-bit planes pack as opaque ARGB and deeper ones as opaque sixteen-bit RGBA`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val narrow = decodedPlanes(planesOf(size, EIGHT), evaluator(EIGHT)).samples
    assertIs<StoredSamples.Argb>(narrow)
    val wide = decodedPlanes(planesOf(size, TEN), evaluator(TEN)).samples
    val rgba = assertIs<StoredSamples.Rgba16>(wide).rgba
    assertEquals(size.width * size.height * CHANNELS, rgba.size)
  }

  @Test
  fun `an interrupted thread stops the evaluation and has its interrupt cleared`() {
    val planes = planesOf(Size(ODD_WIDTH, ODD_HEIGHT), TWELVE)
    Thread.currentThread().interrupt()
    try {
      assertFailsWith<InterruptedException> { decodedPlanes(planes, evaluator(TWELVE)) }
      assertFalse(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `an interrupt from another thread stops the evaluation part way through the picture`() {
    val size = Size(LARGE_WIDTH, LARGE_HEIGHT)
    val planes = YuvPlanes(size, EIGHT, List(3) { ByteArray(size.width * size.height) { MID.toByte() } })
    val started = CountDownLatch(1)
    val stopped = AtomicReference<Throwable?>()
    val worker =
      Thread {
        started.countDown()
        stopped.set(runCatching { decodedPlanes(planes, evaluator(EIGHT)) }.exceptionOrNull())
      }
    worker.start()
    started.await()
    Thread.sleep(INTERRUPT_AFTER_MILLIS)
    worker.interrupt()
    worker.join()

    val interrupted = assertIs<InterruptedException>(stopped.get(), "the evaluation ran to its end")
    val row = assertNotNull(ROW.find(interrupted.message.orEmpty())).groupValues[1].toInt()
    assertTrue(row in 1 until size.height, "stopped at row $row of ${size.height}")
  }

  @Test
  fun `each texel takes its alpha from the plane and a premultiplied colour comes back straight`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val planes = planesOf(size, TEN)
    val codes = IntArray(size.width * size.height) { ALPHA_FIRST + it * ALPHA_STEP }
    val evaluator = evaluator(TEN)
    for (premultiplied in listOf(false, true)) {
      val decoded = decodedPlanes(planes, evaluator, PictureAlpha(size, codes, premultiplied))
      val rgba = assertIs<StoredSamples.Rgba16>(decoded.samples).rgba
      val (y, cb, cr) = planeSamples(size, TEN)
      for (at in codes.indices) {
        val alpha = codes[at] / WIDE.toDouble()
        val rgb = evaluator.rgb(y[at], cb[at], cr[at]).map { if (premultiplied) minOf(1.0, it / alpha) else it }
        val expected = rgb.map { (it * WIDE).roundToInt() } + codes[at]
        val read = (0 until CHANNELS).map { rgba[at * CHANNELS + it].toInt() and WIDE }
        assertEquals(expected, read, "texel $at, premultiplied $premultiplied")
      }
    }
  }

  // [planes] evaluated and checked texel by texel against core's evaluator of [samples], the three planes as codes.
  private fun assertLanded(
    planes: YuvPlanes,
    samples: List<IntArray>,
  ) {
    val evaluator = evaluator(planes.bitDepth)
    val decoded = decodedPlanes(planes, evaluator)
    assertNull(decoded.colour)
    val (y, cb, cr) = samples
    for (at in y.indices) {
      val rgb = evaluator.rgb(y[at], cb[at], cr[at])
      when (val stored = decoded.samples) {
        is StoredSamples.Argb -> {
          val codes = rgb.map { (it * BYTE).roundToInt() }
          val packed = (BYTE shl ALPHA_SHIFT) or (codes[0] shl RED_SHIFT) or (codes[1] shl GREEN_SHIFT) or codes[2]
          assertEquals(packed, stored.argb[at], "texel $at")
        }
        is StoredSamples.Rgba16 -> {
          val expected = rgb.map { (it * WIDE).roundToInt() } + WIDE
          val read = (0 until CHANNELS).map { stored.rgba[at * CHANNELS + it].toInt() and WIDE }
          assertEquals(expected, read, "texel $at")
        }
      }
    }
  }

  private fun evaluator(bitDepth: Int): YcbcrEvaluator =
    ycbcrReading(BT709, BT709, SRGB_TRANSFER).evaluator(bitDepth, fullRange = false)

  private fun planesOf(
    size: Size,
    bitDepth: Int,
  ): YuvPlanes = YuvPlanes(size, bitDepth, planeSamples(size, bitDepth).map { it.bytes(bitDepth) })

  private fun y4m(
    size: Size,
    bitDepth: Int,
    layout: String,
    samples: List<IntArray>,
  ): ByteArrayInputStream = y4m("YUV4MPEG2 W${size.width} H${size.height} C$layout", size, bitDepth, samples)

  private fun y4m(
    header: String,
    size: Size,
    bitDepth: Int,
    samples: List<IntArray>,
    frame: String = "FRAME",
  ): ByteArrayInputStream {
    require(samples.all { it.size == size.width * size.height })
    val body = samples.map { it.bytes(bitDepth) }.reduce(ByteArray::plus)
    return ByteArrayInputStream("$header\n$frame\n".encodeToByteArray() + body)
  }

  // Three planes of distinct codes inside the limited range at [bitDepth], luma rising and chroma crossing it.
  private fun planeSamples(
    size: Size,
    bitDepth: Int,
  ): List<IntArray> {
    val scale = 1 shl (bitDepth - EIGHT)
    val texels = size.width * size.height
    return listOf(
      IntArray(texels) { (LUMA_FIRST + it * STEP) * scale + it % scale },
      IntArray(texels) { (CHROMA_FIRST + it * STEP) * scale + (scale - 1 - it % scale) },
      IntArray(texels) { (CHROMA_LAST - it * STEP) * scale + it % scale },
    )
  }

  // [this] as one byte a code at eight bits and two little-endian bytes a code past that.
  private fun IntArray.bytes(bitDepth: Int): ByteArray =
    if (bitDepth == EIGHT) {
      ByteArray(size) { this[it].toByte() }
    } else {
      ByteArray(size * 2) { if (it % 2 == 0) this[it / 2].toByte() else (this[it / 2] shr GREEN_SHIFT).toByte() }
    }

  private companion object {
    const val EIGHT = 8
    const val TEN = 10
    const val TWELVE = 12
    const val ODD_WIDTH = 3
    const val ODD_HEIGHT = 5
    const val MAX_HEADER = 256
    const val OVERLONG = 300
    const val TRAILING = 64
    const val LUMA_FIRST = 40
    const val CHROMA_FIRST = 60
    const val CHROMA_LAST = 200
    const val STEP = 9
    const val CHANNELS = 4
    const val BYTE = 0xFF
    const val WIDE = 0xFFFF
    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val BT709 = 1
    const val SRGB_TRANSFER = 13
    const val LARGE_WIDTH = 2048
    const val LARGE_HEIGHT = 4096
    const val MID = 128
    const val ALPHA_FIRST = 20_000
    const val ALPHA_STEP = 1_500
    const val INTERRUPT_AFTER_MILLIS = 50L
    val ROW = Regex("at row (\\d+)")
  }
}
