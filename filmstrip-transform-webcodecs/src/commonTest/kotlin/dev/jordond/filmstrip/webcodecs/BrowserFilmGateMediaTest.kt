package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.HalationGlow
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A [Film]'s weave, flicker, dust, scratches and gate drawn through the real browser compositor, every pixel read
 * against [FilmSignal.pixelAt].
 *
 * The picture a read is held against is the same clip drawn with no [Film], read off the frame moved by
 * [FilmSignal.weaveAt] between its four nearest pixels and held at the edge.
 */
class BrowserFilmGateMediaTest {
  @Test
  fun everyControlAtAMidStrengthLandsOnTheSignal() =
    runTest {
      val film = Film(MID, MID, weave = MID, flicker = MID, dust = MID, gate = MID)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      assertMoves(frame, film)
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipSuper8LandsOnTheSignal() =
    runTest {
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(Film.Super8))))
      val picture = drawn(edit(Clip(clip)))

      assertMoves(frame, Film.Super8)
      withClue("the gate closes over the corner") { frame.pixelAt(0, 0).red shouldBe 0 }
      worstCodes(frame, picture, Film.Super8) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSeedOfItsOwnDrawsItsOwnGrainAndWeave() =
    runTest {
      val seeded = Film(grain = 1f, halation = 0f, weave = 1f, dust = MID, seed = OWN_SEED)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip), listOf(seeded)))
      val unseeded = drawn(edit(Clip(clip), listOf(Film(grain = 1f, halation = 0f, weave = 1f, dust = MID))))
      val picture = drawn(edit(Clip(clip)))

      withClue("a seed of its own draws another frame") {
        frame.pixels.contentEquals(unseeded.pixels) shouldBe false
      }
      withClue("a scratch has to land on the frame for this to tell") {
        (0 until frame.size.width).any { x ->
          FilmSignal.scratchAt(OWN_SEED, TICK, (x + HALF) / frame.size.width, MID.toDouble()) != 0.0
        } shouldBe true
      }
      worstCodes(frame, picture, seeded) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSmallerRenderDrawsSuper8AtItsOwnSize() =
    runTest {
      val clip = quarteredClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(Film.Super8)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      worstCodes(frame, picture, Film.Super8) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun theSameTimeDrawsTheSameSuper8AfterASeek() =
    runTest {
      val preview = previewOf(edit(Clip(quarteredClip()), listOf(Film.Super8)), SPEC)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val elsewhere = preview.frameAt(outputSlotTime(1, FRAME_RATE)).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        withClue("the look moves between the two times") {
          elsewhere.pixels.contentEquals(first.pixels) shouldBe false
        }
        withClue("the same time draws the same picture") { again.pixels.contentEquals(first.pixels) shouldBe true }
      } finally {
        preview.release()
      }
    }

  @Test
  fun anOverlayAtTheSameScopeStaysCleanUnderSuper8() =
    runTest {
      val frame = drawn(edit(Clip(greyClip()), listOf(swatch(), Film.Super8)))

      frame.at(SWATCH_X, SWATCH_Y).isNear(Rgb(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE), CLEAR_CODES) shouldBe true
    }

  // A pass of the test's own takes the clip's alpha part way, and the film after it at the clip's scope draws on the
  // straight color under that alpha. The clip then lies over the black fill by the alpha it holds.
  @Test
  fun aClipFilmOnPartAlphaReadsStraightColor() =
    runTest {
      val film = Film(grain = 1f, halation = 1f, weave = MID, flicker = MID, dust = MID, gate = MID)
      val clip = quarteredClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(PartAlpha, film))))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, film, alpha = PARTIAL_ALPHA) shouldBeLessThan PARTIAL_CODES
    }

  private fun assertMoves(
    frame: PreviewFrame,
    film: Film,
  ) {
    val params = FilmParams.of(film)
    val offset = FilmSignal.weaveAt(params.seed, TICK, params.weave)
    withClue("the weave has to move the frame part of a pixel for this to tell") {
      abs(offset[0] * frame.size.width) shouldBeGreaterThan MIN_WEAVE_PX
    }
    withClue("the flicker has to change the gain for this to tell") {
      abs(FilmSignal.flickerAt(params.seed, TICK, params.flicker) - 1.0) shouldBeGreaterThan MIN_FLICKER
    }
    withClue("dust or a scratch has to land on the frame for this to tell") {
      marks(frame.size.width, frame.size.height, params) shouldBeGreaterThan 0
    }
  }

  private fun marks(
    width: Int,
    height: Int,
    params: FilmParams,
  ): Int =
    (0 until height).sumOf { y ->
      (0 until width).count { x ->
        val cell = FilmSignal.grainCell(x, y, width, height)
        val mark =
          FilmSignal.dustAt(params.seed, TICK, cell, params.dust) +
            FilmSignal.scratchAt(params.seed, TICK, (x + HALF) / width, params.dust)
        mark != 0.0
      }
    }

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
          .addEffectResolver(PartAlphaResolver)
          .build(),
      prober = BrowserProber(),
    ).resolve(composition, spec)
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
      .toBrowserPreview(composition)

  private fun edit(
    clip: Clip,
    effects: List<EffectSpec> = emptyList(),
  ) = Composition(
    tracks = listOf(Track(listOf(clip))),
    effects = effects,
    fill = Fill.Solid(BLACK_ARGB),
    audio = AudioSpec.Remove,
  )

  // Bright and grey quarters, so the weave moves an edge on both axes and the halation glows across both.
  private suspend fun quarteredClip(): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, y ->
        if ((x < WIDTH / 2) != (y < HEIGHT / 2)) BRIGHT else GREY
      },
    )

  private suspend fun greyClip(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> GREY })

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  // The largest gap between the frame and the signal over every pixel, in eight bit codes. With an [alpha] below 1 the
  // film read the picture as it is, its halation glowed on the picture premultiplied by that alpha, and the frame lays
  // the result over the black fill by it.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    film: Film,
    alpha: Double = 1.0,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val params = FilmParams.of(film)
    val offset = FilmSignal.weaveAt(params.seed, TICK, params.weave)
    val shown = Array(width * height) { picture.pixelAt(it % width, it / width).channels() }
    val moved =
      Array(width * height) {
        between(shown, width, height, it % width + offset[0] * width, it / width + offset[1] * height)
      }
    val glow = HalationGlow.of(width, height) { x, y -> DoubleArray(RGB) { moved[y * width + x][it] * alpha } }
    var worst = 0.0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val read = moved[y * width + x]
        val expected =
          FilmSignal
            .pixelAt(
              read[0],
              read[1],
              read[2],
              glow.at(x, y),
              FilmSignal.grainCell(x, y, width, height),
              TICK,
              params,
              (x + HALF) / width,
              (y + HALF) / height,
              width.toDouble() / height,
            )
        val drawn = frame.pixelAt(x, y).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(drawn[channel] - expected[channel] * alpha) * FULL)
      }
    }
    return worst
  }

  // The four pixels around x, y read between, each held at the frame's edge.
  private fun between(
    pixels: Array<DoubleArray>,
    width: Int,
    height: Int,
    x: Double,
    y: Double,
  ): DoubleArray {
    val left = floor(x).toInt()
    val top = floor(y).toInt()
    val across = x - left
    val down = y - top
    val at = { column: Int, row: Int ->
      pixels[row.coerceIn(0, height - 1) * width + column.coerceIn(0, width - 1)]
    }
    return DoubleArray(RGB) { channel ->
      val upper = at(left, top)[channel] * (1 - across) + at(left + 1, top)[channel] * across
      val lower = at(left, top + 1)[channel] * (1 - across) + at(left + 1, top + 1)[channel] * across
      upper * (1 - down) + lower * down
    }
  }

  private fun PreviewFrame.pixelAt(
    x: Int,
    y: Int,
  ): Rgb = at((x + HALF) / size.width, (y + HALF) / size.height)

  private fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)
}

