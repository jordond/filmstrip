package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/**
 * Clips of different sizes cut to one aspect and joined, in every order, read back from the written file.
 *
 * Each clip is placed in the output frame on its own, and a clip whose cut is a hair off the aspect lands a pixel in
 * from one edge. concat refuses the graph when any clip reaches it at another size.
 */
class FfmpegCropJoinMediaTest : OddCropMediaCase() {
  @Test
  fun `every order of a wide, a tall and a square clip cut at clip scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) {
      assertEveryOrderJoins(clipScope = true)
    }

  @Test
  fun `every order of a wide, a tall and a square clip cut at composition scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) {
      assertEveryOrderJoins(clipScope = false)
    }

  // Crop-join found the width of a Contain frame off the aspect by up to a pixel and a half on these first clips.
  @Test
  fun `a contain crop at the first clip's height writes the frame the shared function gives and no stray edge`() =
    runTest(timeout = TIMEOUT) {
      val failures = mutableListOf<String>()
      for (size in listOf(Size(91, 51), Size(97, 61), Size(31, 17))) {
        for (clipScope in listOf(true, false)) {
          val source = writeClip(size, "", WHITE_CLIP)
          val composition = joined(listOf(source), CONTAIN, clipScope, null)
          val (written, plan) = exported(composition)
          val natural = frameAfter(CONTAIN, size)
          val expected = alignedFrame(natural, CONTAIN.aspect.value)
          val name = "$size clipScope=$clipScope"
          if (plan.output.size != expected) failures += "$name: planned ${plan.output.size}, wanted $expected"
          if (written.size != plan.output.size) failures += "$name: wrote ${written.size}, planned ${plan.output.size}"
          written.strayEdges().forEach { failures += "$name: $it" }
          source.delete()
        }
      }
      withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

  private suspend fun assertEveryOrderJoins(clipScope: Boolean) {
    val sources = SIZES.associateWith { flatClip(it) }
    val failures = mutableListOf<String>()
    for (aspect in ASPECTS) {
      for (order in orders(SIZES)) {
        for (height in listOf(null, SMALL_HEIGHT)) {
          val name = "$aspect ${order.joinToString(" ") { "${it.width}x${it.height}" }} scale $height"
          val composition = joined(order.map { sources.getValue(it) }, Crop(aspect), clipScope, height)
          try {
            val (written, plan) = exported(composition)
            val output = plan.output.size
            if (written.size != output) failures += "$name: wrote ${written.size}, planned $output"
            if (output.width % 2 != 0 || output.height % 2 != 0) failures += "$name: planned odd $output"
          } catch (failed: IllegalStateException) {
            failures += "$name: ${failed.message?.lines()?.firstOrNull()}"
          }
        }
      }
    }
    sources.values.forEach { it.delete() }
    withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
  }

  private fun joined(
    clips: List<File>,
    cut: Crop,
    clipScope: Boolean,
    height: Int?,
  ): Composition =
    compositionOf {
      clips.forEach { source ->
        clip(MediaSource.of(source.absolutePath)) { if (clipScope) effects { add(cut) } }
      }
      effects {
        val chain = listOfNotNull<EffectSpec>(cut.takeUnless { clipScope }, CONTAIN, height?.let { Scale(it) })
        chain.forEach { add(it) }
      }
    }

  private fun <T> orders(items: List<T>): List<List<T>> =
    if (items.size <= 1) {
      listOf(items)
    } else {
      items.flatMap { first -> orders(items - first).map { listOf(first) + it } }
    }

  private companion object {
    val SIZES = listOf(Size(160, 90), Size(90, 160), Size(120, 120))
    val ASPECTS = listOf(AspectRatio(4, 3), AspectRatio(1, 1), AspectRatio(9, 16), AspectRatio(16, 9))
    val CONTAIN = Crop(AspectRatio(4, 3), Fit.Contain)
  }
}
