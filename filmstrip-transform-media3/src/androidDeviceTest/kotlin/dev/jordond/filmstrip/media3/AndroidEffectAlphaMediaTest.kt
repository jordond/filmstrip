package dev.jordond.filmstrip.media3

import android.content.Context
import android.opengl.GLES20
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.GlStep
import dev.jordond.filmstrip.effects.StepShaderProgram
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.effects.stepTargetFormat
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.CODE_PATCH
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Effects drawn on frames whose alpha sits part way, through a real media3 export at the clip's scope and under the
 * composition's fill.
 *
 * A pass of the test's own takes the clip's alpha to half over part of its frame, which no decoded clip carries on its
 * own. It is no built-in effect, so it runs after every built-in one of its stage, and a filter at the clip's scope
 * comes after it. A square canvas over the landscape clip leaves bars, so the fill is painted under the picture by the
 * alpha each pixel holds, and every read is held against the shared function of the effect on straight color laid
 * over that fill.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidEffectAlphaMediaTest {
  private val probe =
    Media3ExportProbe {
      filters()
      addEffectResolver(HalfAlphaResolver)
    }

  @Test
  fun aClipLutOnHalfAlphaReadsStraightColor() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()
      val plain = probe.written(contained(source, listOf(contain())))
      val looked = probe.written(contained(source, listOf(contain(), HalfAlpha(HALF), Lut(CURVED))))
      val rect = rectOf(contained(source, listOf(contain())))
      val fill = plain.rgbAt((rect.left + rect.right) / 2f, rect.top / 2f)

      for ((fx, alpha) in listOf(rect.left + rect.width / 4f to HALF, rect.right - rect.width / 4f to 1.0)) {
        val fy = (rect.top + rect.bottom) / 2f
        val picture = plain.rgbAt(fx, fy)
        val want = CURVED.at(picture.first / FULL, picture.second / FULL, picture.third / FULL)
        val read = looked.rgbAt(fx, fy)
        val reads = listOf(read.first, read.second, read.third)
        val fills = listOf(fill.first, fill.second, fill.third)
        for (channel in reads.indices) {
          val expected = want[channel] * FULL * alpha + fills[channel] * (1.0 - alpha)
          withClue("at $fx channel $channel read ${reads[channel]}, and the table lays it at $expected") {
            abs(reads[channel] - expected) shouldBeLessThan TABLE_CODES
          }
        }
      }
    }

  @Test
  fun aCompositionBlurUnderTheFillOnHalfAlphaReadsStraightColor() =
    runTest(timeout = TIMEOUT) {
      val source = halvesClip()
      val plainEdit = contained(source, listOf(contain()))
      val plain = probe.written(plainEdit)
      val blurred = probe.written(contained(source, listOf(contain(), HalfAlpha(QUARTER)), listOf(GaussianBlur(SIGMA))))
      val frame = probe.plan(plainEdit).output.size
      val rect = rectOf(plainEdit)
      val midX = (rect.left + rect.right) / 2f
      val midY = (rect.top + rect.bottom) / 2f
      val alphaEdge = rect.left + rect.width * QUARTER.toFloat()
      val light = plain.green(rect.left + rect.width / 4f, midY)
      val dark = plain.green(rect.right - rect.width / 4f, midY)
      val fill = plain.green(midX, rect.top / 2f)

      val width = frame.width
      val height = frame.height
      val inside = { x: Int, y: Int ->
        (x + HALF) / width in rect.left..rect.right && (y + HALF) / height in rect.top..rect.bottom
      }
      val plane = DoubleArray(width * height) { if ((it % width + HALF) / width < midX) light else dark }
      val alpha =
        DoubleArray(plane.size) {
          val x = it % width
          when {
            !inside(x, it / width) -> 0.0
            (x + HALF) / width < alphaEdge -> HALF
            else -> 1.0
          }
        }
      val shared = blurPlane(plane, alpha, width, height, SIGMA.toDouble())
      val laid = DoubleArray(plane.size) { shared[it] * alpha[it] + fill * (1.0 - alpha[it]) }
      // Read as premultiplied, the frame's color goes into the blur unweighted and comes out multiplied by alpha, which
      // lands well off straight color beside the edge.
      val ones = DoubleArray(plane.size) { 1.0 }
      val unweighted = blurPlane(plane, ones, width, height, SIGMA.toDouble())
      val spread = blurPlane(alpha, ones, width, height, SIGMA.toDouble())
      val misread =
        DoubleArray(plane.size) {
          val a = alpha[it]
          if (a == 0.0) fill else unweighted[it] / spread[it] * a * a + fill * (1.0 - a)
        }

      for (fx in listOf(alphaEdge - SIGMA / 2f, alphaEdge + SIGMA / 2f)) {
        val expected = laid.patchAt(fx, midY, frame)
        val wrong = misread.patchAt(fx, midY, frame)
        withClue("at $fx the shared blur on straight color reads $expected, too close to $wrong to tell") {
          abs(expected - wrong) shouldBeGreaterThan SIGNAL_CODES
        }
        val read = blurred.green(fx, midY)
        withClue("at $fx the blur read $read against straight color laid over the fill at $expected") {
          abs(read - expected) shouldBeLessThan SIGNAL_CODES
        }
      }
    }

  private suspend fun rectOf(edit: Composition) =
    placementOf(SOURCE, listOf(listOf(contain())), probe.plan(edit).output.size).drawnRect

  private fun contained(
    source: MediaSource,
    clipEffects: List<EffectSpec>,
    effects: List<EffectSpec> = emptyList(),
  ): Composition =
    Composition(listOf(Track(listOf(Clip(source, effects = clipEffects)))), effects, fill = Fill.Solid(SLATE))

  private fun contain(): Crop = Crop(SQUARE, fit = Fit.Contain)

  // The shared plane averaged over the patch Written reads at the same point.
  private fun DoubleArray.patchAt(
    fx: Float,
    fy: Float,
    frame: Size,
  ): Double {
    val cx = (fx * frame.width).roundToInt()
    val cy = (fy * frame.height).roundToInt()
    var sum = 0.0
    for (y in cy - CODE_PATCH / 2 until cy + CODE_PATCH / 2) {
      for (x in cx - CODE_PATCH / 2 until cx + CODE_PATCH / 2) sum += this[y * frame.width + x]
    }
    return sum / (CODE_PATCH * CODE_PATCH)
  }

  private fun Written.green(
    fx: Float,
    fy: Float,
  ): Double = rgbAt(fx, fy).second.toDouble()

  private suspend fun flatClip(): MediaSource {
    val file = File(probe.context.cacheDir, "alpha-flat.mp4")
    if (!file.exists()) probe.writeBarsClip(file, SOURCE.width, SOURCE.height, listOf(floatArrayOf(0.7f, 0.5f, 0.3f)))
    return MediaSource.of(file.path)
  }

  private suspend fun halvesClip(): MediaSource {
    val file = File(probe.context.cacheDir, "alpha-halves.mp4")
    if (!file.exists()) {
      val light = LIGHT.toFloat()
      val dark = DARK.toFloat()
      probe.writeBarsClip(
        file,
        SOURCE.width,
        SOURCE.height,
        listOf(floatArrayOf(light, light, light), floatArrayOf(dark, dark, dark)),
      )
    }
    return MediaSource.of(file.path)
  }

  private companion object {
    val SOURCE = Size(640, 360)

    // A square canvas over a landscape clip leaves a bar above and below the picture.
    val SQUARE = AspectRatio(1, 1)
    const val SLATE = 0xFF2F4F6F.toInt()
    const val FULL = 255f
    const val HALF = 0.5
    const val QUARTER = 0.25
    const val SIGMA = 0.05f
    const val LIGHT = 0.8
    const val DARK = 0.3

    // An encoder on each of the two exports a read compares.
    const val TABLE_CODES = 8.0
    const val SIGNAL_CODES = 8.0

    // Cross-talk in every channel, so a read at mid-range tells trilinear from anything simpler.
    val CURVED =
      CubeLut.parse(
        buildString {
          append("LUT_3D_SIZE 5\n")
          for (blue in 0..4) {
            for (green in 0..4) {
              for (red in 0..4) {
                val r = red / 4.0
                val g = green / 4.0
                val b = blue / 4.0
                append(r * r * 0.7 + b * 0.2).append(' ')
                append(g * (1.0 - r * 0.4) + b * b * 0.1).append(' ')
                append((1.0 - b) * 0.5 + g * r * 0.4).append('\n')
              }
            }
          }
        },
      )
  }
}

