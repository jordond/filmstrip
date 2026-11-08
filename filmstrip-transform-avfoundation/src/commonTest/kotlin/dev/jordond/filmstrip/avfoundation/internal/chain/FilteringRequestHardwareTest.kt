package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.avfoundation.BAR_CENTRES
import dev.jordond.filmstrip.avfoundation.assertClose
import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.frameOf
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.videoCompositionWithAsset
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.Foundation.NSURL
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The frame a filtering request hands over and the image handed back, through a real filter handler on each target.
 *
 * The two targets type the request's image as different classes, so this runs the same handler on both and reads
 * what AVFoundation drew from what was handed back.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class FilteringRequestHardwareTest {
  @Test
  fun `the source frame is the decoded frame at the clip's own size`() {
    val seen = AtomicReference<List<Double>?>(null)

    frameThrough { request ->
      val source = request.sourceFrame()
      seen.store(source.rect())
      request.finish(source, context = null)
    }

    assertNear(withClue("the handler never ran") { seen.load().shouldNotBeNull() }, listOf(0.0, 0.0, WIDTH, HEIGHT))
  }

  @Test
  fun `a source frame handed straight back draws the clip unchanged`() {
    val passed = frameThrough { request -> request.finish(request.sourceFrame(), context = null) }
    val decoded = frameOf(fixture(CLIP), AT)

    BAR_CENTRES.forEach { x ->
      assertClose(passed.average(x, 0.5f), decoded.average(x, 0.5f), "the passed through frame at $x")
    }
  }

  @Test
  fun `an image finished without a context is what AVFoundation draws`() {
    val drawn = frameThrough { request -> request.finish(green(request), context = null) }

    assertClose(drawn.average(0.5f, 0.5f), GREEN, "the frame finished on AVFoundation's own context")
  }

  @Test
  fun `an image finished through a context of its own is what AVFoundation draws`() {
    val context = CIContext()
    val drawn = frameThrough { request -> request.finish(green(request), context) }

    assertClose(drawn.average(0.5f, 0.5f), GREEN, "the frame finished on the chain's context")
  }

  private fun frameThrough(handler: (AVAsynchronousCIImageFilteringRequest) -> Unit) =
    AVURLAsset(uRL = NSURL.fileURLWithPath(fixture(CLIP)), options = null).let { asset ->
      val composition = AVMutableVideoComposition.videoCompositionWithAsset(asset) { request -> request?.let(handler) }
      frameOf(asset, composition, AT)
    }

  private fun green(request: AVAsynchronousCIImageFilteringRequest): CIImage =
    CIImage(
      color = CIColor.colorWithRed(0.0, green = 1.0, blue = 0.0),
    ).imageByCroppingToRect(request.sourceFrame().extent)

  private companion object {
    const val CLIP = "apple_export_a.mp4"
    const val WIDTH = 640.0
    const val HEIGHT = 360.0
    val AT = 1.seconds
    val GREEN = Triple(0, 255, 0)
  }
}
