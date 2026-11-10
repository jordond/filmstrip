package dev.jordond.filmstrip.player

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Plays [composition] through from its start on a fresh player and returns the first channel of what its audio sink
 * was handed.
 *
 * @param builder The player to build on the looper it is handed, before its audio sink is swapped for the tap.
 */
internal fun playedThrough(
  composition: Composition,
  builder: (Looper) -> CompositionPlayer.Builder = { CompositionPlayer.Builder(contractContext()).setLooper(it) },
): HeardAudio {
  val sink = PcmSink()
  val thread = HandlerThread("preview-audio").apply { start() }
  val handler = Handler(thread.looper)
  val ended = CountDownLatch(1)
  var error: PlaybackException? = null
  var player: CompositionPlayer? = null
  handler.post {
    val audioSink =
      DefaultAudioSink
        .Builder(contractContext())
        .setAudioProcessors(arrayOf(TeeAudioProcessor(sink)))
        .build()
    player =
      builder(thread.looper).setAudioSink(audioSink).build().apply {
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
 * The first channel of what a player's audio sink was handed.
 *
 * @property samples Each frame's first channel, on the -1 to 1 scale.
 * @property sampleRate The rate the sink was configured at.
 */
internal class HeardAudio(
  val samples: FloatArray,
  val sampleRate: Int,
)

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

  fun heard(): HeardAudio {
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
    return HeardAudio(samples, sampleRate)
  }
}

private val PLAY_LIMIT = 30.seconds
private const val PCM_16_SCALE = 32_768f
