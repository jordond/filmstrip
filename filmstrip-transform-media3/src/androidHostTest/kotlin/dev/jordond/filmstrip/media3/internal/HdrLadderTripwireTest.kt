package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the assumption that DEFAULT_HDR_LADDER holds exactly one codec.
 *
 * Two backends pair a ladder-derived codec with a profile constant they name themselves.
 * Media3Encoders.anyHdrEncoder() asks for HEVCProfileMain10 against MIMETYPE_VIDEO_HEVC, and the
 * AVFoundation probe in AvEncoders.opensMain10Session() sets kVTProfileLevel_HEVC_Main10_AutoLevel
 * on whichever codec type the ladder named. Both are right while HEVC is the only entry and both
 * answer for the wrong codec the moment it is not.
 */
class HdrLadderTripwireTest {
  @Test
  fun `DEFAULT_HDR_LADDER is exactly Hevc only`() {
    assertEquals(
      listOf(VideoCodec.Hevc),
      DEFAULT_HDR_LADDER,
      "DEFAULT_HDR_LADDER changed. Media3Encoders.anyHdrEncoder() and " +
        "AvEncoders.opensMain10Session() both hardcode an HEVC Main10 profile against a codec the " +
        "ladder picks, so both answer for the wrong codec now. Map each ladder entry to its own " +
        "profile in both backends before adding a second codec.",
    )
  }
}
