package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.effect.WebGlTable
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.browserRenderOf
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Passes that carry their own fragment source, drawn through the real compositor.
 *
 * The shaders here come from a resolver of the test's own, so what is measured is the pass stage and where it sits in a
 * chain, and nothing about any one effect. Every read comes off a preview, so no encoder sits between it and the pass.
 */
class BrowserShaderPassMediaTest {
  @Test
  fun compositionPassesRunInChainOrderOverTheFill() =
    runTest {
      val source = source()
      val frame = drawn(listOf(Clip(source)), listOf(ShaderEffect(INVERT), ShaderEffect(HALVE)))

      val expected = plain(source).map { (FULL - it) * HALF_GAIN }
      withClue("inverting and then halving, not the other way round") {
        frame.at(MIDDLE, MIDDLE).isNear(expected.toRgb(), DRAWN_CODES) shouldBe true
      }
    }

  @Test
  fun aClipPassSeesTheClipsColorAndTheCompositionsColorSeesThePass() =
    runTest {
      val source = source()
      val clip = Clip(source, effects = listOf(Grade, ShaderEffect(INVERT)))
      val frame = drawn(listOf(clip), listOf(Grade))

      val expected = plain(source).map { (FULL - it * HALF_GAIN) * HALF_GAIN }
      withClue("the clip's grade, the pass and the composition's grade, in that order") {
        frame.at(MIDDLE, MIDDLE).isNear(expected.toRgb(), DRAWN_CODES) shouldBe true
      }
    }

  @Test
  fun aPassReadsTheCompositionTimeOfItsFrame() =
    runTest {
      val source = source()
      val flat = plain(source)
      val composition = editOf(listOf(Clip(source)), listOf(ShaderEffect(HALVE_AFTER_SWITCH)))
      val preview = previewOf(composition)
      try {
        val before = preview.frameAt(outputSlotTime(1, FRAME_RATE)).shouldNotBeNull()
        val after = preview.frameAt(outputSlotTime(FRAMES - 1L, FRAME_RATE)).shouldNotBeNull()

        before.at(MIDDLE, MIDDLE).isNear(flat.toRgb(), DRAWN_CODES) shouldBe true
        after.at(MIDDLE, MIDDLE).isNear(flat.map { it * HALF_GAIN }.toRgb(), DRAWN_CODES) shouldBe true
      } finally {
        preview.release()
      }
    }

  @Test
  fun aCompositionPassReadsTheOutputSizeAndAClipPassItsOwnFrame() =
    runTest {
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val onClip = drawn(listOf(Clip(source(), effects = listOf(ShaderEffect(WRITES_SIZE)))), listOf(square))
      val onComposition = drawn(listOf(Clip(source())), listOf(square, ShaderEffect(WRITES_SIZE)))

      val clipRead = onClip.at(MIDDLE, MIDDLE)
      val outputRead = onComposition.at(MIDDLE, MIDDLE)
      val side = onComposition.size.width
      withClue("a clip pass read $clipRead, and its frame is the clip's quad on the square") {
        clipRead.isNear(sizeCodes(side, side * HEIGHT / WIDTH), SIZE_CODES) shouldBe true
      }
      withClue("a composition pass read $outputRead, and its frame is the whole square") {
        outputRead.isNear(sizeCodes(side, side), SIZE_CODES) shouldBe true
      }
    }

