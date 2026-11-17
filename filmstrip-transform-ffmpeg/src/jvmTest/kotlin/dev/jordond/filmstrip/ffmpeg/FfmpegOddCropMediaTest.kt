package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.applyTo
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Exports whose clips are cut to a size 4:2:0 cannot hold on its own chroma grid, read back from the written file.
 *
 * `crop` rounds an odd size down on such a frame unless it is exact, which left the plan a row or a column wider than
 * the frame ffmpeg wrote.
 */
class FfmpegOddCropMediaTest : OddCropMediaCase() {
  @Test
  fun `a clip crop of odd height is written at the planned size with its last row`() =
    runTest(timeout = TIMEOUT) {
      val cut = cutOf(PORTRAIT, FOUR_THREE)
      cut.height % 2 shouldBe 1
      val source = markedClip(PORTRAIT, cut)

      val (written, plan) = exported(compositionOf(source, listOf(FOUR_THREE)))

      written.size shouldBe plan.output.size
      assertMarkedRows(written)
      source.delete()
    }

  @Test
  fun `a clip crop of odd width is written at the planned size with its last column`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(AspectRatio(3, 4))
      val cut = cutOf(LANDSCAPE, crop)
      cut.width % 2 shouldBe 1
      val source = markedClip(LANDSCAPE, cut, across = true)

      val (written, plan) = exported(compositionOf(source, listOf(crop)))

      written.size shouldBe plan.output.size
      assertMarkedColumns(written)
      source.delete()
    }

  @Test
  fun `a clip crop of odd height under a tape look exports at the planned size`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip(PORTRAIT)
      val (written, plan) = exported(compositionOf(source, listOf(FOUR_THREE, Vhs())))

      written.size shouldBe plan.output.size
      source.delete()
    }

  @Test
  fun `a clip crop of odd height under a camcorder date stamp exports at the planned size`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip(PORTRAIT)
      val stamp = Camcorder(listOf(OsdElement.Rec(), OsdElement.Date()))
      val (written, plan) = exported(compositionOf(source, listOf(FOUR_THREE, stamp)))

      written.size shouldBe plan.output.size
      source.delete()
    }

  @Test
  fun `a clip crop under a tape look and a camcorder stamp exports at the planned size`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip(PORTRAIT)
      val stamp = Camcorder(listOf(OsdElement.Date()))
      val (written, plan) = exported(compositionOf(source, listOf(FOUR_THREE, stamp, Vhs())))

      written.size shouldBe plan.output.size
      source.delete()
    }

  @Test
  fun `two clips of different sizes cropped each under a composition tape look export at the planned size`() =
    runTest(timeout = TIMEOUT) {
      assertSeveralClips(listOf(LANDSCAPE, PORTRAIT))
    }

  @Test
  fun `three clips of different sizes cropped each under a composition tape look export at the planned size`() =
    runTest(timeout = TIMEOUT) {
      assertSeveralClips(listOf(LANDSCAPE, PORTRAIT, Size(640, 480)))
    }

  @Test
  fun `every look exports a cut of odd height, of odd width and a frame odd both ways at the planned size`() =
    runTest(timeout = TIMEOUT) {
      val cases =
        listOf(
          Triple(PORTRAIT, listOf<EffectSpec>(FOUR_THREE), "odd height"),
          Triple(LANDSCAPE, listOf<EffectSpec>(Crop(AspectRatio(3, 4))), "odd width"),
          Triple(Size(321, 181), emptyList<EffectSpec>(), "odd both ways"),
          Triple(Size(181, 321), listOf<EffectSpec>(Crop(AspectRatio(4, 3), Fit.Contain)), "odd with clear bars"),
          Triple(Size(321, 181), listOf<EffectSpec>(Crop(AspectRatio(4, 5), Fit.Contain)), "odd bars either side"),
        )
      val failures = mutableListOf<String>()
      for ((size, cuts, name) in cases) {
        val source = flatClip(size)
        for (look in LOOKS) {
          try {
            val (written, plan) = exported(compositionOf(source, cuts + look))
            if (written.size != plan.output.size) failures += "$name under ${look::class.simpleName}: ${written.size}"
          } catch (failed: IllegalStateException) {
            failures += "$name under ${look::class.simpleName}: ${failed.message?.lines()?.firstOrNull()}"
          }
        }
        source.delete()
      }
      withClue(failures.joinToString("\n")) { failures.isEmpty().shouldBeTrue() }
    }

  @Test
  fun `four clips of mixed sizes under a clip crop each and a composition canvas export at the planned size`() =
    runTest(timeout = TIMEOUT) {
      val sizes = listOf(LANDSCAPE, PORTRAIT, Size(180, 102), Size(240, 240))
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
        val sources = sizes.map { flatClip(it) }
        val composition =
          compositionOf {
            sources.forEach { source ->
              clip(MediaSource.of(source.absolutePath)) { effects { add(Crop(aspect)) } }
            }
            effects {
              add(Crop(AspectRatio(4, 3), Fit.Contain))
              add(Scale(SCALED_HEIGHT))
            }
          }
        val (written, plan) = exported(composition)

        withClue("under a $aspect clip crop") { written.size shouldBe plan.output.size }
        sources.forEach { it.delete() }
      }
    }

  @Test
  fun `a clip scale after a clip crop runs after it`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip(LANDSCAPE)
      val (written, plan) = exported(compositionOf(source, listOf(FOUR_THREE, Scale(SMALL_HEIGHT))))

      written.size shouldBe plan.output.size
      source.delete()
    }

  private suspend fun assertSeveralClips(sizes: List<Size>) {
    val sources = sizes.map { flatClip(it) }
    val composition =
      compositionOf {
        sources.forEach { source ->
          clip(MediaSource.of(source.absolutePath)) { effects { add(FOUR_THREE) } }
        }
        effects {
          add(Crop(AspectRatio(4, 3), Fit.Contain))
          add(Vhs())
        }
      }
    val (written, plan) = exported(composition)

    written.size shouldBe plan.output.size
    sources.forEach { it.delete() }
  }

  private companion object {
    val LOOKS: List<EffectSpec> =
      listOf(
        Vhs(),
        Betamax(),
        Hi8(),
        Camcorder(listOf(OsdElement.Rec(), OsdElement.Date())),
        Film(),
        Crt(),
        NightVision(),
        Pixel(),
        Glitch(),
      )
  }
}
