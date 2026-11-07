package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import dev.jordond.filmstrip.edit.outputLengthOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Puts a clip's frames on the composition's clock before the chain behind it draws them.
 *
 * A frame reader decodes one media item on its own, so the timestamps reaching its effects run from
 * the start of that item. The chain it runs was lowered against the composition's timeline, where
 * media3 offsets an item's frames by where it sits before any effect sees them, and an effect that
 * travels over a span reads that time to decide what to draw. Adding [offset] at the head of a
 * reader's chain is what makes both readings the same number.
 *
 * The frame is handed straight on, so this costs a link in the chain and no drawing. Every
 * timestamp downstream carries the offset, the one a reader reports back included.
 *
 * @property offset Where the clip starts on the composition's timeline.
 * @property speed How fast the clip plays, since a reader's frames are measured in source time.
 */
internal class CompositionClock(
  private val offset: Duration,
  private val speed: Double = 1.0,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = CompositionClockShaderProgram(offset.inWholeMicroseconds, speed)
}

/**
 * Restamps each frame and forwards the texture it arrived on.
 *
 * Everything but the timestamp is the pass-through media3 already ships, which owns no texture of
 * its own and releases and flushes as the chain around it expects.
 */
private class CompositionClockShaderProgram(
  private val offsetUs: Long,
  private val speed: Double,
) : PassthroughShaderProgram() {
  override fun queueInputFrame(
    glObjectsProvider: GlObjectsProvider,
    inputTexture: GlTextureInfo,
    presentationTimeUs: Long,
  ) {
    val played = outputLengthOf(presentationTimeUs.microseconds, speed).inWholeMicroseconds
    super.queueInputFrame(glObjectsProvider, inputTexture, played + offsetUs)
  }
}
