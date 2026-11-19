package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.ffmpeg.internal.lowering.hdrPaintNodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.ffmpeg.internal.lowering.solidInput
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.solidMediaInfoOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SolidInputTest {
  @Test
  fun `an sdr solid is drawn by color in its own hex`() {
    solidInput(SOLID, clipOf(SOLID), FRAME_RATE, transfer = null).description shouldBe
      "color=c=0x308020ff:s=64x36:r=25:d=1.040000"
  }

  @Test
  fun `a translucent sdr solid is read as rgba`() {
    solidInput(TRANSLUCENT, clipOf(TRANSLUCENT), FRAME_RATE, transfer = null).description shouldBe
      "color=c=0x30802080:s=64x36:r=25:d=1.040000,format=rgba"
  }

  // The fill's flatten paints through the same hdrPaintNodes, so a solid and a fill of one color land on one code.
  @Test
  fun `a solid on a kept grade is repainted by the shared hdr paint`() {
    for (transfer in HdrTransfer.entries) {
      for (solid in listOf(SOLID, TRANSLUCENT)) {
        solidInput(solid, clipOf(solid), FRAME_RATE, transfer).description shouldBe
          "color=s=64x36:r=25:d=1.040000," + hdrPaintNodes(solid.color, transfer).render()
      }
    }
  }

  private fun clipOf(solid: MediaSource.Solid): ResolvedClip =
    ResolvedClip(
      source = solid,
      info = solidMediaInfoOf(solid, Size(64, 36)),
      start = Duration.ZERO,
      end = solid.duration,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, solid.duration),
      startsAtKeyFrame = true,
      span = TimeRange.of(Duration.ZERO, solid.duration),
      sourceIndex = 0,
    )

  private companion object {
    const val FRAME_RATE = 25
    val SOLID = MediaSource.Solid(0xFF308020.toInt(), 1.seconds)
    val TRANSLUCENT = MediaSource.Solid(0x80308020.toInt(), 1.seconds)
  }
}
