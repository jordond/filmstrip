package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.thumbnail.AvThumbnailPlanner
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration

/**
 * A buffer the preview and the thumbnails can't write to a file reads as unreadable, the way it does on every backend.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleBytesRootFailureTest {
  private val blockedRoot =
    NSTemporaryDirectory() + "filmstrip-player-blocked-root-" + NSProcessInfo.processInfo.globallyUniqueString

  init {
    pumpMainRunLoopDuringContracts()
    NSFileManager.defaultManager.createFileAtPath(blockedRoot, null, null) shouldBe true
  }

  @AfterTest
  fun removeBlockedRoot() {
    NSFileManager.defaultManager.removeItemAtPath(blockedRoot, null)
  }

  @Test
  fun `a preview of a buffer that cannot be written fails as unreadable`() =
    contractTest { scope ->
      val engine =
        AvPlayerEngine(
          scope,
          AvPreviewPlanner(CONTRACT_COMPONENTS),
          PlayerConfig(),
          muted = true,
          bytesRoot = blockedRoot,
        )
      withEngine(engine) {
        val failed =
          engine
            .awaitComposition(appleFixtureBytesComposition())
            .shouldBeInstanceOf<SetCompositionResult.Failure>()

        failed.error.shouldBeInstanceOf<PlaybackError.SourceUnreadable>().message shouldContain "temporary file"
      }
    }

  @Test
  fun `a thumbnail of a buffer that cannot be written is refused as unreadable`() =
    contractTest {
      val request =
        ThumbnailRequest(
          composition = appleFixtureBytesComposition(),
          position = Duration.ZERO,
          heightPx = FIXTURE_FRAME.height,
          effectsRevision = 0,
          precise = true,
        )

      val refused =
        AvThumbnailPlanner(CONTRACT_COMPONENTS, blockedRoot)
          .lower(request)
          .shouldBeInstanceOf<PlanResult.Refused<FilmstripError>>()

      refused.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldContain "temporary file"
    }
}
