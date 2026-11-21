package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Clips of different sizes cut to one aspect and joined, in every order, read back from the written file.
 *
 * Each clip is placed in the output frame on its own, and a clip whose cut is a hair off the aspect lands a pixel in
 * from one edge. Every export has to succeed and write the frame the plan reports, even both ways.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleCropJoinHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `every order cut to 4 by 3 at clip scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(4, 3), clipScope = true) }

  @Test
  fun `every order cut to 4 by 3 at composition scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(4, 3), clipScope = false) }

  @Test
  fun `every order cut to square at clip scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(1, 1), clipScope = true) }

  @Test
  fun `every order cut to square at composition scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(1, 1), clipScope = false) }

  @Test
  fun `every order cut to 9 by 16 at clip scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(9, 16), clipScope = true) }

  @Test
  fun `every order cut to 9 by 16 at composition scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(9, 16), clipScope = false) }

  @Test
  fun `every order cut to 16 by 9 at clip scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(16, 9), clipScope = true) }

  @Test
  fun `every order cut to 16 by 9 at composition scope joins at the planned size`() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(16, 9), clipScope = false) }

  private suspend fun assertEveryOrderJoins(
    aspect: AspectRatio,
    clipScope: Boolean,
  ) {
    val failures = mutableListOf<String>()
    val output = NSTemporaryDirectory() + "filmstrip-apple-crop-join.mp4"
    for (order in orders(FIXTURES)) {
      for (height in listOf(null, SMALL_HEIGHT)) {
        val name = "$aspect ${order.joinToString(" ")} scale $height"
        NSFileManager.defaultManager.removeItemAtPath(output, error = null)
        failures += checked(name, joined(order, Crop(aspect), clipScope, height), output)
      }
    }
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
  }

  private suspend fun checked(
    name: String,
    composition: Composition,
    output: String,
  ): List<String> =
    withContext(Dispatchers.Default) {
      val plan =
        when (val verdict = filmstrip.plan(composition, ExportSpec())) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> return@withContext listOf("$name: refused ${verdict.reasons.map { it.message }}")
        }
      val planned = plan.output.size
      when (val finished = filmstrip.export(plan, MediaSink.of(output)).toList().last()) {
        is ExportStatus.Success -> {
          val written = finished.info.video?.displaySize
          listOfNotNull(
            "$name: wrote $written, planned $planned".takeIf { written != planned },
            "$name: planned odd $planned".takeIf { planned.width % 2 != 0 || planned.height % 2 != 0 },
          )
        }
        is ExportStatus.Failure -> {
          listOf("$name: ${finished.error.message}")
        }
        else -> {
          listOf("$name: ended on $finished")
        }
      }
    }

  private fun joined(
    order: List<String>,
    cut: Crop,
    clipScope: Boolean,
    height: Int?,
  ): Composition =
    compositionOf {
      order.forEach { name ->
        clip(MediaSource.of(fixture(name))) { if (clipScope) effects { add(cut) } }
      }
      effects {
        listOfNotNull<EffectSpec>(cut.takeUnless { clipScope }, CONTAIN, height?.let { Scale(it) }).forEach { add(it) }
      }
    }

  private fun <T> orders(items: List<T>): List<List<T>> =
    if (items.size <= 1) {
      listOf(items)
    } else {
      items.flatMap { first -> orders(items - first).map { listOf(first) + it } }
    }

  private companion object {
    val TIMEOUT = 4.minutes
    val FIXTURES = listOf("join_wide.mp4", "join_tall.mp4", "join_square.mp4")
    val CONTAIN = Crop(AspectRatio(4, 3), Fit.Contain)

    // The height the reported shape scaled to, small enough that the frame is no longer the clips' own size.
    const val SMALL_HEIGHT = 90
  }
}
