package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.avfoundation.internal.toNSURL
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.internal.AppleScratch
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.containerExtensionOf
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSystemFileNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A buffer holding a clip behaves as the file it came from, at every place this backend reads a source.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class AppleBytesSourceMediaTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  private fun bytesOf(name: String): MediaSource.Bytes = MediaSource.Bytes(readBytes(fixture(name)))

  private suspend fun probe(source: MediaSource): ProbeResult =
    withContext(Dispatchers.Default) {
      filmstrip.probe(source)
    }

  private suspend fun info(source: MediaSource): MediaInfo =
    probe(source).shouldBeInstanceOf<ProbeResult.Success>().info

  private suspend fun resolved(composition: EditComposition): ResolvedComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, ExportSpec())) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private suspend fun exported(
    source: MediaSource,
    name: String,
  ): MediaInfo =
    withContext(Dispatchers.Default) {
      val output = NSTemporaryDirectory() + "filmstrip-apple-bytes-$name.mp4"
      NSFileManager.defaultManager.removeItemAtPath(output, null)
      val statuses =
        filmstrip
          .export(
            compositionOf { clip(source) { trim(EXPORT_START, EXPORT_END) } },
            ExportSpec(heightPx = EXPORT_HEIGHT),
            MediaSink.of(output),
          ).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>().info
      }.also { NSFileManager.defaultManager.removeItemAtPath(output, null) }
    }

  private fun snapped(source: MediaSource): EditComposition =
    compositionOf {
      clip(source) {
        trim(TRIM_START, TRIM_END)
        snapWithin(SNAP_WITHIN)
      }
    }

  private suspend fun opensAt(source: MediaSource): Duration =
    resolved(snapped(source))
      .tracks
      .first()
      .clips
      .single()
      .start

  @Test
  fun `a buffer probes the same as the file it came from`() =
    runTest(timeout = TIMEOUT) {
      info(bytesOf(CLIP)) shouldBe info(MediaSource.of(fixture(CLIP)))
    }

  @Test
  fun `an export of a buffer lands where the file's does`() =
    runTest(timeout = TIMEOUT) {
      val fromFile = exported(MediaSource.of(fixture(CLIP)), "file")
      val fromBytes = exported(bytesOf(CLIP), "bytes")

      fromBytes.duration shouldBe fromFile.duration
      fromBytes.video?.displaySize shouldBe fromFile.video?.displaySize
      fromBytes.audio?.sampleRate shouldBe fromFile.audio?.sampleRate
    }

  @Test
  fun `a trim on a buffer reads the sync sample the file reads`() =
    runTest(timeout = TIMEOUT) {
      val snappedBytes = opensAt(bytesOf(LONG))

      snappedBytes shouldBe opensAt(MediaSource.of(fixture(LONG)))
      snappedBytes shouldNotBe TRIM_START
    }

  @Test
  fun `a snap on a buffer stays on the copy path like the file`() =
    runTest(timeout = TIMEOUT) {
      resolved(snapped(bytesOf(LONG))).path shouldBe ExportPath.Copy
      resolved(snapped(MediaSource.of(fixture(LONG)))).path shouldBe ExportPath.Copy
    }

  @Test
  fun `opening a buffer again does not write it again`() =
    runTest(timeout = TIMEOUT) {
      val source = bytesOf(CLIP)
      val path = source.toNSURL().path.shouldNotBeNull()
      val before = NSFileManager.defaultManager.attributesOfItemAtPath(path, null).shouldNotBeNull()

      resolved(compositionOf { clip(source) })
      source.toNSURL().path shouldBe path

      val after = NSFileManager.defaultManager.attributesOfItemAtPath(path, null).shouldNotBeNull()
      after[NSFileSystemFileNumber] shouldBe before[NSFileSystemFileNumber]
      after[NSFileModificationDate] shouldBe before[NSFileModificationDate]
    }

  @Test
  fun `a buffer with no ftyp opens under the neutral name`() =
    runTest(timeout = TIMEOUT) {
      val bytes = readBytes(fixture(CLIP)).also { "free".encodeToByteArray().copyInto(it, destinationOffset = 4) }
      val source = MediaSource.Bytes(bytes)

      source.toNSURL().pathExtension shouldBe containerExtensionOf(bytes)
      info(source) shouldBe info(MediaSource.of(fixture(CLIP)))
    }

  @Test
  fun `a buffer that does not open says so under the name it was written to`() =
    runTest(timeout = TIMEOUT) {
      val matroska = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(EBML_FILLER_BYTES)
      val corrupt = readBytes(fixture(CLIP)).copyOf(CORRUPT_BYTES)

      listOf(matroska, corrupt).forEach { bytes ->
        val error =
          probe(MediaSource.Bytes(bytes))
            .shouldBeInstanceOf<ProbeResult.Failure>()
            .error
            .shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

        error.message shouldBe AppleScratch.unreadableUnder(containerExtensionOf(bytes))
      }
    }

  @Test
  fun `an audio buffer opens under its own name and reads as the file does`() =
    runTest(timeout = TIMEOUT) {
      listOf(wav(), caf(), SILENT_ADTS_FRAME.repeat(ADTS_FRAMES).hexToByteArray()).forEach { bytes ->
        val written = AppleScratch.urlFor(MediaSource.Bytes(bytes))

        written.pathExtension shouldBe containerExtensionOf(bytes)
        val fromBytes = info(MediaSource.Bytes(bytes))
        fromBytes.audio.shouldNotBeNull()
        fromBytes shouldBe info(MediaSource.of(written.path.shouldNotBeNull()))
      }
    }

  @Test
  fun `a transport stream buffer opens on macOS and not on the simulator`() =
    runTest(timeout = TIMEOUT) {
      listOf(transportStream(packetBytes = TS_PACKET_BYTES), transportStream(packetBytes = M2TS_PACKET_BYTES))
        .forEach { bytes ->
          val written = AppleScratch.urlFor(MediaSource.Bytes(bytes))
          written.pathExtension shouldBe containerExtensionOf(bytes)

          when {
            Platform.osFamily == OsFamily.MACOSX -> {
              val fromBytes = info(MediaSource.Bytes(bytes))
              fromBytes.audio.shouldNotBeNull()
              fromBytes shouldBe info(MediaSource.of(written.path.shouldNotBeNull()))
            }
            NSProcessInfo.processInfo.environment["SIMULATOR_UDID"] != null -> {
              probe(MediaSource.Bytes(bytes))
                .shouldBeInstanceOf<ProbeResult.Failure>()
                .error.message shouldBe AppleScratch.unreadableUnder(containerExtensionOf(bytes))
            }
          }
        }
    }

  @Test
  fun `an export of a buffer that cannot be written fails as unreadable`() =
    runTest(timeout = TIMEOUT) {
      val blocked = NSTemporaryDirectory() + "filmstrip-blocked-root-" + NSProcessInfo.processInfo.globallyUniqueString
      NSFileManager.defaultManager.createFileAtPath(blocked, null, null) shouldBe true
      try {
        val composition = resolved(compositionOf { clip(bytesOf(CLIP)) })
        val driver = AvDriver(UNUSED_PROBER, bytesRoot = blocked)
        val output = MediaSink.of(NSTemporaryDirectory() + "filmstrip-apple-blocked.mp4")

        val failure =
          driver
            .export(composition, output)
            .toList()
            .single()
            .shouldBeInstanceOf<ExportStatus.Failure>()

        failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldContain "temporary file"
        driver.syncSampleAtOrBefore(bytesOf(CLIP), TRIM_START) shouldBe null
      } finally {
        NSFileManager.defaultManager.removeItemAtPath(blocked, null)
      }
    }

  private fun ascii(text: String): ByteArray = text.encodeToByteArray()

  private fun littleEndian(
    value: Int,
    bytes: Int,
  ): ByteArray = ByteArray(bytes) { (value ushr (it * 8)).toByte() }

  private fun bigEndian(
    value: Long,
    bytes: Int,
  ): ByteArray = ByteArray(bytes) { (value ushr ((bytes - 1 - it) * 8)).toByte() }

  // A tenth of a second of silence, mono at 16 bits.
  private fun wav(): ByteArray =
    ascii("RIFF") + littleEndian(36 + PCM_BYTES, 4) + ascii("WAVEfmt ") + littleEndian(16, 4) + littleEndian(1, 2) +
      littleEndian(1, 2) + littleEndian(PCM_RATE, 4) + littleEndian(PCM_RATE * 2, 4) + littleEndian(2, 2) +
      littleEndian(16, 2) + ascii("data") + littleEndian(PCM_BYTES, 4) + ByteArray(PCM_BYTES)

  private fun caf(): ByteArray =
    ascii("caff") + bigEndian(1, 2) + bigEndian(0, 2) + ascii("desc") + bigEndian(32, 8) +
      bigEndian(PCM_RATE.toDouble().toRawBits(), 8) + ascii("lpcm") + bigEndian(0, 4) + bigEndian(2, 4) +
      bigEndian(1, 4) + bigEndian(1, 4) + bigEndian(16, 4) + ascii("data") + bigEndian(4L + PCM_BYTES, 8) +
      bigEndian(0, 4) + ByteArray(PCM_BYTES)

  // The silent ADTS frames as an audio only stream, one PES to a packet and any room left over stuffed.
  private fun transportStream(packetBytes: Int): ByteArray {
    val frame = SILENT_ADTS_FRAME.hexToByteArray()
    val packets =
      listOf(
        psiPacket(pid = 0, "00b00d0001c100000001f000".hexToByteArray()),
        psiPacket(PMT_PID, "02b0120001c10000e100f0000fe100f000".hexToByteArray()),
      ) + List(ADTS_FRAMES) { index -> audioPacket(index, frame) }
    return packets.fold(ByteArray(0)) { stream, packet ->
      stream + ByteArray(packetBytes - TS_PACKET_BYTES) + packet
    }
  }

  private fun psiPacket(
    pid: Int,
    section: ByteArray,
  ): ByteArray {
    val body =
      byteArrayOf(0x47, (0x40 or (pid shr 8)).toByte(), pid.toByte(), 0x10, 0) + section +
        bigEndian(crc32Mpeg(section), 4)
    return body + ByteArray(TS_PACKET_BYTES - body.size) { 0xFF.toByte() }
  }

  private fun audioPacket(
    index: Int,
    frame: ByteArray,
  ): ByteArray {
    val pts = TS_START_TICKS + index * ADTS_FRAME_TICKS
    val pesLength = bigEndian(8L + frame.size, 2)
    val pes = "000001c0".hexToByteArray() + pesLength + "808005".hexToByteArray() + timestamp(pts) + frame
    val header =
      byteArrayOf(0x47, (0x40 or (AUDIO_PID shr 8)).toByte(), AUDIO_PID.toByte(), (0x30 or (index % 16)).toByte())
    val pcr = bigEndian((pts shr 1) and 0xFFFFFFFFL, 4) + byteArrayOf((((pts and 1) shl 7) or 0x7E).toByte(), 0)
    val fieldBytes = TS_PACKET_BYTES - header.size - 1 - pes.size
    val field = byteArrayOf(0x10) + pcr + ByteArray(fieldBytes - 1 - pcr.size) { 0xFF.toByte() }
    return header + fieldBytes.toByte() + field + pes
  }

  private fun timestamp(ticks: Long): ByteArray =
    byteArrayOf(
      (0x21L or ((ticks shr 30) shl 1)).toByte(),
      (ticks shr 22).toByte(),
      (((ticks shr 15) shl 1) or 1).toByte(),
      (ticks shr 7).toByte(),
      ((ticks shl 1) or 1).toByte(),
    )

  private fun crc32Mpeg(bytes: ByteArray): Long =
    bytes.fold(0xFFFFFFFFL) { start, byte ->
      (0 until 8).fold(start xor ((byte.toLong() and 0xFF) shl 24)) { crc, _ ->
        if (crc and 0x80000000L != 0L) ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL else (crc shl 1) and 0xFFFFFFFFL
      }
    }

  private companion object {
    val TIMEOUT = 2.seconds * 60
    const val CLIP = "apple_export_a.mp4"
    const val LONG = "apple_export_long.mp4"
    const val EXPORT_HEIGHT = 240
    const val EBML_FILLER_BYTES = 64
    const val CORRUPT_BYTES = 2_000
    const val PCM_RATE = 8_000
    const val PCM_BYTES = 1_600

    // A silent frame as ffmpeg's AAC encoder writes it, repeated since too few frames don't open at all.
    const val SILENT_ADTS_FRAME = "fff16c40017ffc01182007"
    const val ADTS_FRAMES = 100

    const val TS_PACKET_BYTES = 188
    const val M2TS_PACKET_BYTES = 192
    const val PMT_PID = 0x1000
    const val AUDIO_PID = 0x100
    const val TS_START_TICKS = 90_000L

    // 1,024 samples at the frame's 8 kHz, on the stream's 90 kHz clock.
    const val ADTS_FRAME_TICKS = 11_520L

    val UNUSED_PROBER = MediaProber { error("No probe is expected here.") }
    val EXPORT_START = 500.milliseconds
    val EXPORT_END = 1_500.milliseconds
    val TRIM_START = 3_500.milliseconds
    val TRIM_END = 7.seconds
    val SNAP_WITHIN = 2.seconds
  }
}
