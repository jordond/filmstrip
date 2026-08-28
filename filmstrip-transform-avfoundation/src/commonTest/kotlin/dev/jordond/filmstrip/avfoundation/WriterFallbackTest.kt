package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.refusesFallbackEncode
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the writer does when a copy it was told to make turns out to be impossible.
 *
 * AVFoundation only learns whether it can describe a track well enough to copy it once the asset is
 * open, which is after the plan has already promised one.
 */
class WriterFallbackTest {
  // The device that reaches here with a grade is the one that reported it cannot encode HDR, since
  // a copy is what let the grade through in the first place.
  @Test
  fun `a graded copy that cannot pass through is refused rather than re-encoded`() {
    assertTrue(refusesFallbackEncode(ExportPath.Transmux, canPassThrough = false, transfer = HdrTransfer.Pq))
    assertTrue(refusesFallbackEncode(ExportPath.Transmux, canPassThrough = false, transfer = HdrTransfer.Hlg))
  }

  // An SDR copy re-encodes to the same picture, so there is nothing to refuse.
  @Test
  fun `an sdr copy that cannot pass through falls back to an encode`() {
    assertFalse(refusesFallbackEncode(ExportPath.Transmux, canPassThrough = false, transfer = null))
  }

  @Test
  fun `a copy that can pass through is never refused`() {
    assertFalse(refusesFallbackEncode(ExportPath.Transmux, canPassThrough = true, transfer = HdrTransfer.Pq))
  }

  // An encode was never promised a copy, so a graded one is the ordinary HDR path rather than a
  // fallback off one.
  @Test
  fun `a graded encode is not a fallback and is left alone`() {
    assertFalse(refusesFallbackEncode(ExportPath.Transcode, canPassThrough = false, transfer = HdrTransfer.Pq))
  }
}
