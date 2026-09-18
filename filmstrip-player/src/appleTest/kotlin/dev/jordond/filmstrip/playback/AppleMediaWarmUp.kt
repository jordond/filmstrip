package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import kotlin.test.fail
import kotlin.time.Duration

/**
 * Takes one pass through each AVFoundation and VideoToolbox path the Apple suites use, on the fixture clip they use.
 *
 * The clip is resolved through the export engine, which probes it and asks VideoToolbox what it can encode. Its
 * lowering is then read through the export reader to a probe position, and drawn once at that position through an
 * image generator. The draw is left unbounded, and the harness bounds the warm-up as a whole.
 */
internal suspend fun warmUpAppleMediaStack() {
  val resolved = appleExportLowering(appleFixtureComposition())
  val av = resolved.toAvComposition()
  val filter = av.videoComposition ?: fail("the export lowered the fixture without a video composition")
  val position = PROBE_POSITIONS.first()
  av.composition.readFrame(filter, resolved.duration, position)
  av.composition.generateFrame(filter, position, timeout = Duration.INFINITE)
}
