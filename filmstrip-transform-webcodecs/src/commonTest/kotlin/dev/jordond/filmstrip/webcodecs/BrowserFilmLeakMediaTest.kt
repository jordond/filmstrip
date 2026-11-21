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
import dev.jordond.filmstrip.effects.filters.film.FilmLeak
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
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
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A [Film]'s light leaks drawn through the real browser compositor, every pixel read against [FilmSignal.pixelAt].
 *
 * The picture a read is held against is the same clip drawn with no [Film]. Every place a test reads comes from
 * [FilmLeak.leakAt], and each test first checks that the leak is lit or dark there, so a seed that draws its spots
 * somewhere else fails the test rather than passing it on nothing.
 */
class BrowserFilmLeakMediaTest {
  @Test
  fun aLeakAtAMidStrengthLandsOnTheSignal() =
    runTest {
      val film = Film(0f, 0f, leak = MID)
      val clip = greyClip()

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      val lit = brightest(frame.size, film)
      val dark = unlit(frame.size, film).shouldNotBeNull()
      withClue("the leak lifts the frame where it is brightest") {
        (
          frame
            .pixelAt(
              lit.first,
              lit.second,
            ).red - picture.pixelAt(lit.first, lit.second).red
        ).toDouble() shouldBeGreaterThan
          LIFTED_CODES
      }
      withClue("a pixel past every spot's radius keeps the picture") {
        frame.pixelAt(dark.first, dark.second).isNear(picture.pixelAt(dark.first, dark.second), CLEAR_CODES) shouldBe
          true
      }
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipLeakUnderGrainAndHalationLandsOnTheSignal() =
    runTest {
      val film = Film(MID, MID, leak = MID, seed = OWN_SEED)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(film))))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aFullLeakUnderTheGateLandsOnTheSignal() =
    runTest {
      val film = Film(0f, 0f, flicker = MID, gate = MID, leak = 1f)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSmallerRenderDrawsTheSameLeakAtItsOwnSize() =
    runTest {
      val film = Film(0f, 0f, leak = MID)
      val clip = greyClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(film)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun theSameTimeDrawsTheSameLeakAfterASeek() =
    runTest {
      val film = Film(0f, 0f, leak = 1f)
      val clip = greyClip()
      val preview = previewOf(edit(Clip(clip), listOf(film)), SPEC)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val later = preview.frameAt(LATER_TIME).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        val lit = brightest(first.size, film)
        withClue("the leak closes between the two times") {
          (
            first
              .pixelAt(
                lit.first,
                lit.second,
              ).red - later.pixelAt(lit.first, lit.second).red
          ).toDouble() shouldBeGreaterThan
            LIFTED_CODES
        }
        withClue("the same time draws the same picture") { again.pixels.contentEquals(first.pixels) shouldBe true }
      } finally {
        preview.release()
      }
    }

  @Test
  fun aCompositionLeakReachesTheBars() =
    runTest {
      val film = Film(0f, 0f, leak = 1f)
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val fill = Fill.Solid(SLATE_ARGB)
      val clip = greyClip()

      val frame = drawn(edit(Clip(clip), listOf(square, film), fill))
      val picture = drawn(edit(Clip(clip), listOf(square), fill))

      val bar = brightestOnABar(frame.size, film)
      withClue("the place read has to sit on a bar for this to tell") {
        picture.pixelAt(bar.first, bar.second).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
      withClue("the leak lifts the bar") {
        (frame.pixelAt(bar.first, bar.second).red - SLATE.red).toDouble() shouldBeGreaterThan LIFTED_CODES
      }
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipLeakLeavesACanvasBarClearOverTheFill() =
    runTest {
      val film = Film(0f, 0f, leak = 1f)
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val clip = greyClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(square, film)), fill = Fill.Solid(SLATE_ARGB)))

      val bar = brightestOnABar(frame.size, film)
      withClue("the leak is lit over the bar") {
        lightAt(bar.first, bar.second, frame.size, film) shouldBeGreaterThan
          0.1
      }
      withClue(
        "the bar holds the fill",
      ) { frame.pixelAt(bar.first, bar.second).isNear(SLATE, CLEAR_CODES) shouldBe true }
    }

  @Test
  fun anOverlayAtTheSameScopeStaysCleanUnderTheLeak() =
    runTest {
      val film = Film(0f, 0f, leak = 1f)
      val lit = brightest(Size(WIDTH, HEIGHT), film)
      val corner = if (lit.first < WIDTH / 2) Corner.TopStart else Corner.TopEnd
      val swatch =
        ImageOverlay(
          ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), SWATCH_ARGB)),
          corner,
          scale = SWATCH_SCALE,
        )

      val frame = drawn(edit(Clip(greyClip()), listOf(swatch, film)))

      val x = ((if (corner == Corner.TopStart) SWATCH_X else 1.0 - SWATCH_X) * WIDTH).toInt()
      val y = (SWATCH_Y * HEIGHT).toInt()
      withClue("the leak is lit under the swatch") { lightAt(x, y, frame.size, film) shouldBeGreaterThan 0.1 }
      frame.pixelAt(x, y).isNear(Rgb(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE), CLEAR_CODES) shouldBe true
    }

  private fun lightAt(
    x: Int,
    y: Int,
    size: Size,
    film: Film,
  ): Double =
    FilmLeak
      .leakAt(
        (x + HALF) / size.width,
        (y + HALF) / size.height,
        size.width.toDouble() / size.height,
        TICK,
        film.seed,
        film.leak.toDouble(),
      ).max()

  private fun pixelsOf(size: Size): List<Pair<Int, Int>> =
    (0 until size.height).flatMap { y -> (0 until size.width).map { x -> x to y } }

  private fun brightest(
    size: Size,
    film: Film,
  ): Pair<Int, Int> = pixelsOf(size).maxBy { lightAt(it.first, it.second, size, film) }

  // The brightest place of the leak along the frame's first and last rows, which a square crop of a wide clip leaves
  // in a bar.
  private fun brightestOnABar(
    size: Size,
    film: Film,
  ): Pair<Int, Int> =
    pixelsOf(size)
      .filter { it.second == BAR_ROW || it.second == size.height - 1 - BAR_ROW }
      .maxBy { lightAt(it.first, it.second, size, film) }

  private fun unlit(
    size: Size,
    film: Film,
  ): Pair<Int, Int>? = pixelsOf(size).firstOrNull { lightAt(it.first, it.second, size, film) == 0.0 }

  private suspend fun drawn(
    composition: Composition,
    spec: ExportSpec = SPEC,
  ): PreviewFrame {
    val preview = previewOf(composition, spec)
    try {
      return preview.frameAt(READ_TIME).shouldNotBeNull()
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

  private suspend fun greyClip(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> GREY })

  // Bright and grey quarters, so the screen is read on both a dark code and a bright one and the halation glows.
  private suspend fun quarteredClip(): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, y ->
        if ((x < WIDTH / 2) != (y < HEIGHT / 2)) BRIGHT else GREY
      },
    )

  // The largest gap between the frame and the signal over every pixel, in eight bit codes.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    film: Film,
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
            TICK,
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
private const val HALF = 0.5
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 320
private const val HEIGHT = 240
private const val SMALL_HEIGHT = 120
private const val BAR_ROW = 2
private const val FRAME_RATE = 24

// Two seconds, so a read early in a run and one late in it both land on the clip.
private const val FRAMES = 48
private const val READ_SLOT = 6L
private const val LATER_SLOT = 40L
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private val LATER_TIME: Duration = outputSlotTime(LATER_SLOT, FRAME_RATE)
private val TICK: Int = FilmSignal.grainTickAt(READ_TIME.toDouble(DurationUnit.SECONDS))
private const val OWN_SEED = 68
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SLATE_ARGB = 0xFF204060.toInt()
private const val SWATCH_CODE = 0x20
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// A lift that stands clear of rounding and of what the decoder does to a flat grey.
private const val LIFTED_CODES = 20.0

// A read and the picture it is held against each round to one eight bit code.
private const val SIGNAL_CODES = 1.5

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1
