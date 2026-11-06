package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration

/**
 * What a [Crt] does at each scope, next to bars and overlays, and over time, in the real browser compositor.
 */
class BrowserCrtScopeTest : BrowserCrtFixture() {
  @Test
  fun aCompositionCrtReachesTheBars() =
    runTest {
      val crt = Crt(MID)
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val fill = Fill.Solid(SLATE_ARGB)
      val clip = flatClip(WIDTH, HEIGHT, GREY)

      val frame = drawn(edit(Clip(clip), listOf(square, crt), fill))
      val picture = drawn(edit(Clip(clip), listOf(square), fill))

      val barColumn = frame.size.width / BAR_READ_OF_WIDTH
      val y = frame.size.height / 2
      val shift = abs(frame.pixelAt(barColumn, y).blue - picture.pixelAt(barColumn, y).blue).toDouble()
      withClue("the look has to change the bar, it moved it $shift") { shift shouldBeGreaterThan EFFECT_CODES }
      worstCodes(frame, picture, crt, rows = listOf(y), columns = listOf(barColumn)) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipCrtLeavesACanvasBarClearOverTheFill() =
    runTest {
      val square = flatClip(SQUARE, SQUARE, BRIGHT)
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)

      val frame =
        drawn(edit(Clip(square, effects = listOf(wide, Crt(FULL_INTENSITY))), fill = Fill.Solid(SLATE_ARGB)))

      val width = frame.size.width
      val height = frame.size.height
      withClue("the bar's edge holds the fill past the curve, not the black a picture ends in") {
        frame.pixelAt(0, height / 2).isNear(SLATE, CLEAR_CODES) shouldBe true
        frame.pixelAt(width - 1, height / 4).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
      withClue("the bar next to the picture holds the fill") {
        frame.pixelAt(width / BAR_READ_OF_WIDTH, height / 2).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
      val k = CrtParams.at(FULL_INTENSITY.toDouble()).barrelK
      val row = (0 until height).first { litAt(width / 2, it, k, width, height) }
      withClue("the picture's own top edge ends in black") {
        (row > 0) shouldBe true
        frame.pixelAt(width / 2, row - 1).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
      }
    }

  @Test
  fun aClipCrtLeavesABlurredFillsCanvasBarsBlack() =
    runTest {
      val square = flatClip(SQUARE, SQUARE, BRIGHT)
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)

      val frame = drawn(edit(Clip(square, effects = listOf(wide, Crt(FULL_INTENSITY))), fill = Fill.Blurred()))

      frame.pixelAt(frame.size.width / BAR_READ_OF_WIDTH, frame.size.height / 2).isNear(Rgb.Black, CLEAR_CODES) shouldBe
        true
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest {
      val clip = flatClip(WIDTH, HEIGHT, GREY)
      val frame = drawn(edit(Clip(clip), listOf(swatch(), Crt(FULL_INTENSITY))))

      frame.at(SWATCH_X, SWATCH_Y).isNear(Rgb(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE), CLEAR_CODES) shouldBe true
    }

  @Test
  fun aClipOverlayUnderACompositionCrtIsBurned() =
    runTest {
      val crt = Crt(FULL_INTENSITY)
      val clip = flatClip(WIDTH, HEIGHT, GREY)
      val pale = swatch(PALE_SWATCH_ARGB)

      val frame = drawn(edit(Clip(clip, effects = listOf(pale)), listOf(crt)))
      val picture = drawn(edit(Clip(clip, effects = listOf(pale))))

      val x = (SWATCH_X * WIDTH).toInt()
      val y = (SWATCH_Y * HEIGHT).toInt()
      worstCodes(frame, picture, crt, rows = listOf(y), columns = listOf(x)) shouldBeLessThan SIGNAL_CODES
      withClue("the swatch has to take the look for this to tell") {
        abs(frame.pixelAt(x, y).red - picture.pixelAt(x, y).red).toDouble() shouldBeGreaterThan EFFECT_CODES
      }
    }

  @Test
  fun theSamePictureDrawsAtAnyTimeAndAfterASeek() =
    runTest {
      val crt = Crt(MID)
      val clip = flatClip(WIDTH, HEIGHT, GREY)
      val preview = previewOf(edit(Clip(clip), listOf(crt)), SPEC)
      val picture = drawn(edit(Clip(clip)))
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val elsewhere = preview.frameAt(outputSlotTime(1, FRAME_RATE)).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        again.pixels.contentEquals(first.pixels) shouldBe true
        worstCodes(elsewhere, picture, crt) shouldBeLessThan SIGNAL_CODES
      } finally {
        preview.release()
      }
    }

  @Test
  fun anExportWritesWhatAPreviewDraws() =
    runTest {
      val crt = Crt(FULL_INTENSITY)
      val clip = flatClip(EXPORT_WIDTH, EXPORT_HEIGHT, BRIGHT)
      val composition = edit(Clip(clip), listOf(crt))
      val filmstrip =
        Filmstrip {
          webCodecsBackend()
          filters()
        }

      val statuses = filmstrip.export(planOf(filmstrip, composition), MediaSink.Uri("")).toList()
      val success = statuses.filterIsInstance<ExportStatus.Success>().single()
      val written = decodeFrames(outputOf(success))[READ_SLOT.toInt()]
      val drawn = drawn(composition)
      val plain = drawn(edit(Clip(clip)))

      val x = (EXPORT_WIDTH / 2 + 0.5) / EXPORT_WIDTH
      val y = (EXPORT_HEIGHT / 2 + 0.5) / EXPORT_HEIGHT
      withClue("the look has to move this pixel past the encoder's reach for this to tell") {
        abs(drawn.at(x, y).red - plain.at(x, y).red).toDouble() shouldBeGreaterThan EXPORT_CODES
      }
      abs(written.at(x, y).red - drawn.at(x, y).red).toDouble() shouldBeLessThan EXPORT_CODES
    }

  @Test
  fun aClipCrtReadsAToneMappedFrame() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest
      val crt = Crt(MID)
      val clip = makeHdrClip(HdrTransfer.Pq, HDR_WIDTH, HDR_HEIGHT, FRAMES, FRAME_RATE) { HDR_NITS }
      val source = MediaSource.Bytes(clip)
      val toneMap = ExportSpec(videoCodec = VideoCodec.H264, hdr = HdrMode.ToneMapToSdr)

      val frame = drawn(edit(Clip(source, effects = listOf(crt))), toneMap)
      val picture = drawn(edit(Clip(source)), toneMap)

      worstCodes(frame, picture, crt) shouldBeLessThan SIGNAL_CODES
    }
}

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(120, 120, 120)
private val BRIGHT = Rgb(190, 185, 180)
private val SLATE = Rgb(0x20, 0x40, 0x60)
private val HDR_NITS = floatArrayOf(120f, 180f, 75f)
private const val MID = 0.5f
private const val FULL_INTENSITY = 1f
private const val WIDTH = 1280
private const val HEIGHT = 720
private const val SQUARE = 360
private const val EXPORT_WIDTH = 320
private const val EXPORT_HEIGHT = 240
private const val HDR_WIDTH = 128
private const val HDR_HEIGHT = 96
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val READ_SLOT = 6L
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private const val SLATE_ARGB = 0xFF204060.toInt()
private const val BAR_READ_OF_WIDTH = 8
private const val SWATCH_CODE = 0x20

// Bright enough that what the patterns and the gain leave on it clears the rounding.
private const val PALE_SWATCH_ARGB = 0xFFE0E0E0.toInt()
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// A read and the picture it is held against each round to one eight bit code. Measured at 0.53.
private const val SIGNAL_CODES = 1.0

// Well past SIGNAL_CODES, so a read landing inside it says the look is there.
private const val EFFECT_CODES = 6.0

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1

// What the encoder does to a flat frame under a soft pattern.
private const val EXPORT_CODES = 6.0