  @Test
  fun aGapSlotRunsTheCompositionPassOverTheFill() =
    runTest {
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(source())), start = GAP)),
          effects = listOf(ShaderEffect(INVERT)),
          audio = AudioSpec.Remove,
        )
      val preview = previewOf(composition)
      try {
        val frame = preview.frameAt(Duration.ZERO).shouldNotBeNull()

        withClue("a black fill inverted") { frame.at(MIDDLE, MIDDLE).isNear(WHITE, DRAWN_CODES) shouldBe true }
      } finally {
        preview.release()
      }
    }

  @Test
  fun aBlurredFillGoesUnderTheCompositionPass() =
    runTest {
      val source = source()
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val plain = Composition(listOf(Track(listOf(Clip(source)))), listOf(square), AudioSpec.Remove, Fill.Blur)
      val inverted =
        Composition(
          listOf(Track(listOf(Clip(source)))),
          listOf(square, ShaderEffect(INVERT)),
          AudioSpec.Remove,
          Fill.Blur,
        )

      val bar = frameOf(plain).at(MIDDLE, BAR_ROW)
      val read = frameOf(inverted).at(MIDDLE, BAR_ROW)

      withClue("the bar read $read, and the blurred fill there is $bar") {
        read.isNear(Rgb(255 - bar.red, 255 - bar.green, 255 - bar.blue), DRAWN_CODES) shouldBe true
      }
    }

  @Test
  fun aClipPassUnderABlurredFillIsWhatTheFillBlurs() =
    runTest {
      val source = source()
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val clip = Clip(source, effects = listOf(ShaderEffect(INVERT)))
      val inverted = Composition(listOf(Track(listOf(clip))), listOf(square), AudioSpec.Remove, Fill.Blur)

      val bar = frameOf(inverted).at(MIDDLE, BAR_ROW)
      val expected = plain(source).map { FULL - it }

      withClue("the bar read $bar, and a flat frame inverted blurs to itself") {
        bar.isNear(expected.toRgb(), DRAWN_CODES) shouldBe true
      }
    }

  @Test
  fun oneKeyWithTwoSourcesFailsTheExport() =
    runTest {
      val filmstrip =
        Filmstrip {
          webCodecsBackend()
          addEffectResolver(ShaderResolver)
        }
      val first = ShaderEffect(INVERT, key = "test.shared")
      val second = ShaderEffect(HALVE, key = "test.shared")
      val composition = editOf(listOf(Clip(source())), listOf(first, second))

      val statuses = filmstrip.export(planOf(filmstrip, composition), MediaSink.Uri("")).toList()

      val failure = statuses.filterIsInstance<ExportStatus.Failure>().single()
      failure.error.message.orEmpty() shouldContain "test.shared"
    }

  @Test
  fun aCompositePassWithNoSourceAndNoKnownProgramThrows() =
    runTest {
      val failure = assertFailsWith<BrowserExportFailure> { lowered(listOf(Clip(source())), listOf(Unknown)) }

      failure.message.orEmpty() shouldContain Unknown.id
    }

  @Test
  fun aClipPassWithNoSourceAndNoKnownProgramThrows() =
    runTest {
      val failure =
        assertFailsWith<BrowserExportFailure> { lowered(listOf(Clip(source(), effects = listOf(Unknown)))) }

      failure.message.orEmpty() shouldContain Unknown.id
    }

  @Test
  fun aShaderPassOutsideTheCompositeStageThrows() =
    runTest {
      val failure =
        assertFailsWith<BrowserExportFailure> {
          lowered(listOf(Clip(source())), listOf(ShaderEffect(INVERT, stage = EffectStage.Spatial)))
        }

      failure.message.orEmpty() shouldContain "Composite stage"
    }

  @Test
  fun aShaderPassOverAnOverlayThrows() =
    runTest {
      val failure =
        assertFailsWith<BrowserExportFailure> {
          lowered(listOf(Clip(source())), listOf(overlay(), ShaderEffect(INVERT, beneath = false)))
        }

      failure.message.orEmpty() shouldContain "after an overlay"
    }

  @Test
  fun aUniformOfNoGlslSizeFailsTheExport() =
    runTest {
      val filmstrip =
        Filmstrip {
          webCodecsBackend()
          addEffectResolver(ShaderResolver)
        }
      val composition = editOf(listOf(Clip(source())), listOf(ShaderEffect(BAD_UNIFORM)))

      val statuses = filmstrip.export(planOf(filmstrip, composition), MediaSink.Uri("")).toList()

      val failure = statuses.filterIsInstance<ExportStatus.Failure>().single()
      failure.error.message.orEmpty() shouldContain "uBad"
    }

  @Test
  fun aFrameTableNamingASamplerTheStepReadsFailsTheExport() =
    runTest {
      val filmstrip =
        Filmstrip {
          webCodecsBackend()
          addEffectResolver(ShaderResolver)
        }
      val composition = editOf(listOf(Clip(source())), listOf(ShaderEffect(TABLE_OVER_FRAME)))

      val statuses = filmstrip.export(planOf(filmstrip, composition), MediaSink.Uri("")).toList()

      val failure = statuses.filterIsInstance<ExportStatus.Failure>().single()
      failure.error.message.orEmpty() shouldContain "binds uFrame twice"
    }

  private suspend fun source(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> FLAT })

  // The picture a pass reads, which is the fixture after its encoder and the decoder.
  private suspend fun plain(source: MediaSource): List<Double> {
    val read = drawn(listOf(Clip(source))).at(MIDDLE, MIDDLE)
    return listOf(read.red.toDouble(), read.green.toDouble(), read.blue.toDouble())
  }

  private suspend fun drawn(
    clips: List<Clip>,
    effects: List<EffectSpec> = emptyList(),
  ): PreviewFrame = frameOf(editOf(clips, effects))

  private suspend fun frameOf(composition: Composition): PreviewFrame {
    val preview = previewOf(composition)
    try {
      return preview.frameAt(outputSlotTime(MIDDLE_SLOT, FRAME_RATE)).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  private suspend fun previewOf(composition: Composition): BrowserPreview =
    resolved(composition).toBrowserPreview(composition)

  private suspend fun lowered(
    clips: List<Clip>,
    effects: List<EffectSpec> = emptyList(),
  ) {
    val composition = editOf(clips, effects)
    browserRenderOf(resolved(composition), composition)
  }

  private suspend fun resolved(composition: Composition): ResolvedComposition =
    webCodecsExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addEffectResolver(ShaderResolver)
          .build(),
      prober = BrowserProber(),
    ).resolve(composition, ExportSpec(videoCodec = VideoCodec.H264))
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition

  private fun editOf(
    clips: List<Clip>,
    effects: List<EffectSpec>,
  ) = Composition(tracks = listOf(Track(clips)), effects = effects, audio = AudioSpec.Remove)

  private fun overlay(): ImageOverlay =
    ImageOverlay(ImageSource.ofBytes(OverlayPixels.pngOf(Size(4, 4), OVERLAY_ARGB)), Corner.TopStart)

  private fun sizeCodes(
    width: Int,
    height: Int,
  ) = Rgb(width * FULL.toInt() / SIZE_RANGE, height * FULL.toInt() / SIZE_RANGE, 0)
}

