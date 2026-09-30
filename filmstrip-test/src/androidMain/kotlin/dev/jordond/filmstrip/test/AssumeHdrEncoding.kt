package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import org.junit.Assume.assumeTrue

/**
 * Skips the calling test when [encodesHdr] is false, since a test that keeps an HDR grade has nothing to write
 * with on that device.
 *
 * The skip throws JUnit 4's `AssumptionViolatedException`, so JUnit 4 has to be on the runtime classpath. An Android
 * instrumentation test classpath already has it.
 */
@InternalFilmstripApi
public suspend fun Filmstrip.assumeEncodesHdr() {
  assumeTrue("this device advertises no HDR encoder", encodesHdr())
}
