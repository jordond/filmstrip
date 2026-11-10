@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.webcodecs.internal.interop.BufferTarget
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Mp4OutputFormat
import dev.jordond.filmstrip.webcodecs.internal.interop.Output
import dev.jordond.filmstrip.webcodecs.internal.interop.Quality
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSource
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop

/**
 * An MP4 with one grey video track per entry of [sizes], in that order, and no audio.
 *
 * Only the track at [defaultIndex] is flagged as the default, so a reader that picks by disposition reads another
 * track than one that takes the first.
 */
internal suspend fun makeClipWithVideoTracks(
  sizes: List<Size>,
  defaultIndex: Int,
  frames: Int = 6,
  frameRate: Int = 30,
): ByteArray {
  val target = BufferTarget()
  val output = Output(JsOptions().put("format", Mp4OutputFormat()).put("target", target).build())
  val sources =
    sizes.mapIndexed { index, _ ->
      val source =
        VideoSampleSource(
          JsOptions()
            .put("codec", "avc")
            .put("fullCodecString", "avc1.42001F")
            .put("quality", Quality(JsOptions().put("bitrate", FIXTURE_BITRATE).build()))
            .build(),
        )
      val disposition = JsOptions().put("default", index == defaultIndex).build()
      output.addVideoTrack(source, JsOptions().put("frameRate", frameRate).put("disposition", disposition).build())
      source
    }
  output.start().await()

  sizes.zip(sources).forEach { (size, source) ->
    val pixels =
      ByteArray(size.width * size.height * RGBA_CHANNELS) {
        if (it % RGBA_CHANNELS ==
          ALPHA
        ) {
          OPAQUE
        } else {
          GREY
        }
      }
    for (index in 0 until frames) {
      val sample =
        VideoSample(
          pixels.toUint8Array(),
          JsOptions()
            .put("format", "RGBA")
            .put("codedWidth", size.width)
            .put("codedHeight", size.height)
            .put("timestamp", index.toDouble() / frameRate)
            .put("duration", 1.0 / frameRate)
            .build(),
        )
      try {
        source.add(sample).await()
      } finally {
        sample.close()
      }
    }
  }

  output.finalize().await()
  return (target.buffer ?: error("the fixture muxer produced no buffer")).toByteArray()
}

private const val RGBA_CHANNELS = 4
private const val ALPHA = 3
private const val OPAQUE = 255.toByte()
private const val GREY = 128.toByte()
private const val FIXTURE_BITRATE = 2_000_000
