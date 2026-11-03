package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.writer.refusesFallbackEncode
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.test.Test

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
  fun `a graded copy the writer cannot describe is refused rather than re-encoded`() {
    refusesFallbackEncode(ExportPath.Copy, canCopy = false, transfer = HdrTransfer.Pq).shouldBeTrue()
    refusesFallbackEncode(ExportPath.Copy, canCopy = false, transfer = HdrTransfer.Hlg).shouldBeTrue()
  }

  // An SDR copy re-encodes to the same picture, so there is nothing to refuse.
  @Test
  fun `an sdr copy the writer cannot describe falls back to an encode`() {
    refusesFallbackEncode(ExportPath.Copy, canCopy = false, transfer = null).shouldBeFalse()
  }

  @Test
  fun `a copy the writer can describe is never refused`() {
    refusesFallbackEncode(ExportPath.Copy, canCopy = true, transfer = HdrTransfer.Pq).shouldBeFalse()
  }

  // An encode was never promised a copy, so a graded one is the ordinary HDR path rather than a
  // fallback off one.
  @Test
  fun `a graded encode is not a fallback and is left alone`() {
    refusesFallbackEncode(ExportPath.Transcode, canCopy = false, transfer = HdrTransfer.Pq).shouldBeFalse()
  }
}
