package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.AvCompositorInstruction
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One SDR export through a Core Image effect on iOS, read back with the prober, with no pixel read.
 *
 * The effect is drawn in the filtering handler AVFoundation calls per frame rather than by the custom compositor, so
 * the export runs the iOS side of `FilteringRequest.ios.kt`, the source frame it reads and the image it finishes the
 * request with. A case fails when its fixture is missing, as in [AppleExportSmokeTest].
 */
@OptIn(ExperimentalForeignApi::class)
class IosExportCanaryTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `exports one clip through a core image effect`() =
    runTest(timeout = TIMEOUT) {
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture("apple_export_long.mp4"))) { trim(0.seconds, 1.seconds) }
          effects { brightness(DIMMED) }
        }
      withClue("the effect was drawn by the compositor, not the filtering handler") {
        drawsThroughCompositor(composition) shouldBe false
      }

      val output = NSTemporaryDirectory() + "filmstrip-ios-export-canary.mp4"
      val success = filmstrip.exportOrFail(composition, SPEC, output)

      success.info.video!!.displaySize shouldBe Size(426, 240)
      withClue("duration was ${success.info.duration}") {
        (success.info.duration in 800.milliseconds..1_200.milliseconds).shouldBeTrue()
      }

      NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    }

  private suspend fun drawsThroughCompositor(composition: EditComposition): Boolean =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, SPEC)) {
        is ResolveResult.Refused -> {
          error(result.error.message)
        }
        is ResolveResult.Resolved -> {
          result.composition
            .toAvComposition()
            .videoComposition
            .shouldNotBeNull()
            .instructions
            .any { it is AvCompositorInstruction }
        }
      }
    }

  private companion object {
    val TIMEOUT = 2.seconds * 60
    val SPEC = ExportSpec(heightPx = 240)
    const val DIMMED = 0.8f
  }
}
