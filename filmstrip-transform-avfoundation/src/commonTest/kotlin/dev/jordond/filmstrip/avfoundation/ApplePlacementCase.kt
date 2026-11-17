package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionBuilder
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.time.Duration.Companion.seconds

/**
 * What the Apple placement hardware tests share: an AVFoundation backend, the fixtures they export and the files they
 * write.
 */
@OptIn(ExperimentalForeignApi::class)
abstract class ApplePlacementCase {
  internal val filmstrip = Filmstrip { avFoundationBackend() }

  internal suspend fun exported(
    name: String,
    build: CompositionBuilder.() -> Unit,
  ): Pair<String, ExportPlan> = exported(compositionOf(build), temporaryPath(name))

  internal suspend fun exported(
    composition: Composition,
    output: String,
    spec: ExportSpec = SPEC,
  ): Pair<String, ExportPlan> =
    withContext(Dispatchers.Default) {
      val plan =
        when (val verdict = filmstrip.plan(composition, spec)) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
        }
      val finished = filmstrip.export(plan, MediaSink.of(output)).toList().last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()
      output to plan
    }

  internal fun isSlate(pixel: Triple<Int, Int, Int>): Boolean = distance(pixel, SLATE) <= COLOR_TOLERANCE

  internal fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-placement-$name.mp4"

  internal fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  internal companion object {
    const val LANDSCAPE = "apple_export_a.mp4"
    const val PORTRAIT = "apple_export_portrait.mp4"
    const val FRAME_HEIGHT = 324

    // A bar this many pixels deep is read as one at the frame's edge, and a bar under the second is the content's.
    const val BAR_PIXELS = 2f
    const val REACHED_PIXELS = 0.5f
    val TIMEOUT = 2.seconds * 60
    val SPEC = ExportSpec(heightPx = 360, bitrate = COLOR_READ_BITRATE)
    val SLATE = Triple(0x20, 0x40, 0x60)
  }
}
