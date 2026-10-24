package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import dev.jordond.filmstrip.media.rgbMatrix
import dev.jordond.filmstrip.media.ycbcrReading
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the colour an encoder writes when it is asked for one.
 *
 * An encoder fed RGB through its input surface picks the matrix and range it converts with, and not every encoder
 * picks the ones it was configured with.
 */
internal fun interface EncoderColourProbe {
  /**
   * The colour the encoder [request] names writes when asked for [EncoderColourRequest.requested], or why that could
   * not be told.
   */
  fun written(request: EncoderColourRequest): WrittenColour
}

/**
 * One encoder at one frame size tier, asked for one colour.
 *
 * @property encoder The encoder's codec name.
 * @property mimeType The MIME type it encodes.
 * @property tier The size tier of the frames the export encodes.
 * @property requested The colour media3 asks it for.
 */
internal data class EncoderColourRequest(
  val encoder: String,
  val mimeType: String,
  val tier: SizeTier,
  val requested: ColorInfo,
)

/**
 * A band of frame sizes measured as one, at the largest size in the band the encoder takes.
 *
 * A frame belongs to the first tier whose [width] its longer side and whose [height] its shorter side fit within, so a
 * portrait frame shares the tier of the landscape one it turns into. Every frame past [HD] belongs to [UHD].
 *
 * @property width The largest width in the tier.
 * @property height The largest height in the tier.
 */
internal enum class SizeTier(
  val width: Int,
  val height: Int,
) {
  SD(720, 576),
  HD(1920, 1080),
  UHD(3840, 2160),
  ;

  companion object {
    /**
     * The tier a frame belongs to, comparing its longer side to each tier's width and its shorter side to its height.
     *
     * @param width The frame's width.
     * @param height The frame's height.
     */
    fun of(
      width: Int,
      height: Int,
    ): SizeTier {
      val longer = max(width, height)
      val shorter = min(width, height)
      return entries.firstOrNull { longer <= it.width && shorter <= it.height } ?: UHD
    }
  }
}

/**
 * What an [EncoderColourProbe] found an encoder writes.
 */
internal sealed interface WrittenColour {
  /**
   * The encoder was measured writing [colour].
   */
  data class Measured(
    val colour: ColorInfo,
  ) : WrittenColour

  /**
   * The colour could not be told, for [reason].
   *
   * @property reason Why the colour could not be told.
   * @property kept Whether [MeasuredEncoderColour] keeps the answer for the process. An answer cut short by a deadline
   * is not kept, so the next caller measures again.
   */
  data class Unclear(
    val reason: String,
    val kept: Boolean = true,
  ) : WrittenColour {
    companion object {
      /**
       * The answer for a measurement the deadline cut short before it got as far as [reason] says, which is not kept.
       *
       * @param reason How far the measurement got.
       */
      fun outOfTime(reason: String): Unclear = Unclear(reason, kept = false)
    }
  }
}

/**
 * An [EncoderColourProbe] that runs [measure] on a thread of its own and keeps each answer it returns for the process.
 *
 * [measure] gets one deadline [timeoutNanos] from the start and every wait inside it is bounded by that deadline. When
 * the deadline passes with [measure] still running, its thread is interrupted, which it reads as a signal to stop and
 * release what it holds. The caller then waits up to [graceNanos] more for it to return, and the answer is a
 * [WrittenColour.Unclear] that is not kept.
 *
 * Callers asking the same request while it is measured share that one measurement, each waiting for it until the
 * deadline in a wait an interrupt ends. A caller interrupted there gets the [InterruptedException], and the
 * measurement goes on for the rest. A [measure] that throws an [Exception] answers [WrittenColour.Unclear] and is not
 * kept, and neither is an unclear answer [measure] marks as not [WrittenColour.Unclear.kept], so the next caller
 * measures again. Every other answer is kept.
 *
 * @param timeoutNanos How long one measurement may take.
 * @param graceNanos How long a timed out measurement is waited for once interrupted.
 * @param measure Measures the colour one request writes before the deadline it is given, in [System.nanoTime] terms.
 */
