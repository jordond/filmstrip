package dev.jordond.filmstrip.player

import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Where a trimmed clip's audio opens in a media3 preview, read off what `CompositionPlayer` hands its audio sink.
 *
 * media3's limit: `CompositionPlayer` refuses an audio processor that changes an item's length, so a preview leaves a
 * clip's speed to media3. That opens the clip's audio on the first access unit at or after its trim start and stretches
 * the decoder's fade into that unit by the speed, so a slowed clip's audio opens late, by up to two access units of
 * source. An export opens on the trim start at every speed.
 */
class AndroidPreviewAudioStartMediaTest {
  @Test
  fun aClipAtSpeedOneOpensItsPreviewAudioWithinAnOutputFrame() =
    runTest(timeout = TIMEOUT) {
      TRIM_STARTS.forEach { start ->
        val opening = previewAudioOpening(start, 1.0)
        withClue("at speed 1 trimmed from $start the preview's audio opened at ${opening.at}") {
          (opening.at <= OUTPUT_FRAME).shouldBeTrue()
        }
      }
    }

  @Test
  fun aSlowedClipOpensItsPreviewAudioLateByAtMostTwoAccessUnitsAtItsSpeed() =
    runTest(timeout = TIMEOUT) {
      val speed = CLIP_SPEED_RANGE.start
      TRIM_STARTS.forEach { start ->
        val opening = previewAudioOpening(start, speed)
        withClue("at $speed trimmed from $start the preview's audio opened at ${opening.at}") {
          (opening.at > OUTPUT_FRAME && opening.at <= opening.accessUnit * 2 / speed).shouldBeTrue()
        }
      }
    }

  // Where the preview of the fixture trimmed from [start] at [speed] is first louder than a quiet floor.
  private suspend fun previewAudioOpening(
    start: Duration,
    speed: Double,
  ): Opening {
    val edit =
      Composition(listOf(Track(listOf(Clip(androidFixtureClip(), TimeRange.of(start, start + TRIM), speed = speed)))))
    val plan =
      withContext(Dispatchers.Default) {
        Media3PreviewPlanner(CONTRACT_COMPONENTS).plan(edit, PreviewQualityPolicy.Full, ExportSpec())
      }.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan
    val composition = plan.resolved.toMedia3Preview(plan.bounds).composition
    val heard = withContext(Dispatchers.IO) { played(composition) }
    val first = heard.samples.indexOfFirst { abs(it) > LOUD }
    if (first < 0) fail("the preview at $speed trimmed from $start played no sound")
    return Opening(at = 1.seconds * first / heard.sampleRate, accessUnit = 1.seconds * AAC_FRAMES / heard.sampleRate)
  }

  // Where a preview's audio opened, and how long one AAC access unit runs at the rate it played at.
  private class Opening(
    val at: Duration,
    val accessUnit: Duration,
  )

  // Plays [composition] through from its start on a fresh player and returns the first channel of what its sink got.
  private fun played(composition: androidx.media3.transformer.Composition): Heard {
    val sink = PcmSink()
    val thread = HandlerThread("preview-audio").apply { start() }
    val handler = Handler(thread.looper)
    val ended = CountDownLatch(1)
    var error: PlaybackException? = null
    var player: CompositionPlayer? = null
    handler.post {
      val audioSink =
        DefaultAudioSink
          .Builder(
            contractContext(),
          ).setAudioProcessors(arrayOf(TeeAudioProcessor(sink)))
          .build()
      player =
        CompositionPlayer.Builder(contractContext()).setLooper(thread.looper).setAudioSink(audioSink).build().apply {
          addListener(
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.countDown()
              }

              override fun onPlayerError(failure: PlaybackException) {
                error = failure
                ended.countDown()
              }
            },
          )
          setComposition(composition)
          prepare()
          play()
        }
    }
    val finished = ended.await(PLAY_LIMIT.inWholeSeconds, TimeUnit.SECONDS)
    val released = CountDownLatch(1)
    handler.post {
      player?.release()
      released.countDown()
    }
    released.await(PLAY_LIMIT.inWholeSeconds, TimeUnit.SECONDS)
    thread.quitSafely()
    withClue("the preview failed with $error") { error shouldBe null }
    withClue("the preview never ended") { finished.shouldBeTrue() }
    return sink.heard()
  }

  /**
   * Keeps every byte of PCM the player's audio sink is handed, along with the format it was last configured at.
   */
  private class PcmSink : TeeAudioProcessor.AudioBufferSink {
    private val bytes = ByteArrayOutputStream()
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    override fun flush(
      sampleRateHz: Int,
      channelCount: Int,
      encoding: Int,
    ) {
      sampleRate = sampleRateHz
      channels = channelCount
      this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
      val chunk = ByteArray(buffer.remaining())
      buffer.duplicate().get(chunk)
      bytes.write(chunk)
    }

    fun heard(): Heard {
      val read = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.nativeOrder())
      val samples =
        when (encoding) {
          C.ENCODING_PCM_16BIT -> {
            val shorts = read.asShortBuffer()
            FloatArray(shorts.remaining() / channels) { shorts.get(it * channels) / PCM_16_SCALE }
          }
          C.ENCODING_PCM_FLOAT -> {
            val floats = read.asFloatBuffer()
            FloatArray(floats.remaining() / channels) { floats.get(it * channels) }
          }
          else -> {
            fail("the sink was handed PCM encoding $encoding")
          }
        }
      return Heard(samples, sampleRate)
    }
  }

  private class Heard(
    val samples: FloatArray,
    val sampleRate: Int,
  )

  private companion object {
    val TIMEOUT = 3.minutes
    val PLAY_LIMIT = 30.seconds
    val TRIM = 125.milliseconds
    val OUTPUT_FRAME = FIXTURE_FRAME_STEP

    // Three starts at different distances into an AAC access unit, the last well into the fixture.
    val TRIM_STARTS = listOf(200.milliseconds, 210.milliseconds, 1_000.milliseconds)
    const val AAC_FRAMES = 1_024
    const val LOUD = 0.05f
    const val PCM_16_SCALE = 32_768f
  }
}
