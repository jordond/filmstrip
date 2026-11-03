package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlShaderProgram
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The link at the head of a reader's chain that moves each frame onto the composition's clock.
 */
class CompositionClockTest {
  @Test
  fun `a frame is restamped by where its clip starts`() {
    val forwarded = forwardedThrough(CompositionClock(1_500.milliseconds), 40_000L)

    forwarded.single().second shouldBe 1_540_000L
  }

  @Test
  fun `every frame of a clip moves by the same offset`() {
    val forwarded = forwardedThrough(CompositionClock(2.seconds), 0L, 33_333L, 66_666L)

    forwarded.map { it.second } shouldBe listOf(2_000_000L, 2_033_333L, 2_066_666L)
  }

  @Test
  fun `a clip at the start of the timeline keeps its own timestamps`() {
    val forwarded = forwardedThrough(CompositionClock(Duration.ZERO), 250_000L)

    forwarded.single().second shouldBe 250_000L
  }

  @Test
  fun `the texture is handed on as it arrived`() {
    val forwarded = forwardedThrough(CompositionClock(1.seconds), 0L)

    forwarded.single().first shouldBeSameInstanceAs TEXTURE
  }

  @Test
  fun `an hdr chain is restamped the same way`() {
    val program = CompositionClock(500.milliseconds).toGlShaderProgram(mockk<Context>(), useHdr = true)

    forwardedBy(program, 10_000L).single().second shouldBe 510_000L
  }

  private fun forwardedThrough(
    clock: CompositionClock,
    vararg presentationTimeUs: Long,
  ): List<Pair<GlTextureInfo, Long>> =
    forwardedBy(clock.toGlShaderProgram(mockk<Context>(), useHdr = false), *presentationTimeUs)

  private fun forwardedBy(
    program: GlShaderProgram,
    vararg presentationTimeUs: Long,
  ): List<Pair<GlTextureInfo, Long>> {
    val forwarded = mutableListOf<Pair<GlTextureInfo, Long>>()
    program.setOutputListener(
      object : GlShaderProgram.OutputListener {
        override fun onOutputFrameAvailable(
          outputTexture: GlTextureInfo,
          presentationTimeUs: Long,
        ) {
          forwarded += outputTexture to presentationTimeUs
        }
      },
    )
    presentationTimeUs.forEach { program.queueInputFrame(mockk<GlObjectsProvider>(), TEXTURE, it) }
    return forwarded
  }

  private companion object {
    val TEXTURE = GlTextureInfo(7, -1, -1, 640, 360)
  }
}
