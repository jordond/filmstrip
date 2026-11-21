package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.FilmTone
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A [Film]'s fade and breath drawn through the real browser compositor, every pixel read against
 * [FilmSignal.pixelAt].
 *
 * The picture a read is held against is the same clip drawn with no [Film]. A breath test first checks that
 * [FilmTone.breathAt] moves the frame at the tick it reads, so a seed that draws next to nothing there fails the test
 * rather than passing it on nothing.
 */
class BrowserFilmToneMediaTest {
  @Test
  fun aMidFadeLandsOnTheSignal() =
    runTest {
      val film = Film(0f, 0f, fade = MID)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      withClue("the fade moves the frame") { movedCodes(picture, film, TICK) shouldBeGreaterThan MOVED_CODES }
      worstCodes(frame, picture, film, TICK) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aBreathBetweenTwoKnotsLandsOnTheSignal() =
    runTest {
      val film = Film(0f, 0f, breath = BREATH, seed = OWN_SEED)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      withClue("the tick read sits between two knots") { (TICK % FilmTone.KNOT_TICKS) shouldNotBe 0 }
      withClue("the breath moves the frame") { movedCodes(picture, film, TICK) shouldBeGreaterThan MOVED_CODES }
      worstCodes(frame, picture, film, TICK) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aBreathOnAKnotLandsOnTheSignal() =
    runTest {
      val film = Film(0f, 0f, breath = BREATH, seed = OWN_SEED)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip), listOf(film)), at = KNOT_TIME)
      val picture = drawn(edit(Clip(clip)), at = KNOT_TIME)

