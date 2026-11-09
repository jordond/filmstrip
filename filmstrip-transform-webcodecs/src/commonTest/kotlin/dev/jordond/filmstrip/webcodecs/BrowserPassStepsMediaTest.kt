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
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A pass made of more than one step, drawn through the real compositor.
 *
 * The passes here come from a resolver of the test's own, so what is measured is the chain and its pooled targets, and
 * nothing about any one effect.
 */
class BrowserPassStepsMediaTest {
  @Test
  fun aStepReadsTheOutputOfAnEarlierOneAndTheInput() =
    runTest {
      val source = source()
      val pass = listOf(halved("half"), mix("half"))

      val read = drawn(source, listOf(StepsEffect(pass))).at(MIDDLE, MIDDLE)

      val expected = plain(source).scaled(HALF + HALF * HALF)
      withClue("half the picture from the first step and a quarter from the input, read $read") {
        read.isNear(expected, DRAWN_CODES) shouldBe true
      }
    }

  @Test
  fun aStepDrawsAtTheSizeItAsksFor() =
    runTest {
      val quarter = { width: Int, height: Int -> Size(width / 2, height / 2) }
      val writesCoord =
        step("coord", "fragColor = vec4(gl_FragCoord.xy / $COORD_RANGE.0, 0.0, 1.0);", targetAt = quarter)
      val passThrough = step("out", "fragColor = texture(uFrame, vUv);", reads = mapOf("uFrame" to "coord"))

      val read = drawn(source(), listOf(StepsEffect(listOf(writesCoord, passThrough)))).at(MIDDLE, MIDDLE)

      val expected = Rgb(WIDTH / 4 * FULL / COORD_RANGE, HEIGHT / 4 * FULL / COORD_RANGE, 0)
      withClue("the middle of a half size target read $read") { read.isNear(expected, COORD_CODES) shouldBe true }
    }

  @Test
  fun aNameEndingInItsFirstElementSetsAFloatArray() =
    runTest {
      val array =
        step(
          "array",
          "fragColor = vec4(uWeights[0], uWeights[1], uWeights[2], 1.0);",
          declares = "uniform float uWeights[3];",
        ) { _, _, _ -> mapOf("uWeights[0]" to floatArrayOf(0.25f, 0.5f, 0.75f)) }

      val read = drawn(source(), listOf(StepsEffect(listOf(array)))).at(MIDDLE, MIDDLE)

      read.isNear(Rgb(64, 128, 191), DRAWN_CODES) shouldBe true
    }

