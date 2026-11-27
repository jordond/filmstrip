package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The [dev.jordond.filmstrip.FilmstripError.TimedOut.operation] each short read names when it is stopped at its
 * deadline.
 */
@InternalFilmstripApi
public object ProbeOperation {
  public const val SOURCE_PROBE: String = "source probe"
  public const val OUTPUT_PROBE: String = "output probe"
  public const val COLOR_READ: String = "color read"
  public const val SYNC_SAMPLE_READ: String = "sync sample read"
  public const val FIRST_FRAME_DECODE: String = "first frame decode"
  public const val COPY_PACKET_READ: String = "copy packet read"
  public const val ENCODER_TEST: String = "encoder test encode"
  public const val PICTURE_DECODE: String = "overlay picture decode"
  public const val DECODER_START: String = "decoder start"
  public const val TEN_BIT_READ: String = "ten-bit read"
}

/**
 * How long a short probe read may run before it is stopped.
 *
 * Every read of a source made while probing or planning gets this long, on every backend.
 */
@InternalFilmstripApi
public val PROBE_DEADLINE_BASE: Duration = 30.seconds
