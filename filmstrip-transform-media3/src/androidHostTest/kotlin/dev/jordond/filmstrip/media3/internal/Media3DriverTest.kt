package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * What [Media3Driver.syncSampleAtOrBefore] answers for a source the planner already knows carries
 * no video track.
 *
 * The planner reads a null answer as "this trim blocks the copy", so an exception here would take
 * down the whole plan rather than only the copy this one clip asked for.
 */
class Media3DriverTest {
  @OptIn(ExperimentalFilmstripApi::class)
  @Test
  fun `a still reports no sync sample rather than throwing`() =
    runTest {
      val driver = Media3Driver(prober = MediaProber { error("a still's sync sample is answered without probing") })
      val still = MediaSource.Image(ImageSource.Path("poster.jpg"), duration = 2.seconds)

      assertNull(driver.syncSampleAtOrBefore(still, cut = 1.seconds))
    }
}
