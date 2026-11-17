package dev.jordond.filmstrip.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.HeardSound
import dev.jordond.filmstrip.player.contract.SeamSoundBackend
import dev.jordond.filmstrip.player.contract.SeamSoundContractTest
import dev.jordond.filmstrip.player.contract.measureSeam
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The seam sound contract on media3, which decodes the file it wrote back with a platform decoder.
 */
internal object AndroidSeamSound : SeamSoundBackend, ClipParityBackend by AndroidClipParity {
  override suspend fun soundOf(composition: Composition): HeardSound = decodedSound(androidExportedFile(composition))
}

/**
 * The seam sound cells on media3.
 */
class AndroidSeamSoundMediaTest : SeamSoundContractTest(AndroidSeamSound)

/**
 * The sound at a seam in a media3 preview, read off what `CompositionPlayer` hands its audio sink.
 *
 * media3's limit: `CompositionPlayer` leaves a quiet run of about 3.2 ms at the join of two clips. It loses no sound,
 * so the two clips play as long as the same run as one clip, to a frame at the sink's rate.
 */
class AndroidPreviewSeamSoundMediaTest {
  @Test
  fun aSeamLosesNoSoundAndLeavesAShortQuietRunInThePreview() =
    runTest(timeout = TIMEOUT) {
      SEAMS.forEach { seam ->
        val whole =
          previewSound(
            Composition(
              listOf(
                Track(
                  listOf(
                    Clip(
                      androidFixtureClip(),
                      TimeRange.of(
                        Duration.ZERO,
                        seam + LATER,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          )
        val cut = previewSound(seamEdit(seam))
        val lost = 1.seconds * (whole.samples.size - cut.samples.size) / cut.sampleRate
        val measured = measureSeam(cut, seam)
        val oneFrame = 1.seconds / cut.sampleRate
        withClue(
          "the preview cut at $seam lost $lost against $oneFrame, " +
            "with a quiet run of ${measured.quietRun} against $PREVIEW_QUIET_RUN",
        ) {
          (lost.absoluteValue <= oneFrame).shouldBeTrue()
          (measured.quietRun <= PREVIEW_QUIET_RUN).shouldBeTrue()
        }
      }
    }

  private fun seamEdit(seam: Duration): Composition =
    Composition(
      listOf(
        Track(
          listOf(
            Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, seam)),
            Clip(androidFixtureClip(), TimeRange.of(seam, seam + LATER)),
          ),
        ),
      ),
    )

  private suspend fun previewSound(edit: Composition): HeardSound {
    val plan =
      withContext(Dispatchers.Default) {
        Media3PreviewPlanner(CONTRACT_COMPONENTS).plan(edit, PreviewQualityPolicy.Full, ExportSpec())
      }.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan
    val heard = withContext(Dispatchers.IO) { playedThrough(plan.resolved.toMedia3Preview(plan.bounds).composition) }
    return HeardSound(heard.samples, heard.sampleRate)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    val LATER = 500.milliseconds

    // The seams of the export cells, on an access unit and either side of one.
    val SEAMS = listOf(512.milliseconds, 513.milliseconds, 501.milliseconds)

    // media3's limit: its preview leaves a quiet run at the join of about 3.2 ms, measured at all three seams on an
    // API 37 emulator, and loses no sound.
    val PREVIEW_QUIET_RUN = 4.milliseconds
  }
}

// The audio of [file] decoded to its first channel, minus the encoder delay its track names.
private fun decodedSound(file: File): HeardSound {
  val extractor = MediaExtractorCompat()
  try {
    extractor.setDataSource(Uri.fromFile(file), 0L)
    val track =
      (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      } ?: throw AssertionError("${file.name} carries no audio track to decode")
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    val delay =
      if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) format.getInteger(MediaFormat.KEY_ENCODER_DELAY) else 0
    format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
    val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
    try {
      codec.configure(format, null, null, 0)
      codec.start()
      val sound = codec.drain(extractor)
      return HeardSound(
        sound.samples.copyOfRange(delay.coerceIn(0, sound.samples.size), sound.samples.size),
        sound.sampleRate,
      )
    } finally {
      runCatching { codec.stop() }
      codec.release()
    }
  } finally {
    extractor.release()
  }
}

private fun MediaCodec.drain(extractor: MediaExtractorCompat): HeardSound {
  val pcm = ByteArrayOutputStream()
  val info = MediaCodec.BufferInfo()
  var format = outputFormat
  var fed = false
  var drained = false
  val deadline = System.nanoTime() + DECODE_BUDGET.inWholeNanoseconds
  while (!drained) {
    if (System.nanoTime() > deadline) throw AssertionError("the audio decoder stalled after $DECODE_BUDGET")
    if (!fed) {
      val input = dequeueInputBuffer(WAIT_MICROS)
      if (input >= 0) {
        val read = extractor.readSampleData(requireNotNull(getInputBuffer(input)), 0)
        if (read < 0) {
          queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          fed = true
        } else {
          queueInputBuffer(input, 0, read, extractor.sampleTime, 0)
          extractor.advance()
        }
      }
    }
    val output = dequeueOutputBuffer(info, WAIT_MICROS)
    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = outputFormat
    if (output < 0) continue
    if (info.size > 0) {
      val buffer = requireNotNull(getOutputBuffer(output))
      val chunk = ByteArray(info.size)
      buffer.position(info.offset)
      buffer.get(chunk)
      pcm.write(chunk)
    }
    releaseOutputBuffer(output, false)
    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) drained = true
  }
  val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
  val encoding =
    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
      format.getInteger(MediaFormat.KEY_PCM_ENCODING)
    } else {
      AudioFormat.ENCODING_PCM_16BIT
    }
  val bytes = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.nativeOrder())
  val frames =
    when (encoding) {
      AudioFormat.ENCODING_PCM_FLOAT -> {
        FloatArray(bytes.remaining() / Float.SIZE_BYTES / channels) {
          bytes.getFloat(it * channels * Float.SIZE_BYTES)
        }
      }
      AudioFormat.ENCODING_PCM_16BIT -> {
        FloatArray(bytes.remaining() / Short.SIZE_BYTES / channels) {
          bytes.getShort(it * channels * Short.SIZE_BYTES) /
            SHORT_FULL_SCALE
        }
      }
      else -> {
        throw AssertionError("the decoder produced PCM encoding $encoding, which nothing here reads")
      }
    }
  return HeardSound(frames, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
}

private val DECODE_BUDGET = 60.seconds
private const val WAIT_MICROS = 10_000L
private const val SHORT_FULL_SCALE = 32_768f