      withClue("the tick read sits on a knot") { (KNOT_TICK % FilmTone.KNOT_TICKS) shouldBe 0 }
      withClue("the breath moves the frame") { movedCodes(picture, film, KNOT_TICK) shouldBeGreaterThan MOVED_CODES }
      worstCodes(frame, picture, film, KNOT_TICK) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipToneUnderEveryOtherControlLandsOnTheSignal() =
    runTest {
      val film = Film(MID, MID, flicker = MID, gate = MID, leak = MID, breath = BREATH, fade = MID, seed = OWN_SEED)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(film))))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, film, TICK) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSmallerRenderDrawsTheSameToneAtItsOwnSize() =
    runTest {
      val film = Film(0f, 0f, breath = BREATH, fade = MID, seed = OWN_SEED)
      val clip = quarteredClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(film)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      worstCodes(frame, picture, film, TICK) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun theSameTimeDrawsTheSameBreathAfterASeek() =
    runTest {
      val film = Film(0f, 0f, breath = 1f, seed = OWN_SEED)
      val preview = previewOf(edit(Clip(quarteredClip()), listOf(film)), SPEC)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val later = preview.frameAt(KNOT_TIME).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        withClue("the breath drifts between the two times") {
          later.pixels.contentEquals(first.pixels) shouldBe false
        }
        withClue("the same time draws the same picture") { again.pixels.contentEquals(first.pixels) shouldBe true }
      } finally {
        preview.release()
      }
    }

  @Test
  fun aCompositionFadeReachesTheBars() =
    runTest {
      val film = Film(0f, 0f, fade = 1f)
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val fill = Fill.Solid(SLATE_ARGB)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip), listOf(square, film), fill))
      val picture = drawn(edit(Clip(clip), listOf(square), fill))

      withClue("the place read has to sit on a bar for this to tell") {
        picture.pixelAt(WIDTH / 2, BAR_ROW).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
      withClue("the fade lifts the bar's red") {
        (frame.pixelAt(WIDTH / 2, BAR_ROW).red - SLATE.red).toDouble() shouldBeGreaterThan MOVED_CODES
      }
      worstCodes(frame, picture, film, TICK) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipFadeLeavesACanvasBarClearOverTheFill() =
    runTest {
      val film = Film(0f, 0f, fade = 1f)
      val square = Crop(AspectRatio.Square, Fit.Contain)

      val frame = drawn(edit(Clip(quarteredClip(), effects = listOf(square, film)), fill = Fill.Solid(SLATE_ARGB)))

      frame.pixelAt(WIDTH / 2, BAR_ROW).isNear(SLATE, CLEAR_CODES) shouldBe true
    }

  private suspend fun drawn(
    composition: Composition,
    spec: ExportSpec = SPEC,
    at: Duration = READ_TIME,
  ): PreviewFrame {
    val preview = previewOf(composition, spec)
    try {
      return preview.frameAt(at).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  private suspend fun previewOf(
    composition: Composition,
    spec: ExportSpec,
  ): BrowserPreview =
    webCodecsExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addEffectResolver(FilterEffectResolver())
          .build(),
      prober = BrowserProber(),
    ).resolve(composition, spec)
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
      .toBrowserPreview(composition)

  private fun edit(
    clip: Clip,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(BLACK_ARGB),
  ) = Composition(
    tracks = listOf(Track(listOf(clip))),
    effects = effects,
    fill = fill,
    audio = AudioSpec.Remove,
  )

  // Bright and grey quarters, so the tone is read on both a dark code and a bright one and the halation glows.
  private suspend fun quarteredClip(): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, y ->
        if ((x < WIDTH / 2) != (y < HEIGHT / 2)) BRIGHT else GREY
      },
    )

  // The largest gap between the picture and what the signal makes of it, in eight bit codes.
  private fun movedCodes(
    picture: PreviewFrame,
    film: Film,
    tick: Int,
  ): Double = gapCodes(picture, picture, film, tick)

  // The largest gap between the frame and the signal over every pixel, in eight bit codes.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    film: Film,
    tick: Int,
  ): Double = gapCodes(frame, picture, film, tick)

  private fun gapCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    film: Film,
    tick: Int,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val params = FilmParams.of(film)
    val glow =
      if (params.halationGain > 0.0) {
        FilmSignal.halationGlow(width, height) { x, y -> picture.pixelAt(x, y).channels() }
      } else {
        null
      }
    var worst = 0.0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val shown = picture.pixelAt(x, y).channels()
        val expected =
          FilmSignal.pixelAt(
            shown[0],
            shown[1],
            shown[2],
            glow?.at(x, y) ?: DoubleArray(RGB),
            FilmSignal.grainCell(x, y, width, height),
            tick,
            params,
            (x + HALF) / width,
            (y + HALF) / height,
            width.toDouble() / height,
          )
        val read = frame.pixelAt(x, y).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
      }
    }
    return worst
  }

  private fun PreviewFrame.pixelAt(
    x: Int,
    y: Int,
  ): Rgb = at((x + HALF) / size.width, (y + HALF) / size.height)

  private fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)
}

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(96, 96, 96)
private val BRIGHT = Rgb(235, 225, 215)
private val SLATE = Rgb(0x20, 0x40, 0x60)

private const val MID = 0.5f
private const val BREATH = 0.6f
private const val HALF = 0.5
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 320
private const val HEIGHT = 240
private const val SMALL_HEIGHT = 120
private const val BAR_ROW = 2
private const val FRAME_RATE = 24

// Two seconds, so a read between two knots and one on a later knot both land on the clip.
private const val FRAMES = 48
private const val READ_SLOT = 6L
private const val KNOT_SLOT = 3L * FilmTone.KNOT_TICKS
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private val KNOT_TIME: Duration = outputSlotTime(KNOT_SLOT, FRAME_RATE)
private val TICK: Int = FilmSignal.grainTickAt(READ_TIME.toDouble(DurationUnit.SECONDS))
private val KNOT_TICK: Int = FilmSignal.grainTickAt(KNOT_TIME.toDouble(DurationUnit.SECONDS))
private const val OWN_SEED = 68
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SLATE_ARGB = 0xFF204060.toInt()

// A move that stands clear of rounding.
private const val MOVED_CODES = 3.0

// A read and the picture it is held against each round to one eight bit code.
private const val SIGNAL_CODES = 1.5

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1
