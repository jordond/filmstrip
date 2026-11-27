package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FfmpegEncoder
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegEncoderNamed
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeAnswer
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.EncoderAsk
import dev.jordond.filmstrip.transform.internal.HDR_PROBE_SIZE
import dev.jordond.filmstrip.transform.internal.RESOLUTION_LADDER
import dev.jordond.filmstrip.transform.internal.geometry.MeasuredFrame
import dev.jordond.filmstrip.transform.internal.negotiation.fittedBetween

/**
 * The device an export of this build is planned against, read as far as the plan needs and no further.
 *
 * [Settled] holds a device the plan can be negotiated against and come out the way the whole measurement would have
 * it. [Refused] holds the test encode the plan needed that was still running at its deadline.
 */
internal sealed interface PlannedDevice {
  class Settled(
    val device: DeviceCapabilities,
  ) : PlannedDevice

  class Refused(
    val error: FilmstripError,
  ) : PlannedDevice
}

/**
 * Every encoder this build carries, read as taking any frame, with nothing encoded to find out.
 *
 * This is where [EncoderReading] starts. The engine hands it to a negotiation only for an edit whose sources it cannot
 * open, which the lowering refuses before it reads the device.
 */
internal fun Toolchain.unmeasuredDevice(): DeviceCapabilities = EncoderReading(this).device()

/**
 * Reads this build's encoders for one export, only as far as the plans it is asked about need.
 *
 * Every encoder nothing has read yet takes any frame, and the HDR profile when the build carries an encoder an HDR
 * export would open. Which encoder a plan picks depends on the codecs the build carries and never on an encoder's
 * frame limits, so the first ask already names it. That encoder then has its smallest frame read and is asked again,
 * which settles the frame the plan asks it for.
 *
 * The smallest rung of RESOLUTION_LADDER that holds that frame stands in for the encoder's ceiling when the encoder
 * opens there, since a larger ceiling changes nothing about a frame that fits. It is only a lower bound, so an ask
 * reads it as no ceiling at all and a frame past it reads the encoder further. When the encoder does not open at that
 * rung, or no rung holds the frame, the whole ladder is walked for the real ceiling. A plan that keeps HDR reads the
 * encoder's ten-bit profile, and a plan whose encoder opens at no rung drops it and settles on the next one.
 *
 * A plan refused before it reaches an encoder is refused for the same reason by every device but one that answers HDR
 * differently, so only that answer is read for it. A test encode a plan needs that is still running at its deadline
 * refuses as [FilmstripError.TimedOut]. An encode no plan needs is never run.
 *
 * The answers are held for the life of the reading, so the plan an export falls back on is read on top of what its
 * own plan already read, and [revision] moves each time anything new is read.
 */
