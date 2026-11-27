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
 * Exports of a canvas crop and a scale over clips of different shapes, read back from the written file: the frame is
 * the one asked for whichever clip is first, and no edge row or column of it is a stray one.
 */
class FfmpegOddFrameMediaTest : OddCropMediaCase() {
  // The canvas and the frame's height give a frame of the aspect asked for, whichever clip is first.
  @Test
  fun `a canvas crop and a scale write the exact aspect's frame whichever clip comes first`() =
    runTest(timeout = TIMEOUT) {
      val sizes = listOf(Size(86, 48), Size(64, 48), Size(192, 108), Size(108, 192))
      val failures = mutableListOf<String>()
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(16, 9), AspectRatio(9, 16), AspectRatio(1, 1))) {
        for (height in listOf(CANVAS_HEIGHT, TALL_HEIGHT)) {
          val width = (height * aspect.value).roundToInt()
          val expected = alignedFrame(Size(width, height), aspect.value)
          for (order in listOf(sizes, sizes.reversed())) {
            val sources = order.map { flatClip(it) }
            val composition =
              compositionOf {
                sources.forEach { clip(MediaSource.of(it.absolutePath)) }
                effects {
                  add(Crop(aspect, Fit.Contain))
                  add(Scale(height))
                }
              }
            val (written, plan) = exported(composition)
            if (written.size != expected || plan.output.size != expected) {
              failures +=
                "$aspect to $height from ${order.first()}: wrote ${written.size}, planned ${plan.output.size}, " +
                "wanted $expected"
            }
            sources.forEach { it.delete() }
          }
        }
      }
      withClue(failures.joinToString("\n")) { failures.isEmpty().shouldBeTrue() }
    }

  // A composition of one clip has the frame it always had: its own canvas, scaled.
  @Test
  fun `a single clip under a canvas crop and a scale keeps its frame and its picture`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip(Size(192, 108))
      val (written, plan) =
        exported(compositionOf(source, listOf(Crop(AspectRatio(4, 3), Fit.Contain), Scale(CANVAS_HEIGHT))))

      written.size shouldBe Size(432, CANVAS_HEIGHT)
      plan.output.size shouldBe written.size
      written.green(written.size.width / 2, written.size.height / 2) shouldBeGreaterThan GRAY_FLOOR
      written.green(written.size.width / 2, 2) shouldBeLessThan GRAY_FLOOR
      source.delete()
    }

  // The compositions an app renders: a crop to the aspect, the canvas of that aspect, then the frame's height, over
  // clips that each need a different cut.
  @Test
  fun `an app's crop, canvas and scale over clips of different sizes export at the planned size`() =
    runTest(timeout = TIMEOUT) {
      val cases =
        listOf(
          Triple(listOf(Size(160, 90), Size(90, 160)), AspectRatio(4, 3), 160),
          Triple(listOf(Size(90, 160), Size(120, 90)), AspectRatio(4, 3), 160),
          Triple(listOf(Size(90, 160), Size(180, 102)), AspectRatio(9, 16), 160),
          Triple(listOf(Size(180, 320), Size(100, 100)), AspectRatio(9, 16), 320),
        )
      val failures = mutableListOf<String>()
      for ((sizes, aspect, height) in cases) {
        for (look in listOf<EffectSpec?>(null, Vhs())) {
          val sources = sizes.map { flatClip(it) }
          val composition =
            compositionOf {
              sources.forEach { clip(MediaSource.of(it.absolutePath)) }
              effects {
                add(Crop(aspect))
                add(Crop(aspect, Fit.Contain))
                add(Scale(height))
                look?.let { add(it) }
              }
            }
          val name = "$sizes at $aspect to $height${if (look == null) "" else " with a tape look"}"
          try {
            val (written, plan) = exported(composition)
            if (written.size != plan.output.size) {
              failures += "$name: wrote ${written.size}, planned ${plan.output.size}"
            }
          } catch (failed: IllegalStateException) {
            failures += "$name: ${failed.message?.lines()?.firstOrNull()}"
          }
          sources.forEach { it.delete() }
        }
      }
      withClue(failures.joinToString("\n")) { failures.isEmpty().shouldBeTrue() }
    }

  // The compositions an app failed on: a portrait and a landscape clip, and a 4K clip beside a small 4:3 one, under a
  // canvas crop and a scale. The clips are white and the fill black, so a picture reaches the frame's edge on one axis
  // and the fill on the other, or on both under a cut. Each writes the planned frame in either order, and no edge line
  // differs from the line inside it, which a column or row of padding, or one the cut lost, would. A white clip is
  // neutral in chroma, so a stray that only has the wrong chroma shows on a saturated red one.
  @Test
  fun `an app's mixed clips write one frame in either order with no stray edge column or row`() =
    runTest(timeout = TIMEOUT) {
      val failures =
        strayFailures(WHITE_CLIP, listOf(APP_HEIGHT, TALL_APP_HEIGHT)) + strayFailures(RED_CLIP, listOf(APP_HEIGHT))
      withClue(failures.joinToString("\n")) { failures.isEmpty().shouldBeTrue() }
    }

  private suspend fun strayFailures(
    color: String,
    heights: List<Int>,
  ): List<String> {
    val pairs = listOf(listOf(PORTRAIT, LANDSCAPE), listOf(Size(3840, 2160), Size(640, 480)))
    val failures = mutableListOf<String>()
    for (sizes in pairs) {
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
        for (fit in listOf(Fit.Contain, Fit.Crop)) {
          for (height in heights) {
            val width = (height * aspect.value).roundToInt()
            val expected = alignedFrame(Size(width, height), aspect.value)
            for (order in listOf(sizes, sizes.reversed())) {
              val sources = order.map { writeClip(it, "", color) }
              val composition =
                compositionOf {
                  sources.forEach { clip(MediaSource.of(it.absolutePath)) }
                  effects {
                    add(Crop(aspect, fit))
                    add(Scale(height))
                  }
                }
              val (written, plan) = exported(composition)
              val name = "$color $order at $aspect ($fit) to $height"
              if (written.size != expected || plan.output.size != expected) {
                failures += "$name: wrote ${written.size}, planned ${plan.output.size}, wanted $expected"
              }
              written.strayEdges().forEach { failures += "$name: $it" }
              sources.forEach { it.delete() }
            }
          }
        }
      }
    }
    return failures
  }

  // The clip's own cut is odd, and the canvas it lands on carries it to a frame of its own size: the cut's last row
  // has to survive the cut, the canvas and the scale, or the written frame's foot loses its white line.
  @Test
  fun `a clip cut to an odd height keeps its last row through a canvas and a scale`() =
    runTest(timeout = TIMEOUT) {
      val cut = cutOf(PORTRAIT, FOUR_THREE)
      cut.height % 2 shouldBe 1
      for (order in listOf(listOf(PORTRAIT, LANDSCAPE), listOf(LANDSCAPE, PORTRAIT))) {
        val sources = order.map { if (it == PORTRAIT) markedClip(it, cut) else flatClip(it) }
        val composition =
          compositionOf {
            sources.forEachIndexed { index, source ->
              clip(MediaSource.of(source.absolutePath)) { if (order[index] == PORTRAIT) effects { add(FOUR_THREE) } }
            }
            effects {
              add(Crop(AspectRatio(4, 3), Fit.Contain))
              add(Scale(APP_HEIGHT))
            }
          }
        val (written, plan) = exported(composition)

        written.size shouldBe plan.output.size
        if (order.first() == PORTRAIT) assertMarkedRows(written)
        sources.forEach { it.delete() }
      }
    }

  // A clip that fills an odd frame overflows it, and what overflows is cut away down to the frame's own odd size.
  @Test
  fun `a clip that fills an odd frame is cut to the frame the plan sizes`() =
    runTest(timeout = TIMEOUT) {
      val failures = mutableListOf<String>()
      for (order in listOf(listOf(Size(321, 181), Size(180, 320)), listOf(Size(181, 321), Size(320, 180)))) {
        val sources = order.map { writeClip(it, "", WHITE_CLIP) }
        val composition =
          compositionOf {
            sources.forEach { clip(MediaSource.of(it.absolutePath)) }
            effects { add(Scale(order.first().height, Fit.Crop)) }
          }
        val (written, plan) = exported(composition)
        if (written.size != plan.output.size) failures += "$order: wrote ${written.size}, planned ${plan.output.size}"
        written.strayEdges().forEach { failures += "$order: $it" }
        sources.forEach { it.delete() }
      }
      withClue(failures.joinToString("\n")) { failures.isEmpty().shouldBeTrue() }
    }

  // Every tape look pads an odd frame in 4:4:4 to keep its last row and column, and takes it back to 4:2:0. A saturated
  // clip shows whether the pad kept the chroma of that last row and column or filled it in neutral.
  @Test
  fun `an odd frame under a tape look keeps the colour of its last row and column`() =
    runTest(timeout = TIMEOUT) {
      for (look in listOf<EffectSpec>(Vhs(), Betamax(), Hi8())) {
        val source = writeClip(PORTRAIT, "", RED_CLIP)
        val (written, plan) = exported(compositionOf(source, listOf(FOUR_THREE, look)))

        withClue("${look::class.simpleName}") {
          written.size shouldBe plan.output.size
          written.redness(written.size.width / 2, written.size.height - 1) shouldBeGreaterThan RED_FLOOR
          written.redness(written.size.width - 1, written.size.height / 2) shouldBeGreaterThan RED_FLOOR
        }
        source.delete()
      }
    }

  // The second clip is cut to the frame by a crop whose offset is odd: it scales to 640x430 and keeps rows 35 to 394 of
  // them, which 4:2:0 would round to 34 with a crop that is not exact. A white line on each of those two rows has to
  // land on the first and last row of the frame, read from the second clip's span.
  @Test
  fun `a clip that overflows the frame by an odd offset is cut at the row asked for`() =
    runTest(timeout = TIMEOUT) {
      val first = flatClip(Size(640, 360))
      val markers = listOf(35, 394).joinToString(",") { "drawbox=x=0:y=$it:w=iw:h=1:color=white:t=fill" }
      val second = writeClip(Size(640, 430), ",$markers", GRAY_CLIP)
      val composition =
        compositionOf {
          clip(MediaSource.of(first.absolutePath))
          clip(MediaSource.of(second.absolutePath))
          effects { add(Scale(360, Fit.Crop)) }
        }
      val (written, plan) = exported(composition, at = SECOND_CLIP_AT)

      written.size shouldBe plan.output.size
      written.size shouldBe Size(640, 360)
      assertMarkedRows(written)
      first.delete()
      second.delete()
    }
}
