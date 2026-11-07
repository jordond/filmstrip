package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.ffmpeg.internal.lowering.PREVIEW_PIXEL_FORMAT
import dev.jordond.filmstrip.ffmpeg.internal.lowering.writtenPixelFormat
import dev.jordond.filmstrip.ffmpeg.support.BYTE
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.RGBA
import dev.jordond.filmstrip.ffmpeg.support.frameTime
import dev.jordond.filmstrip.ffmpeg.support.rawFrames
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.compareFrames
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A preview frame read against the frame its export wrote, on a picture that moves and fills the chroma range.
 *
 * Each export runs at a bitrate that leaves only encoder loss between the two, and every speed reads against the same
 * floors as speed 1. The peak is read as well as the PSNR, since a caption or a picture covers too little of a frame to
 * pull the PSNR of a 10-bit encode down.
 */
class FfmpegPreviewFormatTest {
  private val frames = FilterFrames()

  private val texture by lazy { texturedSource() }
  private val clip by lazy { Clip(texture, TWO_SECONDS) }
  private val caption = TextOverlay(text = "Speed", anchor = Anchor.Center)
  private val picture by lazy { ImageOverlay(ImageSource.ofBytes(flatPng(OPAQUE)), Corner.TopStart) }
  private val halfPicture by lazy { ImageOverlay(ImageSource.ofBytes(flatPng(HALF_ALPHA)), Corner.TopStart) }

  @Test
  fun `a bare clip shows the frame its export writes`() =
    runTest(timeout = TIMEOUT) {
      assertShownAsWritten(frames.composition(listOf(clip)))
    }

  @Test
  fun `a graded clip shows the frame its export writes`() =
    runTest(timeout = TIMEOUT) {
      assertShownAsWritten(frames.composition(listOf(Clip(texture, TWO_SECONDS, effects = listOf(GRADE)))))
    }

  @Test
  fun `a composition grade shows the frame its export writes`() =
    runTest(timeout = TIMEOUT) {
      assertShownAsWritten(frames.composition(listOf(clip), listOf(GRADE)))
    }

  @Test
  fun `a caption shows the frame its export writes`() =
    runTest(timeout = TIMEOUT) {
      assertShownAsWritten(frames.composition(listOf(clip), listOf(caption)))
      assertShownAsWritten(frames.composition(listOf(clip), listOf(GRADE, caption)))
    }

  @Test
  fun `a picture on an odd offset shows the frame its export writes`() =
    runTest(timeout = TIMEOUT) {
      val edit = frames.composition(listOf(clip), listOf(picture))

      frames.graphOf(edit) shouldContain "overlay=x=7:y=7"
      assertShownAsWritten(edit)
    }

  @Test
  fun `a half alpha picture shows the frame its export writes`() =
    runTest(timeout = TIMEOUT) {
      assertShownAsWritten(frames.composition(listOf(clip), listOf(halfPicture)))
    }

  // The overlay stays in the graph either side of its window, and its RGB round trip reaches those frames in both.
  @Test
  fun `a shut overlay shows the frame its export writes either side of its window`() =
    runTest(timeout = TIMEOUT) {
      val shut =
        TextOverlay(
          text = "Speed",
          anchor = Anchor.Center,
          visibleDuring = TimeRange.of(1.seconds, 1500.milliseconds),
        )

      assertShownAsWritten(frames.composition(listOf(clip), listOf(shut)), slots = listOf(0, 7, 35, 50))
    }

  @Test
  fun `a clip at speed shows the frame its export writes against the floors of speed 1`() =
    runTest(timeout = TIMEOUT) {
      for ((speed, trim) in SPEEDS) {
        val sped = Clip(texture, trim, speed = speed)
        withClue("at $speed") {
          assertShownAsWritten(frames.composition(listOf(sped)), SPED_SLOTS)
          assertShownAsWritten(frames.composition(listOf(sped), listOf(GRADE)), SPED_SLOTS)
          assertShownAsWritten(frames.composition(listOf(sped), listOf(caption)), SPED_SLOTS)
          assertShownAsWritten(frames.composition(listOf(sped), listOf(halfPicture)), SPED_SLOTS)
        }
      }
    }

