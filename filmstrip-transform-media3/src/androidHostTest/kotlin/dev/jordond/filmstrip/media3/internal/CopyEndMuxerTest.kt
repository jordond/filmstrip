package dev.jordond.filmstrip.media3.internal

import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import com.google.common.collect.ImmutableList
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CopyEndMuxerTest {
  private val written = RecordingMuxer()
  private val muxer = CopyEndMuxerFactory(sourceEnd = null, gridEnd = null, media3 = written).create("copy.mp4")

  @Test
  fun `closing twice ends the audio once`() {
    val track = muxer.addTrack(AAC)
    muxer.writeSampleData(track, unit(), BufferInfo(0L, UNIT_BYTES, 0))

    muxer.close()
    muxer.close()

    written.ends shouldBe 1
    written.closes shouldBe 1
  }

  @Test
  fun `a closed muxer refuses priming it would have held back`() {
    val track = muxer.addTrack(AAC)
    muxer.close()

    assertFailsWith<IllegalStateException> { muxer.writeSampleData(track, unit(), BufferInfo(-1L, UNIT_BYTES, 0)) }
  }

  @Test
  fun `a track end that fails still closes the file`() {
    val track = muxer.addTrack(AAC)
    muxer.writeSampleData(track, unit(), BufferInfo(0L, UNIT_BYTES, 0))
    written.failsOnEnd = true

    assertFailsWith<MuxerException> { muxer.close() }

    written.closes shouldBe 1
  }

  private fun unit(): ByteBuffer = ByteBuffer.wrap(ByteArray(UNIT_BYTES))

  // Refuses every call after close, the way media3's own muxers do.
  private class RecordingMuxer :
    Muxer,
    Muxer.Factory {
    var ends = 0
    var closes = 0
    var failsOnEnd = false

    override fun create(path: String): Muxer = this

    override fun create(pfd: ParcelFileDescriptor): Muxer = this

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = ImmutableList.of()

    override fun addTrack(format: Format): Int {
      check(closes == 0)
      return 0
    }

    override fun writeSampleData(
      trackId: Int,
      byteBuffer: ByteBuffer,
      bufferInfo: BufferInfo,
    ) {
      check(closes == 0)
      if (bufferInfo.flags and C.BUFFER_FLAG_END_OF_STREAM == 0) return
      if (failsOnEnd) throw MuxerException("end refused", IllegalArgumentException())
      ends++
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = check(closes == 0)

    override fun close() {
      closes++
    }
  }

  private companion object {
    val AAC: Format =
      Format
        .Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setSampleRate(48_000)
        .build()

    const val UNIT_BYTES = 8
  }
}
