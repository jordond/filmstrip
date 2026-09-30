package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.test.fail

/**
 * True when this result reports an encoder that can write HDR on this device.
 *
 * A [CapabilitiesResult.Failure] fails the calling test instead of reading as false, since a backend that cannot answer
 * the query is broken rather than missing an HDR encoder.
 */
@InternalFilmstripApi
public fun CapabilitiesResult.encodesHdr(): Boolean =
  when (this) {
    is CapabilitiesResult.Success -> capabilities.supportsHdrEncoding
    is CapabilitiesResult.Failure -> fail("the capabilities query failed: $error")
  }

/**
 * True when this [Filmstrip]'s backend reports an encoder that can write HDR on this device.
 *
 * Asks for capabilities once and reads the answer through [CapabilitiesResult.encodesHdr], so a failed query fails the
 * calling test.
 */
@InternalFilmstripApi
public suspend fun Filmstrip.encodesHdr(): Boolean = capabilities().encodesHdr()