// Halves the alpha of every pixel left of edge, as a fraction of the clip's own frame, and leaves its color.
private class HalfAlpha(
  val edge: Double,
) : EffectSpec {
  override val id: EffectId get() = EffectId("test.halfAlpha")
  override val stage: EffectStage get() = EffectStage.Spatial
}

@OptIn(InternalFilmstripApi::class)
private class HalfAlphaGlEffect(
  private val edge: Double,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    val fragment =
      """
      #version 300 es
      precision highp float;
      in vec2 vUv;
      out vec4 fragColor;
      uniform sampler2D uFrame;
      void main() {
        vec4 c = texture(uFrame, vUv);
        fragColor = vec4(c.rgb, vUv.x < $edge ? c.a * 0.5 : c.a);
      }
      """.trimIndent()
    val step =
      GlStep("halfAlpha", fragment, mapOf("uFrame" to StepShaderProgram.INPUT), { w, h -> Size(w, h) }) { _, _, _ ->
        emptyMap()
      }
    return StepShaderProgram(
      listOf(step),
      stepTargetFormat(GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()),
      useHighPrecisionColorComponents = useHdr,
    )
  }
}

private val HalfAlphaResolver =
  EffectResolver { spec, _, _ ->
    if (spec is HalfAlpha) EffectResolution.Resolved(PlatformEffect(HalfAlphaGlEffect(spec.edge))) else null
  }
