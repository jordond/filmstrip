package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.geometry.blendOverFill
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.interop.BufferTarget
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Output
import dev.jordond.filmstrip.webcodecs.internal.interop.Quality
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSource
import dev.jordond.filmstrip.webcodecs.internal.interop.WebMOutputFormat
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * A VP9 clip with a half transparent picture, probed and exported over a solid fill and read against [blendOverFill].
 *
 * The clip is written at test time with the alpha plane kept. The source and the clear fill are read off exports of the
 * same picture at full and no alpha, so the codes carry the codec's own rounding.
 */
@OptIn(InternalFilmstripApi::class)
class BrowserSourceAlphaMediaTest {
  @Test
  fun `a vp9 clip with alpha is reported and drawn over the fill on the shared blend`() =
    runTest {
      val half = MediaSource.Bytes(alphaWebm(alpha = HALF_ALPHA))
      val probed = BrowserProber().probe(half).shouldBeInstanceOf<ProbeResult.Success>()
      probed.info.video
        .shouldNotBeNull()
        .hasAlpha shouldBe true

      val opaque = exported(OPAQUE_ALPHA)
      val clear = exported(CLEAR_ALPHA)
      val drawn = exported(HALF_ALPHA)

      withClue("the fill has to differ from the source or this cannot tell a blend from no blend") {
        (abs(opaque.red - clear.red) > MIN_COVER_GAP) shouldBe true
      }
      val expected =
        Rgb(
          blendOverFill(opaque.red, HALF_ALPHA, clear.red),
          blendOverFill(opaque.green, HALF_ALPHA, clear.green),
          blendOverFill(opaque.blue, HALF_ALPHA, clear.blue),
        )
      withClue(
        "read ${drawn.red},${drawn.green},${drawn.blue} expected ${expected.red},${expected.green},${expected.blue}",
      ) {
        drawn.isNear(expected, TOLERANCE) shouldBe true
      }
    }

  private suspend fun exported(alpha: Int): Rgb {
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(alphaWebm(alpha)))))),
        audio = AudioSpec.Remove,
        fill = Fill.Solid(SLATE),
      )
    return decodeFrames(outputOf(exportOf(composition, to = MediaSink.Uri("")))).first().at(0.5, 0.5)
  }

  private suspend fun alphaWebm(alpha: Int): ByteArray {
    val target = BufferTarget()
    val output = Output(JsOptions().put("format", WebMOutputFormat()).put("target", target).build())
    val video =
      VideoSampleSource(
        JsOptions()
          .put("codec", "vp9")
          .put("alpha", "keep")
          .put("quality", Quality(JsOptions().put("bitrate", FIXTURE_BITRATE).build()))
          .put("keyFrameInterval", 1.0)
          .build(),
      )
    output.addVideoTrack(video, JsOptions().put("frameRate", FRAME_RATE).build())
    output.start().await()
    val pixels = ByteArray(SIDE * SIDE * RGBA_CHANNELS)
    for (i in 0 until SIDE * SIDE) {
      pixels[i * RGBA_CHANNELS] = RED.toByte()
      pixels[i * RGBA_CHANNELS + 1] = GREEN.toByte()
      pixels[i * RGBA_CHANNELS + 2] = BLUE.toByte()
      pixels[i * RGBA_CHANNELS + 3] = alpha.toByte()
    }
    for (index in 0 until FRAMES) {
      val sample =
        VideoSample(
          pixels.toUint8Array(),
          JsOptions()
            .put("format", "RGBA")
            .put("codedWidth", SIDE)
            .put("codedHeight", SIDE)
            .put("timestamp", index.toDouble() / FRAME_RATE)
            .put("duration", 1.0 / FRAME_RATE)
            .build(),
        )
      try {
        video.add(sample).await()
      } finally {
        sample.close()
      }
    }
    output.finalize().await()
    return (target.buffer ?: error("the fixture muxer produced no buffer")).toByteArray()
  }

  private companion object {
    const val RGBA_CHANNELS = 4
    const val FIXTURE_BITRATE = 2_000_000
    const val SIDE = 64
    const val FRAMES = 12
    const val FRAME_RATE = 30
    const val HALF_ALPHA = 128
    const val OPAQUE_ALPHA = 255
    const val CLEAR_ALPHA = 0
    const val MIN_COVER_GAP = 12

    // What a VP9 and an H.264 round trip move a read by.
    const val TOLERANCE = 10
    const val RED = 230
    const val GREEN = 30
    const val BLUE = 30
    const val SLATE = 0xFF2F4F6F.toInt()
  }
}
