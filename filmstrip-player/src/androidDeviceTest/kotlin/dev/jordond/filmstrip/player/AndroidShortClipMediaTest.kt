package dev.jordond.filmstrip.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ShortClipContractTest
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The short clip contract on media3, whose frame rate setting can't write faster than a source, so a source at another
 * rate is the moving source's own samples remuxed onto that rate's frame times.
 */
class AndroidShortClipMediaTest : ShortClipContractTest(AndroidClipParity) {
  // media3's limit: the player's getFrame hands back the frame at or after the whole millisecond it is given, so a read
  // past a frame's own time lands on the next one.
  override fun frameReadLead(rate: Int): Duration = Duration.ZERO

  // media3's limit, androidx/media#921: its export gives a lengthened last item no more samples, so the video track
  // ends with the primary's own slots, as AndroidClipSpeedMediaTest reads it, and the preview's ends there too.
  override val writesHeldTail: Boolean get() = false

  // media3's limit: its frame rate setting is a maximum and nothing duplicates a frame, so a slowed clip writes each
  // frame it shows once, as AndroidClipSpeedMediaTest reads it.
  override val writesEverySlowedSlot: Boolean get() = false

  override suspend fun writtenFrameCount(composition: EditComposition): Long {
    val extractor = MediaExtractor().apply { setDataSource(androidExportedFile(composition).path) }
    try {
      extractor.selectTrack(extractor.trackOf("video/") ?: fail("the file holds no video"))
      var frames = 0L
      while (extractor.sampleTrackIndex >= 0) {
        frames++
        extractor.advance()
      }
      return frames
    } finally {
      extractor.release()
    }
  }

  override suspend fun sourceAt(rate: Int): MediaSource {
    val moving = movingSource().shouldBeInstanceOf<MediaSource.Path>()
    val target = File(contractContext().cacheDir, "short-clip-$rate.mp4")
    val extractor = MediaExtractor().apply { setDataSource(moving.path) }
    val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
      val video = extractor.trackOf("video/") ?: fail("the moving source holds no video")
      val audio = extractor.trackOf("audio/") ?: fail("the moving source holds no audio")
      val written = listOf(video, audio).associateWith { muxer.addTrack(extractor.getTrackFormat(it)) }
      written.keys.forEach(extractor::selectTrack)
      muxer.start()
      val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
      val info = MediaCodec.BufferInfo()
      while (true) {
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) break
        val track = extractor.sampleTrackIndex
        val time = if (track == video) extractor.sampleTime * FIXTURE_FRAME_RATE / rate else extractor.sampleTime
        info.set(0, size, time, extractor.sampleFlags)
        muxer.writeSampleData(written.getValue(track), buffer, info)
        extractor.advance()
      }
      muxer.stop()
    } finally {
      muxer.release()
      extractor.release()
    }
    return MediaSource.of(target.path)
  }

  private fun MediaExtractor.trackOf(mimePrefix: String): Int? =
    (0 until trackCount).firstOrNull {
      getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(mimePrefix)
    }

  private companion object {
    const val SAMPLE_BUFFER_BYTES = 1 shl 20
  }
}