internal class EncoderReading(
  private val toolchain: Toolchain,
) {
  private val reads = mutableMapOf<String, EncoderRead>()
  private var supportsHdr: Boolean? = null

  /**
   * How many answers this reading has taken, so a caller can tell whether a [settle] read anything new.
   */
  var revision: Int = 0
    private set

  /**
   * The device every plan is negotiated against, with each ceiling read so far applied.
   */
  fun device(): DeviceCapabilities = deviceOf(asked = false)

  /**
   * Reads the encoders until what [askOf] asks of them is settled.
   *
   * @param readsHdrForRefusal Whether a plan [askOf] refuses reads the HDR answer, which a fallback that is not there
   * has no use for.
   * @param askOf What a plan asks of its encoder against a device, or null when it is refused before reaching one.
   */
  suspend fun settle(
    runtime: FfmpegRuntime,
    readsHdrForRefusal: Boolean = true,
    askOf: (DeviceCapabilities) -> EncoderAsk?,
  ): PlannedDevice {
    while (true) {
      val asking = deviceOf(asked = true)
      val ask = askOf(asking)
      val step =
        when {
          ask != null -> runtime.stepFor(ask)
          // Only the HDR answer can move a plan refused before it reaches an encoder.
          readsHdrForRefusal && supportsHdr == null && asking.supportsHdrEncoding -> runtime.hdrStep(asking)
          else -> Step.Settled
        }
      when (step) {
        is Step.Refused -> return PlannedDevice.Refused(step.error)
        Step.Read -> revision++
        Step.Settled -> return PlannedDevice.Settled(device())
      }
    }
  }

  private suspend fun FfmpegRuntime.stepFor(ask: EncoderAsk): Step {
    val encoder = ask.encoderName?.let(::ffmpegEncoderNamed) ?: return Step.Settled
    val read = readFor(encoder, ask.frame)
    if (read != Step.Settled) return read
    if (!ask.encodesHdr || supportsHdr != null) return Step.Settled
    // Read once the encoder is known to open, so the answer is the one the device it settles on gives.
    return hdrOf(encoder)
  }

  // The encoder an HDR export would open answers for the device, so it is read first and then asked.
  private suspend fun FfmpegRuntime.hdrStep(asking: DeviceCapabilities): Step {
    val encoder = checkNotNull(asking.video.hdrEncoder()) { NO_HDR_ENCODER }
    val read = readFor(encoder, HDR_PROBE_SIZE)
    return if (read != Step.Settled) read else hdrOf(encoder)
  }

  private suspend fun FfmpegRuntime.hdrOf(encoder: FfmpegEncoder): Step =
    when (val hdr = encodesHdr(toolchain, encoder)) {
      is ProbeAnswer.Expired -> Step.Refused(hdr.timedOut())
      is ProbeAnswer.Answered -> Step.Read.also { supportsHdr = hdr.value }
    }

  // Reads one more answer about encoder when frame needs one, or settles when it already has them all.
  private suspend fun FfmpegRuntime.readFor(
    encoder: FfmpegEncoder,
    frame: Size,
  ): Step {
    val floor =
      when (val read = reads[encoder.name]) {
        null -> {
          return when (val answer = floorOf(toolchain, encoder)) {
            is ProbeAnswer.Expired -> Step.Refused(answer.timedOut())
            is ProbeAnswer.Answered -> Step.Read.also { reads[encoder.name] = answer.value.floorRead() }
          }
        }
        EncoderRead.Absent -> {
          return Step.Settled
        }
        is EncoderRead.Floor -> {
          read.floor
        }
        is EncoderRead.Read -> {
          if (read.exact ||
            frame.liftedTo(read.floor).fits(read.ceiling)
          ) {
            return Step.Settled
          } else {
            read.floor
          }
        }
      }
    // A copy is not held to the floor, and the plan it falls back on lifts its frame there.
    return when (val answer = ceilingHolding(toolchain, encoder, frame.liftedTo(floor))) {
      is ProbeAnswer.Expired -> {
        Step.Refused(answer.timedOut())
      }
      is ProbeAnswer.Answered -> {
        reads[encoder.name] = answer.value?.let { (ceiling, exact) -> EncoderRead.Read(floor, ceiling, exact) }
          ?: EncoderRead.Absent
        Step.Read
      }
    }
  }

  // An ask reads a ceiling that is only a lower bound as no ceiling, so the frame it settles is never clamped by one.
  private fun deviceOf(asked: Boolean): DeviceCapabilities {
    val video =
      toolchain.videoEncoders().mapNotNull { (codec, encoder) ->
        when (val read = reads[encoder.name]) {
          null -> {
            capabilityOf(codec, encoder, ANY_FLOOR, ANY_CEILING)
          }
          EncoderRead.Absent -> {
            null
          }
          is EncoderRead.Floor -> {
            capabilityOf(codec, encoder, read.floor, ANY_CEILING)
          }
          is EncoderRead.Read -> {
            capabilityOf(codec, encoder, read.floor, if (asked && !read.exact) ANY_CEILING else read.ceiling)
          }
        }
      }
    return DeviceCapabilities(
      video = video,
      audio = toolchain.audioEncoders(),
      // Unread, this is what the encoder an HDR export would open could answer, so a build with none never keeps HDR.
      supportsHdrEncoding = supportsHdr ?: (video.hdrEncoder()?.hdrPixelFormat != null),
      concurrentSessionBudget = null,
    )
  }

  private fun Size.liftedTo(floor: Size): Size =
    checkNotNull(MeasuredFrame(this).fittedBetween(floor, null, ENCODER_SIZE_ALIGNMENT))

  private fun Size.fits(ceiling: Size): Boolean = width <= ceiling.width && height <= ceiling.height

  private fun Size?.floorRead(): EncoderRead = this?.let(EncoderRead::Floor) ?: EncoderRead.Absent

  private sealed interface Step {
    object Read : Step

    object Settled : Step

    class Refused(
      val error: FilmstripError,
    ) : Step
  }

  // What this reading has of one encoder. A Read ceiling that is not exact is the rung a frame fit under.
  private sealed interface EncoderRead {
    object Absent : EncoderRead

    class Floor(
      val floor: Size,
    ) : EncoderRead

    class Read(
      val floor: Size,
      val ceiling: Size,
      val exact: Boolean,
    ) : EncoderRead
  }
}

// The rung holding frame when the encoder opens there, else its real ceiling, paired with whether it is the real one.
private suspend fun FfmpegRuntime.ceilingHolding(
  toolchain: Toolchain,
  encoder: FfmpegEncoder,
  frame: Size,
): ProbeAnswer<Pair<Size, Boolean>?> {
  val holding = RESOLUTION_LADDER.lastOrNull { it.width >= frame.width && it.height >= frame.height }
  if (holding != null) {
    when (val rung = canEncode(toolchain, encoder, holding)) {
      is ProbeAnswer.Expired -> {
        return rung
      }
      is ProbeAnswer.Answered -> {
        if (rung.value) {
          return ProbeAnswer.Answered(holding to (holding == RESOLUTION_LADDER.first()))
        }
      }
    }
  }
  return when (val ceiling = ceilingOf(toolchain, encoder)) {
    is ProbeAnswer.Expired -> ceiling
    is ProbeAnswer.Answered -> ProbeAnswer.Answered(ceiling.value?.let { it to true })
  }
}

private const val NO_HDR_ENCODER = "A device that reads as encoding HDR names the encoder that would."

// Limits that clamp and lift nothing, for an encoder no plan has read yet.
private val ANY_FLOOR = Size(1, 1)
private val ANY_CEILING = Size(Int.MAX_VALUE, Int.MAX_VALUE)
