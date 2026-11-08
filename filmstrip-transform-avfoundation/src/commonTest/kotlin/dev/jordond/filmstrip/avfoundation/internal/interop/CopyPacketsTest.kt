package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.readerPacketsOf
import dev.jordond.filmstrip.transform.internal.copy.COPY_PACKET_MARGIN
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The samples [copyPacketsWithin] steps a cursor over, against every sample an asset reader hands back for the same
 * track in decode order.
 */
@OptIn(ExperimentalForeignApi::class)
class CopyPacketsTest {
  // A window opening past the fixture's second sync sample, so the walk has to step back to it rather than start on the
  // track's first sample.
  @Test
  fun `walks the same samples as a reader from the opening to past the margin`() =
    runTest {
      val track = videoTrackOf(fixture(LONG_FIXTURE))
      val reference = readerPacketsOf(track)
      val opening = reference.filter { it.key }.map { it.pts }.sorted()[1]

      val walked = track.copyPacketsWithin(opening + PAST_OPENING, opening + WINDOW).shouldNotBeNull()

      val opens = reference.indexOfFirst { it.key && it.pts == opening }
      walked.map { it.pts to it.key } shouldBe reference.subList(opens, opens + walked.size).map { it.pts to it.key }
      withClue("the walk stopped at ${walked.last().pts}, short of the margin") {
        (walked.last().pts >= opening + WINDOW + COPY_PACKET_MARGIN) shouldBe true
      }
    }

  @Test
  fun `refuses a walk past more samples than its cap`() =
    runTest {
      val track = videoTrackOf(fixture(LONG_FIXTURE))

      val refused = assertFailsWith<AvLoweringFailure> { track.copyPacketsWithin(Duration.ZERO, WINDOW, SMALL_CAP) }

      refused.reason shouldContain "more than $SMALL_CAP video samples"
    }

  private fun videoTrackOf(path: String): AVAssetTrack =
    AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
      .tracksWithMediaType(AVMediaTypeVideo)
      .first() as AVAssetTrack

  private companion object {
    const val LONG_FIXTURE = "apple_export_long.mp4"
    const val SMALL_CAP = 5
    val PAST_OPENING = 100.milliseconds
    val WINDOW = 1_500.milliseconds
  }
}