private class ShaderEffect(
  val shader: WebGlStep,
  override val stage: EffectStage = EffectStage.Composite,
  beneath: Boolean = true,
  val key: String = shader.fragmentSource,
) : EffectSpec {
  override val id: String get() = "test.shader"
  override val beneathOverlays: Boolean = beneath
}

private data object Grade : EffectSpec {
  override val id: String get() = "test.grade"
  override val stage: EffectStage get() = EffectStage.Color
}

private data object Unknown : EffectSpec {
  override val id: String get() = "test.unknown"
  override val stage: EffectStage get() = EffectStage.Composite
}

private val ShaderResolver =
  EffectResolver { spec, _, _ ->
    when (spec) {
      is ShaderEffect -> {
        EffectResolution.Resolved(PlatformEffect(WebGlPass(spec.key, listOf(spec.shader))))
      }
      is Grade -> {
        val half = floatArrayOf(0.5f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 1f)
        EffectResolution.Resolved(PlatformEffect(WebGlPass("test.grade", mapOf("uColorMatrix" to half))))
      }
      is Unknown -> {
        EffectResolution.Resolved(PlatformEffect(WebGlPass("test.unknown", emptyMap())))
      }
      else -> {
        null
      }
    }
  }

private fun fragment(body: String): String =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D uFrame;
  uniform float uGain;
  uniform vec2 uSize;
  uniform vec3 uBad;
  void main() {
    vec4 c = texture(uFrame, vUv);
    $body
  }
  """.trimIndent()

private fun shader(
  body: String,
  uniforms: (Duration, Int, Int) -> Map<String, FloatArray> = { _, _, _ -> emptyMap() },
) = WebGlStep("draw", fragment(body), mapOf("uFrame" to WebGlStep.INPUT), uniformsAt = uniforms)

private val INVERT = shader("fragColor = vec4(1.0 - c.rgb, c.a);")
private val HALVE =
  shader("fragColor = vec4(c.rgb * uGain, c.a);") { _, _, _ -> mapOf("uGain" to floatArrayOf(HALF_GAIN.toFloat())) }
private val HALVE_AFTER_SWITCH =
  shader("fragColor = vec4(c.rgb * uGain, c.a);") { time, _, _ ->
    mapOf("uGain" to floatArrayOf(if (time < SWITCH_AT) 1f else HALF_GAIN.toFloat()))
  }
private val WRITES_SIZE =
  shader("fragColor = vec4(uSize / ${SIZE_RANGE}.0, 0.0, c.a);") { _, width, height ->
    mapOf("uSize" to floatArrayOf(width.toFloat(), height.toFloat()))
  }
private val TABLE_OVER_FRAME =
  WebGlStep(
    "draw",
    fragment("fragColor = c;"),
    mapOf("uFrame" to WebGlStep.INPUT),
    texturesAt = { _, _, _ -> mapOf("uFrame" to WebGlTable(1, 1, ByteArray(4))) },
  ) { _, _, _ -> emptyMap() }
private val BAD_UNIFORM =
  shader("fragColor = vec4(uBad, c.a);") { _, _, _ -> mapOf("uBad" to FloatArray(5)) }

private fun List<Double>.toRgb(): Rgb = Rgb(this[0].roundToInt(), this[1].roundToInt(), this[2].roundToInt())

private val FLAT = Rgb(100, 140, 180)
private val WHITE = Rgb(255, 255, 255)
private val SWITCH_AT = 200.milliseconds
private val GAP = 200.milliseconds

private const val FULL = 255.0
private const val HALF_GAIN = 0.5
private const val MIDDLE = 0.5
private const val BAR_ROW = 0.05
private const val OVERLAY_ARGB = 0xFF102030.toInt()
private const val WIDTH = 64
private const val HEIGHT = 48
private const val SIZE_RANGE = 256
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val MIDDLE_SLOT = 6L

// A read and the picture it is worked out from each round to one eight bit code.
private const val DRAWN_CODES = 1

// Two neighbouring frame sizes land about one code apart, written over a range of 256 pixels.
private const val SIZE_CODES = 1