internal class MeasuredEncoderColour(
  private val timeoutNanos: Long,
  private val graceNanos: Long = GRACE_NANOS,
  private val measure: (request: EncoderColourRequest, deadline: Long) -> WrittenColour,
) : EncoderColourProbe {
  private val answers = ConcurrentHashMap<EncoderColourRequest, WrittenColour>()
  private val running = ConcurrentHashMap<EncoderColourRequest, Measurement>()

  override fun written(request: EncoderColourRequest): WrittenColour {
    answers[request]?.let { return it }
    val started = Measurement(request)
    val shared = running.putIfAbsent(request, started)
    if (shared != null) return shared.await()
    answers[request]?.let {
      running.remove(request, started)
      return it
    }
    started.start()
    return started.await()
  }

  private inner class Measurement(
    private val request: EncoderColourRequest,
  ) {
    private val deadline = System.nanoTime() + timeoutNanos
    private val tookTooLong =
      WrittenColour.Unclear.outOfTime(
        "measuring it took longer than ${TimeUnit.NANOSECONDS.toMillis(timeoutNanos)} ms",
      )
    private val task =
      object : FutureTask<WrittenColour>({ measure(request, deadline) }) {
        override fun done() {
          val answer =
            if (isCancelled) {
              tookTooLong
            } else {
              try {
                get()
              } catch (_: ExecutionException) {
                null
              }
            }
          if (answer != null && answer.isKept) answers[request] = answer
          running.remove(request, this@Measurement)
        }
      }
    private val thread = Thread(task, THREAD_NAME)

    fun start() = thread.start()

    fun await(): WrittenColour =
      try {
        answerWithin(deadline - System.nanoTime())
      } catch (_: TimeoutException) {
        task.cancel(true)
        if (task.isCancelled) released() else answerWithin(0)
      } catch (_: CancellationException) {
        released()
      }

    private fun answerWithin(nanos: Long): WrittenColour =
      try {
        task.get(nanos, TimeUnit.NANOSECONDS)
      } catch (thrown: ExecutionException) {
        val cause = thrown.cause
        if (cause !is Exception) throw cause ?: thrown
        WrittenColour.Unclear("measuring it threw $cause")
      }

    private fun released(): WrittenColour {
      thread.join(TimeUnit.NANOSECONDS.toMillis(graceNanos) + 1)
      return tookTooLong
    }
  }

  private val WrittenColour.isKept: Boolean
    get() = this !is WrittenColour.Unclear || kept

  companion object {
    /**
     * The probe every export shares, measuring on the device's own encoders within [TIMEOUT_NANOS].
     */
    val Device: MeasuredEncoderColour = MeasuredEncoderColour(TIMEOUT_NANOS, measure = ::measureOnDevice)
  }
}

/**
 * The colour among BT.601 and BT.709 at limited and full range whose reading turns [codes] nearest full green, with
 * [requested]'s transfer, or null when even that reading lands further than [GREEN_TOLERANCE] from it.
 *
 * Each candidate reads [codes] through core's H.273 matrix for code 6 or 1 at its range, without clamping the RGB it
 * gives.
 *
 * @param codes The eight-bit Y, Cb and Cr an encoder wrote for full green.
 */
internal fun nearestColour(
  codes: Triple<Int, Int, Int>,
  requested: ColorInfo,
): ColorInfo? {
  val (nearest, distance) =
    CANDIDATES
      .map { candidate -> candidate to fromGreen(codes, candidate) }
      .minBy { it.second }
  if (distance > GREEN_TOLERANCE) return null
  return requested
    .buildUpon()
    .setColorSpace(nearest.colorSpace)
    .setColorRange(nearest.colorRange)
    .build()
}

/**
 * How far [codes] read through [candidate]'s matrix and range land from full green, as the largest of the three
 * channels' distances on a zero to one scale.
 */
internal fun fromGreen(
  codes: Triple<Int, Int, Int>,
  candidate: GreenCandidate,
): Double {
  val matrix = if (candidate.colorSpace == C.COLOR_SPACE_BT601) BT601_MATRIX else BT709_MATRIX
  val reading = ycbcrReading(matrix, UNSPECIFIED, UNSPECIFIED)
  val decode = checkNotNull(reading.rgbMatrix(EIGHT_BIT, candidate.colorRange == C.COLOR_RANGE_FULL))
  val (red, green, blue) = decode.rgb(codes.first.toDouble(), codes.second.toDouble(), codes.third.toDouble())
  return maxOf(abs(red), abs(green - 1.0), abs(blue))
}

/**
 * One matrix and range [nearestColour] weighs.
 *
 * @property colorSpace media3's colour space, BT.601 or BT.709.
 * @property colorRange media3's colour range, limited or full.
 */
internal data class GreenCandidate(
  val colorSpace: Int,
  val colorRange: Int,
)

/**
 * BT.601 and BT.709, each at limited and full range.
 */
internal val CANDIDATES: List<GreenCandidate> =
  listOf(C.COLOR_SPACE_BT601, C.COLOR_SPACE_BT709).flatMap { space ->
    listOf(C.COLOR_RANGE_LIMITED, C.COLOR_RANGE_FULL).map { range -> GreenCandidate(space, range) }
  }

// The nearest wrong reading of any candidate's green lands about 0.068 away. Two codes of noise on each channel move
// a right reading less than 0.031.
internal const val GREEN_TOLERANCE = 0.034

private const val THREAD_NAME = "filmstrip-encoder-colour"
private val TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5)
private val GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(500)
private const val BT709_MATRIX = 1
private const val BT601_MATRIX = 6
private const val UNSPECIFIED = 2
private const val EIGHT_BIT = 8
