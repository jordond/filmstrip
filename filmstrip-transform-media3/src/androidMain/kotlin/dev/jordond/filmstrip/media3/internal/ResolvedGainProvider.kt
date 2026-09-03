package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.GainProcessor
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gain media3 asks for at each frame of a clip, read straight off the clip's resolved curve.
 *
 * `GainProcessor` calls this once per frame and multiplies that frame by what comes back, so a fade
 * lands at frame resolution instead of being cut into steps. The positions it counts are frames
 * since the last flush, read here as the clip's own time, which is the time a [ResolvedGain] is
 * built in.
 *
 * `DefaultGainProvider` is not used to build this. Its builder layers fade shapes over a range map
 * that a resolved curve has no need for, and it documents its factors as `[0f, 1f]` where a caller
 * may ask for more.
 *
 * A factor above one is handed back as it stands. media3's 16-bit path computes
 * `(short) (sample * gain)`, which wraps rather than clipping, so boosting a clip that already runs
 * near full scale distorts. The scaled [androidx.media3.common.audio.ChannelMixingMatrix] a
 * constant gain rides on behaves the same way, so a ramp through a factor distorts exactly where a
 * constant at that factor would have.
 */
internal class ResolvedGainProvider(
  private val gain: ResolvedGain,
) : GainProcessor.GainProvider {
  override fun getGainFactorAtSamplePosition(
    samplePosition: Long,
    sampleRate: Int,
  ): Float = gain.gainAt(timeOf(samplePosition, sampleRate))

  /**
   * The first frame after [samplePosition] that media3 may not copy through untouched, or
   * [C.TIME_END_OF_SOURCE] when the curve holds unity for the rest of the stream.
   *
   * media3 throws when this answers [C.TIME_UNSET] for a frame whose gain is one, and it spins
   * forever when the boundary is not past [samplePosition], so both are settled here rather than
   * left to the curve. The boundary lands on the last frame that starts before the curve leaves
   * unity, which can fall one frame short of the run but never covers a frame that needs scaling.
   */
  override fun isUnityUntil(
    samplePosition: Long,
    sampleRate: Int,
  ): Long {
    val from = timeOf(samplePosition, sampleRate)
    if (gain.gainAt(from) != UNITY) return C.TIME_UNSET

    val leaves = unityEndAfter(from) ?: return C.TIME_END_OF_SOURCE
    return maxOf(samplePosition + 1, framesBefore(leaves, sampleRate))
  }

  // Where the curve stops holding one, at or after [from], or null when it holds it to the end of
  // the stream. A segment only holds one value across its whole span when it is flat, so the answer
  // is always the start of a run and never a point inside one.
  private fun unityEndAfter(from: Duration): Duration? {
    val leaves = gain.segments.firstOrNull { it.end > from && (!it.isFlat || it.startGain != UNITY) }
    if (leaves != null) return maxOf(from, leaves.start)

    val last = gain.segments.last()
    return if (last.endGain == UNITY) null else last.end
  }
}

/**
 * The processor that walks [gain] frame by frame, or null when the curve holds one number and a
 * scaled mixing matrix carries it instead.
 *
 * `internal` rather than private so a test can make this call without building the mixer that goes
 * with it, which stores its matrices in an `android.util.SparseArray` and so only runs on device.
 */
internal fun rampProcessorFor(gain: ResolvedGain): GainProcessor? =
  if (gain.isConstant) null else GainProcessor(ResolvedGainProvider(gain))

// The clip time a frame position sits at, split at the second so a long stream cannot overflow the
// nanosecond conversion. media3 seeds its frame counter from StreamMetadata.positionOffsetUs on
// every flush, and whether that offset is clip-relative once an item carries a trim is not
// something the media3 sources settle. A resolved curve is in the clip's own time, so that is what
// is read here.
private fun timeOf(
  frames: Long,
  sampleRate: Int,
): Duration = (frames / sampleRate).seconds + ((frames % sampleRate) * NANOS_PER_SECOND / sampleRate).nanoseconds

// The last frame position that starts before [time], which is where a unity run has to stop for
// every frame in it to be unity.
private fun framesBefore(
  time: Duration,
  sampleRate: Int,
): Long {
  val whole = time.inWholeSeconds
  return whole * sampleRate + (time - whole.seconds).inWholeNanoseconds * sampleRate / NANOS_PER_SECOND
}

private const val UNITY = 1f
private const val NANOS_PER_SECOND = 1_000_000_000L