  @Test
  fun `an HDR grade shows the frame its export writes`() =
    runTest(timeout = TIMEOUT) {
      val fixture = FfmpegExportFixture()
      for (file in listOf(fixture.hdr, fixture.hdrHlg)) {
        val hdr = Clip(MediaSource.of(file.absolutePath))
        for (effects in listOf(emptyList(), listOf(caption), listOf<EffectSpec>(halfPicture))) {
          assertShownAsWritten(
            frames.composition(listOf(hdr), effects),
            HDR_SLOTS,
            ExportSpec(hdr = HdrMode.KeepHdr, bitrate = HIGH_BITRATE),
            HDR_FLOOR,
          )
        }
      }
    }

  @Test
  fun `an HDR grade at speed shows the frame its export writes against the floors of speed 1`() =
    runTest(timeout = TIMEOUT) {
      val fixture = FfmpegExportFixture()
      for (file in listOf(fixture.hdr, fixture.hdrHlg)) {
        val media = MediaSource.of(file.absolutePath)
        for ((speed, trim) in HDR_SPEEDS) {
          val sped = Clip(media, trim, speed = speed)
          val length = outputLengthOf(checkNotNull(trim.duration), speed)
          val slots = HDR_SPED_SLOTS.filter { outputSlotTime(it.toLong(), FILTER_RATE) < length }
          for (effects in listOf(emptyList(), listOf<EffectSpec>(caption))) {
            withClue("$file at $speed") {
              assertShownAsWritten(
                frames.composition(listOf(sped), effects),
                slots,
                ExportSpec(hdr = HdrMode.KeepHdr, bitrate = HIGH_BITRATE),
                HDR_FLOOR,
              )
            }
          }
        }
      }
    }

  @Test
  fun `the preview's frame is the export's with no encoder between them`() =
    runTest(timeout = TIMEOUT) {
      for (effects in listOf(emptyList(), listOf(GRADE), listOf(caption), listOf<EffectSpec>(GRADE, caption))) {
        val edit = frames.composition(listOf(clip), effects)
        val bytes = frames.rawFrames(edit, COUNT) { asWritten(it) }

        bytes.contentEquals(frames.rawFrames(edit, COUNT) { asWritten(unpinned(it)) }) shouldBe true
      }
    }

  @Test
  fun `an export's graph leaves its end format to the encoder`() =
    runTest(timeout = TIMEOUT) {
      val graph = frames.graphOf(frames.composition(listOf(clip), listOf(caption)))

      graph shouldContain "format=auto"
      graph shouldContain "setpts=expr=PTS-STARTPTS[vout]"
    }

  private suspend fun assertShownAsWritten(
    edit: EditComposition,
    slots: List<Int> = SLOTS,
    spec: ExportSpec = ExportSpec(bitrate = HIGH_BITRATE),
    floor: EncoderLoss = SDR_FLOOR,
  ) {
    val size = frames.invocationOf(edit).output.size
    for (slot in slots) {
      val shown = frames.frames(edit, frameTime(slot, FILTER_RATE), 1, spec).single()
      val written = frames.exportedFrame(edit, slot, spec)

      withClue("slot $slot") {
        compareFrames(written.framed(size), shown.framed(size)).psnrDb shouldBeGreaterThanOrEqual floor.minPsnrDb
        peakDifference(written, shown) shouldBeLessThanOrEqual floor.maxPeak
      }
    }
  }

  private fun ByteArray.framed(size: Size) = TestFrame(this, size)

  private fun peakDifference(
    a: ByteArray,
    b: ByteArray,
  ): Int =
    a.indices
      .filter { it % RGBA != RGBA - 1 }
      .maxOf { abs((a[it].toInt() and BYTE) - (b[it].toInt() and BYTE)) }

