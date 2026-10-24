package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A step that throws on every frame of an export, exported for real.
 *
 * The run fails with the step's reason and stops near the frame that threw rather than drawing the rest of the
 * composition first, so the step runs on far fewer frames than the composition holds.
 */
class AppleStepFailureExportTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  @Test
  fun `a step that throws fails the export early with its reason`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest
      val drawn = AtomicInt(0)
      val filmstrip =
        Filmstrip {
          avFoundationBackend()
          addEffectResolver(ThrowingResolver(drawn))
        }
      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, CLIP) }
          effects { add(Throwing) }
        }
      val output = NSTemporaryDirectory() + "filmstrip-apple-step-failure.mp4"

      val statuses =
        withContext(Dispatchers.Default) {
          filmstrip.export(composition, ExportSpec(targetHeight = 180), MediaSink.of(output)).toList()
        }

      val failed = assertIs<ExportStatus.Failure>(statuses.last())
      val error = assertIs<ExportError.UnsupportedEffect>(failed.error)
      error.specId shouldBe Throwing.id
      error.message shouldBe REASON
      val frames = (CLIP.inWholeMilliseconds * FRAME_RATE / 1_000).toInt()
      assertTrue(drawn.value < frames / 2, "the step ran on ${drawn.value} of $frames frames before the run stopped")
    }

  private object Throwing : EffectSpec {
    override val id: String = "dev.jordond.filmstrip.test.throwing"
  }

  private class ThrowingResolver(
    private val drawn: AtomicInt,
  ) : EffectResolver {
    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? =
      if (spec !is Throwing) {
        null
      } else {
        EffectResolution.Resolved(
          PlatformEffect(
            CoreImageEffect { _, _ ->
              drawn.incrementAndGet()
              throw IllegalStateException(REASON)
            },
          ),
        )
      }
  }

  private fun fixture(): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$LANDSCAPE"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val LANDSCAPE = "apple_export_a.mp4"
    const val REASON = "The picture is too large to draw at its colour."
    const val FRAME_RATE = 30
    val CLIP = 2.seconds
    val TIMEOUT = 2.seconds * 60
  }
}