  @Test
  fun passesAtTwoSizesShareThePoolAcrossFrames() =
    runTest {
      val source = source()
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val chain = listOf(halved("half"), mix("half"))
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(source, effects = listOf(StepsEffect(chain)))))),
          effects = listOf(square, StepsEffect(chain)),
          audio = AudioSpec.Remove,
        )

      val preview = previewOf(composition)
      try {
        val expected = plain(source).scaled((HALF + HALF * HALF) * (HALF + HALF * HALF))
        for (slot in listOf(MIDDLE_SLOT, MIDDLE_SLOT + 1, MIDDLE_SLOT - 1)) {
          val read = checkNotNull(preview.frameAt(outputSlotTime(slot, FRAME_RATE))).at(MIDDLE, MIDDLE)
          withClue("slot $slot read $read") { read.isNear(expected, DRAWN_CODES) shouldBe true }
        }
      } finally {
        preview.release()
      }
    }

  @Test
  fun aStepOfOneKeyHandedTwoSourcesFailsTheExport() =
    runTest {
      val first = step("same", "fragColor = c;")
      val second = step("same", "fragColor = vec4(1.0 - c.rgb, c.a);")

      val message = failureOf(listOf(StepsEffect(listOf(first), KEY), StepsEffect(listOf(second), KEY)))

      message shouldContain "$KEY/same"
    }

  @Test
  fun aLastStepThatMissesTheFrameFailsTheExport() =
    runTest {
      val small = step("small", "fragColor = c;", targetAt = { _, _ -> Size(4, 4) })

      val message = failureOf(listOf(StepsEffect(listOf(small), KEY)))

      message shouldContain "small"
      message shouldContain KEY
    }

  @Test
  fun aPassRefusesAStepThatReadsWhatNothingWrote() {
    val reader = step("reader", "fragColor = c;", reads = mapOf("uFrame" to "later"))

    val failure = assertFailsWith<IllegalArgumentException> { WebGlPass(KEY, listOf(reader)) }

    failure.message.orEmpty() shouldContain "later"
  }

  @Test
  fun aPassRefusesTwoStepsOfOneName() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        WebGlPass(KEY, listOf(step("twin", "fragColor = c;"), step("twin", "fragColor = c;")))
      }

    failure.message.orEmpty() shouldContain "twin"
  }

  @Test
  fun aPassRefusesNoSteps() {
    assertFailsWith<IllegalArgumentException> { WebGlPass(KEY, emptyList<WebGlStep>()) }
  }

  private suspend fun source(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> FLAT })

  private suspend fun plain(source: MediaSource): Rgb = drawn(source, emptyList()).at(MIDDLE, MIDDLE)

  private suspend fun drawn(
    source: MediaSource,
    effects: List<EffectSpec>,
  ): PreviewFrame {
    val composition =
      Composition(tracks = listOf(Track(listOf(Clip(source)))), effects = effects, audio = AudioSpec.Remove)
    val preview = previewOf(composition)
    try {
      return checkNotNull(preview.frameAt(outputSlotTime(MIDDLE_SLOT, FRAME_RATE)))
    } finally {
      preview.release()
    }
  }

  private suspend fun previewOf(composition: Composition): BrowserPreview =
    webCodecsExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addEffectResolver(StepsResolver)
          .build(),
      prober = BrowserProber(),
    ).resolve(composition, ExportSpec(videoCodec = VideoCodec.H264))
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
      .toBrowserPreview(composition)

  private suspend fun failureOf(effects: List<EffectSpec>): String {
    val filmstrip =
      Filmstrip {
        webCodecsBackend()
        addEffectResolver(StepsResolver)
      }
    val composition =
      Composition(tracks = listOf(Track(listOf(Clip(source())))), effects = effects, audio = AudioSpec.Remove)
    val statuses = filmstrip.export(planOf(filmstrip, composition), MediaSink.Uri("")).toList()
    return statuses
      .filterIsInstance<ExportStatus.Failure>()
      .single()
      .error.message
      .orEmpty()
  }

  private fun Rgb.scaled(gain: Double): Rgb =
    Rgb((red * gain).roundToInt(), (green * gain).roundToInt(), (blue * gain).roundToInt())
}

private class StepsEffect(
  val steps: List<WebGlStep>,
  val key: String = steps.joinToString { it.fragmentSource },
) : EffectSpec {
  override val id: String get() = "test.steps"
  override val stage: EffectStage get() = EffectStage.Composite
  override val beneathOverlays: Boolean get() = true
}

private val StepsResolver =
  EffectResolver { spec, _, _ ->
    if (spec is StepsEffect) EffectResolution.Resolved(PlatformEffect(WebGlPass(spec.key, spec.steps))) else null
  }

private fun step(
  name: String,
  body: String,
  reads: Map<String, String> = mapOf("uFrame" to WebGlStep.INPUT),
  declares: String = "",
  targetAt: (Int, Int) -> Size = { width, height -> Size(width, height) },
  uniforms: (kotlin.time.Duration, Int, Int) -> Map<String, FloatArray> = { _, _, _ -> emptyMap() },
) = WebGlStep(name, fragment(body, reads.keys, declares), reads, targetAt, uniformsAt = uniforms)

private fun fragment(
  body: String,
  samplers: Set<String>,
  declares: String,
): String {
  val declared = samplers.joinToString("\n") { "uniform sampler2D $it;" }
  return "#version 300 es\nprecision highp float;\nin vec2 vUv;\nout vec4 fragColor;\n$declared\n$declares\n" +
    "void main() {\n  vec4 c = ${if ("uFrame" in samplers) "texture(uFrame, vUv)" else "vec4(0.0)"};\n  $body\n}\n"
}

private fun halved(name: String) = step(name, "fragColor = vec4(c.rgb * $HALF, c.a);")

private fun mix(earlier: String) =
  step(
    "mix",
    "fragColor = vec4(texture(uEarlier, vUv).rgb + c.rgb * ${HALF * HALF}, c.a);",
    reads = mapOf("uFrame" to WebGlStep.INPUT, "uEarlier" to earlier),
  )

private val FLAT = Rgb(100, 140, 180)

private const val KEY = "test.steps.shared"
private const val FULL = 255
private const val HALF = 0.5
private const val MIDDLE = 0.5
private const val WIDTH = 64
private const val HEIGHT = 48
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val MIDDLE_SLOT = 6L
private const val COORD_RANGE = 256

// A read and the picture it is worked out from each round to one eight bit code.
private const val DRAWN_CODES = 1

// A coordinate lands on a pixel's centre, half a pixel from the quarter point, across a range of 256.
private const val COORD_CODES = 2