  private fun texturedSource(): MediaSource {
    val file = File.createTempFile("preview-format", ".mkv").apply { deleteOnExit() }
    val encode =
      ProcessBuilder(
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "testsrc2=s=${WIDTH}x$HEIGHT:r=$FILTER_RATE:d=$SOURCE_SECONDS",
        "-vf",
        "scale=out_color_matrix=bt709:out_range=tv",
        "-colorspace",
        "bt709",
        "-color_range",
        "tv",
        "-color_primaries",
        "bt709",
        "-color_trc",
        "bt709",
        "-c:v",
        "ffv1",
        "-pix_fmt",
        "yuv420p",
        file.path,
      ).inheritIO().start()
    encode.waitFor() shouldBe 0
    return MediaSource.of(file.path)
  }

  private fun flatPng(argb: Int): ByteArray {
    val image = BufferedImage(PICTURE_SIDE, PICTURE_SIDE, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until PICTURE_SIDE) for (x in 0 until PICTURE_SIDE) image.setRGB(x, y, argb)
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  private fun unpinned(command: List<String>): List<String> =
    command.map {
      if (it == "[$SHOWN]") "[$GRAPH_OUT]" else it.replace(";[$GRAPH_OUT]format=pix_fmts=$WRITTEN[$SHOWN]", "")
    }

  // The sink an export has for its encoder, in place of the pump's RGBA.
  private fun asWritten(command: List<String>): List<String> =
    command.map { if (it == PREVIEW_PIXEL_FORMAT) WRITTEN else it }

  /**
   * How far encoder loss alone parts a preview from its export.
   */
  private class EncoderLoss(
    val minPsnrDb: Double,
    val maxPeak: Int,
  )

  private companion object {
    val TIMEOUT = 5.minutes
    val HIGH_BITRATE = Bitrate.mbps(200)
    val TWO_SECONDS = TimeRange.of(Duration.ZERO, 2.seconds)
    val GRADE = Brightness(0.2f)
    val SLOTS = listOf(0, 7, 20)
    val SPED_SLOTS = listOf(0, 3, 7)
    val HDR_SLOTS = listOf(0, 20)
    val HDR_SPED_SLOTS = listOf(1, 3)
    val HDR_SPEEDS =
      listOf(
        0.7 to TimeRange.of(Duration.ZERO, 500.milliseconds),
        1.5 to TimeRange.of(Duration.ZERO, 500.milliseconds),
        (1.0 / 32) to TimeRange.of(Duration.ZERO, 100.milliseconds),
        32.0 to TimeRange.of(Duration.ZERO, 2.seconds),
      )
    val SPEEDS =
      listOf(
        0.7 to TWO_SECONDS,
        1.5 to TWO_SECONDS,
        (1.0 / 32) to TimeRange.of(Duration.ZERO, 200.milliseconds),
        32.0 to TimeRange.of(Duration.ZERO, 8.seconds),
      )

    // 200 Mbps at this size sits at the top of the encoder's range on 8-bit, reading 61.2 dB and peaking at 5 on both
    // ffmpeg builds. HEVC at 720p stays further off on 10-bit, reading 52.0 dB and peaking at 8 on 9.0.2, and 53.5 dB
    // and peaking at 5 on 8.0.1. Without the end format a composition grade reads 49.8 dB and a caption peaks at 161.
    val SDR_FLOOR = EncoderLoss(minPsnrDb = 60.0, maxPeak = 6)
    val HDR_FLOOR = EncoderLoss(minPsnrDb = 51.0, maxPeak = 8)
    const val WIDTH = 320
    const val HEIGHT = 180
    const val SOURCE_SECONDS = 8
    const val COUNT = 3
    const val PICTURE_SIDE = 64
    const val OPAQUE = 0xFFE67814.toInt()
    const val HALF_ALPHA = 0x80E67814.toInt()
    val WRITTEN = writtenPixelFormat(null)
    const val GRAPH_OUT = "vout"
    const val SHOWN = "vshown"
  }
}
