package dev.jordond.filmstrip.media3.internal.encoder

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An [EncoderColorProbe] that runs [measure] on a thread of its own and keeps each answer it returns for the process.
 *
 * [measure] gets one deadline [timeoutNanos] from the start and every wait inside it is bounded by that deadline. When
 * the deadline passes with [measure] still running, its thread is interrupted, which it reads as a signal to stop and
 * release what it holds. The caller then waits up to [graceNanos] more for it to return, and the answer is a
 * [WrittenColor.Unclear] that is not kept.
 *
 * Callers asking the same request while it is measured share that one measurement, each waiting for it until the
 * deadline in a wait an interrupt ends. A caller interrupted there gets the [InterruptedException], and the
 * measurement goes on for the rest. A [measure] that throws an [Exception] answers [WrittenColor.Unclear] and is not
 * kept, and neither is an unclear answer [measure] marks as not [WrittenColor.Unclear.kept], so the next caller
 * measures again. Every other answer is kept.
 *
 * @param timeoutNanos How long one measurement may take.
 * @param graceNanos How long a timed out measurement is waited for once interrupted.
 * @param measure Measures the color one request writes before the deadline it is given, in [System.nanoTime] terms.
 */
internal class MeasuredEncoderColor(
  private val timeoutNanos: Long,
  private val graceNanos: Long = GRACE_NANOS,
  private val measure: (request: EncoderColorRequest, deadline: Long) -> WrittenColor,
) : EncoderColorProbe {
  private val answers = ConcurrentHashMap<EncoderColorRequest, WrittenColor>()
  private val running = ConcurrentHashMap<EncoderColorRequest, Measurement>()

  override fun written(request: EncoderColorRequest): WrittenColor {
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
    private val request: EncoderColorRequest,
  ) {
    private val deadline = System.nanoTime() + timeoutNanos
    private val tookTooLong =
      WrittenColor.Unclear.outOfTime(
        "measuring it took longer than ${TimeUnit.NANOSECONDS.toMillis(timeoutNanos)} ms",
      )
    private val task =
      object : FutureTask<WrittenColor>({ measure(request, deadline) }) {
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

    fun await(): WrittenColor =
      try {
        answerWithin(deadline - System.nanoTime())
      } catch (_: TimeoutException) {
        task.cancel(true)
        if (task.isCancelled) released() else answerWithin(0)
      } catch (_: CancellationException) {
        released()
      }

    private fun answerWithin(nanos: Long): WrittenColor =
      try {
        task.get(nanos, TimeUnit.NANOSECONDS)
      } catch (thrown: ExecutionException) {
        val cause = thrown.cause
        if (cause !is Exception) throw cause ?: thrown
        WrittenColor.Unclear("measuring it threw $cause")
      }

    private fun released(): WrittenColor {
      thread.join(TimeUnit.NANOSECONDS.toMillis(graceNanos) + 1)
      return tookTooLong
    }
  }

  private val WrittenColor.isKept: Boolean
    get() = this !is WrittenColor.Unclear || kept

  companion object {
    /**
     * The probe every export shares, measuring on the device's own encoders within [TIMEOUT_NANOS].
     */
    val Device: MeasuredEncoderColor = MeasuredEncoderColor(TIMEOUT_NANOS, measure = ::measureOnDevice)
  }
}

private const val THREAD_NAME = "filmstrip-encoder-color"
private val TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5)
private val GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(500)
