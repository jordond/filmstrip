package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.MeasuredFrame
import dev.jordond.filmstrip.transform.internal.negotiation.fittedBetween
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Clips of different sizes cut to one aspect and joined, in every order, read back from the written file.
 *
 * Each clip is placed in the output frame on its own, and a clip whose cut is a hair off the aspect lands a pixel in
 * from one edge. Every export has to succeed and write the frame the plan reports, even both ways.
 */
class BrowserCropJoinMediaTest {
  @Test
  fun everyOrderCutToFourByThreeAtClipScopeJoinsAtThePlannedSize() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(4, 3), clipScope = true) }

  @Test
  fun everyOrderCutToFourByThreeAtCompositionScopeJoinsAtThePlannedSize() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(4, 3), clipScope = false) }

  @Test
  fun everyOrderCutToSquareAtClipScopeJoinsAtThePlannedSize() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(1, 1), clipScope = true) }

  @Test
  fun everyOrderCutToSquareAtCompositionScopeJoinsAtThePlannedSize() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(1, 1), clipScope = false) }

  @Test
  fun everyOrderCutToNineBySixteenAtClipScopeJoinsAtThePlannedSize() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(9, 16), clipScope = true) }

  @Test
  fun everyOrderCutToNineBySixteenAtCompositionScopeJoinsAtThePlannedSize() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(9, 16), clipScope = false) }

  @Test
  fun everyOrderCutToSixteenByNineAtClipScopeJoinsAtThePlannedSize() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(16, 9), clipScope = true) }

  @Test
  fun everyOrderCutToSixteenByNineAtCompositionScopeJoinsAtThePlannedSize() =
    runTest(timeout = TIMEOUT) { assertEveryOrderJoins(AspectRatio(16, 9), clipScope = false) }

  private suspend fun assertEveryOrderJoins(
    aspect: AspectRatio,
    clipScope: Boolean,
  ) {
    val filmstrip = webCodecsFilmstrip()
    val failures = mutableListOf<String>()
    for (order in orders(FIXTURES)) {
      for (height in listOf(null, SMALL_HEIGHT)) {
        val name = "$aspect ${order.joinToString(" ")} scale $height"
        val plan = planOf(filmstrip, joined(order, Crop(aspect), clipScope, height))
        val planned = plan.output.size
        val alignment = alignmentOf(filmstrip, plan)
        if (planned.width % alignment != 0 || planned.height % alignment != 0) {
          failures += "$name: planned $planned off the encoder's $alignment grid"
        }
        val shared = containedAt(planned.height, alignment)
        if (planned != shared) failures += "$name: planned $planned, shared fit $shared"
        when (val finished = filmstrip.export(plan, MediaSink.Uri("")).toList().last()) {
          is ExportStatus.Success -> {
            val written = finished.info.video?.displaySize
            if (written != planned) failures += "$name: wrote $written, planned $planned"
          }
          is ExportStatus.Failure -> {
            failures += "$name: ${finished.error.message}"
          }
          else -> {
            failures += "$name: ended on $finished"
          }
        }
      }
    }
    withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
  }

  private fun joined(
    order: List<String>,
    cut: Crop,
    clipScope: Boolean,
    height: Int?,
  ): Composition =
    Composition(
      tracks =
        listOf(
          Track(
            order.map { name ->
              Clip(MediaSource.Uri("/base/kotlin/$name"), effects = if (clipScope) listOf(cut) else emptyList())
            },
          ),
        ),
      effects = listOfNotNull<EffectSpec>(cut.takeUnless { clipScope }, CONTAIN, height?.let { Scale(it) }),
    )

  // The size alignment of the encoder the plan writes with, as the device reports it.
  private suspend fun alignmentOf(
    filmstrip: Filmstrip,
    plan: ExportPlan,
  ): Int =
    filmstrip
      .capabilities()
      .shouldBeInstanceOf<CapabilitiesResult.Success>()
      .capabilities
      .encoderFor(plan.output.videoCodec)
      .shouldNotBeNull()
      .sizeAlignment

  // The 4:3 frame the shared fit gives at this planned height. The last effect is always a 4:3 contain crop.
  private fun containedAt(
    height: Int,
    alignment: Int,
  ): Size =
    MeasuredFrame(Size((height * CONTAIN.aspect.value).roundToInt(), height), CONTAIN.aspect.value)
      .fittedBetween(null, null, alignment)
      .shouldNotBeNull()

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