// Takes every pixel's alpha to PARTIAL_ALPHA of what it was and leaves its color.
private object PartAlpha : EffectSpec {
  override val id: EffectId get() = EffectId("test.partAlpha")
  override val stage: EffectStage get() = EffectStage.Spatial
}

private val PartAlphaResolver =
  EffectResolver { spec, _, _ ->
    if (spec != PartAlpha) return@EffectResolver null
    val source =
      "#version 300 es\nprecision highp float;\nin vec2 vUv;\nout vec4 fragColor;\nuniform sampler2D uFrame;\n" +
        "void main() {\n  vec4 c = texture(uFrame, vUv);\n  fragColor = vec4(c.rgb, c.a * $PARTIAL_ALPHA);\n}\n"
    val step = WebGlStep("partAlpha", source, mapOf("uFrame" to WebGlStep.INPUT)) { _, _, _ -> emptyMap() }
    EffectResolution.Resolved(PlatformEffect(WebGlPass(PartAlpha.id.value, listOf(step))))
  }

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(96, 96, 96)
private val BRIGHT = Rgb(235, 225, 215)

private const val MID = 0.5f
private const val HALF = 0.5
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 320
private const val HEIGHT = 240
private const val SMALL_HEIGHT = 120
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val READ_SLOT = 4L
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private val TICK: Int = FilmSignal.grainTickAt(READ_TIME.toDouble(DurationUnit.SECONDS))
private const val OWN_SEED = 68
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SWATCH_CODE = 0x20
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// 153 over 255, so the alpha itself holds in eight bits.
private const val PARTIAL_ALPHA = 0.6

// A read that tells a weave from rounding, and a gain that tells a flicker from none.
private const val MIN_WEAVE_PX = 0.02
private const val MIN_FLICKER = 0.005

// A read and the picture it is held against each round to one eight bit code, and the targets ahead of the last hold
// eight bits.
private const val SIGNAL_CODES = 1.5

// The film's own gap scaled by the alpha it is laid at, and one more code for the lay over the fill.
private const val PARTIAL_CODES = SIGNAL_CODES * PARTIAL_ALPHA + 1.0

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1
