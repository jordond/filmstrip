package dev.jordond.filmstrip.player.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long one `AVAssetImageGenerator` may stay silent on a readback before [AvFrameReadback] gives
 * up on it.
 *
 * This is AVFoundation's limit, not a shared quantity. Its generator can build a decode session,
 * ask for the sample and then never answer or fail. One retry and the failure after it both land
 * within twice this deadline.
 */
internal val AV_READBACK_DEADLINE: Duration = 5.seconds

/**
 * The failure a readback reports when the first generator and its replacement both stayed silent
 * for [deadline] at [position].
 */
internal fun stalledReadbackMessage(
  position: Duration,
  deadline: Duration,
): String = "The frame generator gave no frame at $position within $deadline, twice, so the readback failed."
