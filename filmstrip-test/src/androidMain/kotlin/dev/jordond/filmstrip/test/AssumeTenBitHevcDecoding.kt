package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import org.junit.Assume.assumeTrue

/**
 * Skips the calling test when [decodesTenBitHevc] is false, so a device that cannot read the HDR fixtures reports
 * the test as skipped rather than passed. Every emulator image is one of these.
 *
 * The skip throws JUnit 4's `AssumptionViolatedException`, so JUnit 4 has to be on the runtime classpath. An Android
 * instrumentation test classpath already has it.
 */
@InternalFilmstripApi
public fun assumeDecodesTenBitHevc() {
  assumeTrue("this device has no ten-bit HEVC decoder", decodesTenBitHevc())
}
