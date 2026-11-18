package dev.jordond.filmstrip.media3.internal.encoder

import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * When [keyFrameReport] says an export's key frames sit further apart than its plan placed them.
 */
class KeyFrameLimitTest {
  @Test
  fun `an export on the omx encoder reports the spacing it asked for and the one written`() {
    val adjustment = keyFrameReport(ExportPath.Transcode, OMX_H264_ENCODER, SPACING).shouldNotBeNull()

    adjustment.kind shouldBe AdjustmentKind.KeyFrameIntervalClamped
    adjustment.requested shouldBe "every $SPACING frames"
    adjustment.resolved shouldBe "every $OMX_IDR_SPACING frames"
    adjustment.message shouldContain OMX_H264_ENCODER
  }

  @Test
  fun `a spacing the omx encoder already meets reports nothing`() {
    keyFrameReport(ExportPath.Transcode, OMX_H264_ENCODER, OMX_IDR_SPACING).shouldBeNull()
  }

  @Test
  fun `another encoder, a copy or no spacing reports nothing`() {
    keyFrameReport(ExportPath.Transcode, "c2.android.avc.encoder", SPACING).shouldBeNull()
    keyFrameReport(ExportPath.Copy, OMX_H264_ENCODER, SPACING).shouldBeNull()
    keyFrameReport(ExportPath.Transcode, null, SPACING).shouldBeNull()
    keyFrameReport(ExportPath.Transcode, OMX_H264_ENCODER, null).shouldBeNull()
  }

  private companion object {
    const val SPACING = 30
  }
}
