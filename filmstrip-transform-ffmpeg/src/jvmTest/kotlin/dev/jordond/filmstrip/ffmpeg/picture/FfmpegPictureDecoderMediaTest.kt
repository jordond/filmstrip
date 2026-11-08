package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.sizeOfFrameCrc
import dev.jordond.filmstrip.ffmpeg.internal.picture.ycbcrScale
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeOperation
import dev.jordond.filmstrip.ffmpeg.internal.process.interruptibly
import dev.jordond.filmstrip.ffmpeg.internal.process.probeExpiredReason
import dev.jordond.filmstrip.ffmpeg.overlay.encoded
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_COLOR
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.convertedFilter
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.orientedJpeg
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.test.IccPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The child processes an overlay picture is read through, which never outlive the read.
 *
 * A stand-in for ffmpeg that records its process id and then sleeps is what a hung decode looks like from here, so a
 * test can stop it part way and prove it is gone.
 */
class FfmpegPictureDecoderMediaTest {
  private val directory: File = Files.createTempDirectory("filmstrip-picture").toFile()
  private val scratch = File(directory, "scratch").apply { mkdirs() }
  private val pidFile = File(directory, "pid")
  private val fixture = FfmpegExportFixture()

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  // The resolve runs the way the planner's does, blocking inside interruptibly, on an HDR grade and a picture the JDK
  // cannot read, so the decode is the hanging ffmpeg. What the resolve returned or threw is caught inside the block,
  // since the coroutine itself ends cancelled whether or not the interrupt escaped it.
  @Test
  fun `cancelling a plan part way through a decode stops the ffmpeg it spawned and ends the plan cancelled`() =
    runBlocking<Unit> {
      if (!posix) return@runBlocking
      val resolver = BuiltInEffectResolver(FfmpegPictureDecoder(hangingBinary(), scratch = scratch))
      val spec = ImageOverlay(ImageSource.ofBytes(UNREADABLE), Corner.TopStart)
      val resolved = AtomicReference<EffectResolution?>()
      val thrown = AtomicReference<Throwable?>()

      val planning =
        launch(Dispatchers.Default) {
          interruptibly {
            try {
              resolved.set(resolver.resolve(spec, capabilities, hdrAttributes))
            } catch (escaped: InterruptedException) {
              thrown.set(escaped)
              throw escaped
            }
          }
        }
      val pid = spawnedPid()
      planning.cancelAndJoin()

      withClue("the decode's ffmpeg is still running after the plan was cancelled") { alive(pid).shouldBeFalse() }
      withClue("the plan did not end cancelled") { planning.isCancelled.shouldBeTrue() }
      withClue("the plan carried on with ${resolved.get()} after it was cancelled") { resolved.get().shouldBeNull() }
      withClue("the interrupt never reached the plan") { thrown.get().shouldBeInstanceOf<InterruptedException>() }
      withClue("the picture's bytes were left in ${scratch.list()?.toList()}") {
        scratch
          .list()
          .orEmpty()
          .isEmpty()
          .shouldBeTrue()
      }
    }

  @Test
  fun `a decode that runs past its timeout is stopped and expires naming its deadline`() {
    if (!posix) return
    val binary = hangingBinary()
    val decoder = FfmpegPictureDecoder(binary, timeoutMillis = SHORT_TIMEOUT, scratch = scratch)

    val expired = decoder.decode(ImageSource.ofBytes(PICTURE)).shouldBeInstanceOf<OverlayPictureRead.Expired>()

    withClue("the decode's ffmpeg outlived its timeout") { alive(spawnedPid()).shouldBeFalse() }
    expired.operation shouldBe ProbeOperation.PICTURE_DECODE
    expired.deadline shouldBe SHORT_TIMEOUT.milliseconds
    expired.reason shouldBe probeExpiredReason(File(binary).name, SHORT_TIMEOUT.milliseconds)
    withClue("the picture's bytes were left in ${scratch.list()?.toList()}") {
      scratch
        .list()
        .orEmpty()
        .isEmpty()
        .shouldBeTrue()
    }
  }

  @Test
  fun `an overlay whose decode expires on an HDR grade fails as timed out`() {
    if (!posix) return
    val binary = hangingBinary()
    val resolver = BuiltInEffectResolver(FfmpegPictureDecoder(binary, timeoutMillis = SHORT_TIMEOUT, scratch = scratch))
    val spec = ImageOverlay(ImageSource.ofBytes(UNREADABLE), Corner.TopStart)

    val failed = resolver.resolve(spec, capabilities, hdrAttributes).shouldBeInstanceOf<EffectResolution.Failed>()

    failed.error shouldBe timedOut(binary)
  }

  @Test
  fun `an overlay whose measure expires on an SDR grade fails as timed out`() {
    if (!posix) return
    val binary = hangingBinary()
    val resolver = BuiltInEffectResolver(FfmpegPictureDecoder(binary, timeoutMillis = SHORT_TIMEOUT, scratch = scratch))
    val spec = ImageOverlay(ImageSource.ofBytes(PICTURE), Corner.TopStart)

    val failed = resolver.resolve(spec, capabilities, sdrAttributes).shouldBeInstanceOf<EffectResolution.Failed>()

    failed.error shouldBe timedOut(binary)
  }

  private fun timedOut(binary: String): FilmstripError.TimedOut {
    val deadline = SHORT_TIMEOUT.milliseconds
    return FilmstripError.TimedOut(
      ProbeOperation.PICTURE_DECODE,
      deadline,
      probeExpiredReason(File(binary).name, deadline),
    )
  }

  @Test
  fun `picture bytes that fail to write leave no file behind and spawn nothing`() {
    if (!posix) return
    val decoder =
      FfmpegPictureDecoder(hangingBinary(), scratch = scratch) { file, bytes ->
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        throw IOException(DISK_FULL)
      }

    val refused = decoder.decode(ImageSource.ofBytes(PICTURE)).shouldBeInstanceOf<OverlayPictureRead.Refused>()

    refused.reason shouldBe DISK_FULL
    withClue("the half written picture was left in ${scratch.list()?.toList()}") {
      scratch
        .list()
        .orEmpty()
        .isEmpty()
        .shouldBeTrue()
    }
    withClue("ffmpeg was spawned on a picture that never finished writing") { pidFile.exists().shouldBeFalse() }
  }

  @Test
  fun `measures a WebP the JDK cannot read at its own size`() {
    if (!fixture.available()) return
    val webp = encoded(PICTURE, "webp", listOf("-c:v", "libwebp", "-lossless", "1")) ?: return
    val decoder = FfmpegPictureDecoder(hostFfmpeg, scratch = scratch)

    decoder.measure(ImageSource.ofBytes(webp.readBytes())).value() shouldBe OVERLAY_SIZE
    decoder.measure(ImageSource.of(webp.absolutePath)).value() shouldBe OVERLAY_SIZE
    withClue("the picture's bytes were left in ${scratch.list()?.toList()}") {
      scratch
        .list()
        .orEmpty()
        .isEmpty()
        .shouldBeTrue()
    }
    webp.delete()
  }

  // Display P3 orange sits outside BT.709, so a decode that color managed it on the way would move the stored samples.
  @Test
  fun `a decode carries a tagged picture's color beside the samples it stores`() {
    if (!fixture.available()) return
    val picture = IccPictures.DISPLAY_P3
    val png = picture.pngOf(OVERLAY_SIZE)
    val decoder = FfmpegPictureDecoder(hostFfmpeg, scratch = scratch)

    val read = decoder.decode(ImageSource.ofBytes(png))
    val decoded =
      withClue("this ffmpeg decoded nothing") {
        (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
      }

    val texel = intArrayOf(picture.texel)
    withClue("color lost") { pictureLight(decoded.color, texel) shouldBe pictureLight(pictureColor(png), texel) }
    decoded.samples
      .shouldBeInstanceOf<StoredSamples.Argb>()
      .argb
      .first() shouldBe picture.texel
  }

  @Test
  fun `an untagged picture's decode carries no color`() {
    if (!fixture.available()) return
    val decoder = FfmpegPictureDecoder(hostFfmpeg, scratch = scratch)

    val read = decoder.decode(ImageSource.ofBytes(PICTURE))
    withClue("this ffmpeg decoded nothing") {
      (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
    }.color.shouldBeNull()
  }

  @Test
  fun `an nclx box's matrix and range become the scale the decode converts with`() {
    val cases =
      listOf(
        coding(1, true) to "$FULL_HOP,scale=in_color_matrix=bt709:in_range=pc:flags=full_chroma_int",
        coding(6, false) to "$LIMITED_HOP,scale=in_color_matrix=smpte170m:in_range=tv:flags=full_chroma_int",
        coding(5, false) to "$LIMITED_HOP,scale=in_color_matrix=smpte170m:in_range=tv:flags=full_chroma_int",
        coding(2, true) to "$FULL_HOP,scale=in_color_matrix=smpte170m:in_range=pc:flags=full_chroma_int",
        coding(9, false) to "$LIMITED_HOP,scale=in_color_matrix=bt2020:in_range=tv:flags=full_chroma_int",
        coding(4, true) to "$FULL_HOP,scale=in_color_matrix=fcc:in_range=pc:flags=full_chroma_int",
        coding(7, false) to "$LIMITED_HOP,scale=in_color_matrix=smpte240m:in_range=tv:flags=full_chroma_int",
      )
    for ((coding, scale) in cases) withClue("$coding") { ycbcrScale(coding) shouldBe scale }
    for ((coding, scale) in cases) {
      withClue("$coding") {
        convertedFilter(coding.copy(codec = CodecKind.Hevc)) shouldBe scale
      }
    }
    val unknownDepth = coding(1, true).copy(bitDepth = null)
    ycbcrScale(unknownDepth) shouldBe "scale=in_color_matrix=bt709:in_range=pc:flags=full_chroma_int"
    val grey = coding(1, false).copy(bitDepth = 12, chromaFormat = 0)
    ycbcrScale(grey) shouldBe "scale=in_color_matrix=bt709:in_range=tv:flags=full_chroma_int"
    val twelveBit = coding(9, true).copy(bitDepth = 12, chromaFormat = 1)
    ycbcrScale(twelveBit) shouldBe "scale=in_range=pc:out_range=pc,format=yuv444p12le," +
      "scale=in_color_matrix=bt2020:in_range=pc:flags=full_chroma_int"
    val unsubsampled = coding(9, true).copy(bitDepth = 12, chromaFormat = 3)
    unsubsampled.unsubsampled.shouldBeTrue()
    ycbcrScale(unsubsampled) shouldBe "scale=in_color_matrix=bt2020:in_range=pc:flags=full_chroma_int"
  }

  // Each case names AV1, and a HEIC with the same coding goes through the same scale.
  private fun coding(
    matrix: Int,
    fullRange: Boolean,
  ): YcbcrCoding = YcbcrCoding(matrix, fullRange, CodecKind.Av1, codestream = null, bitDepth = 8)

  @Test
  fun `a framecrc's size is read off its dimensions line and anything else has none`() {
    val framecrc = "#tb 0: 1/25\n#media_type 0: video\n#dimensions 0: 20x40\n#sar 0: 1/1\n0, 0, 0, 1, 424, 0x0\n"
    sizeOfFrameCrc(framecrc.encodeToByteArray()) shouldBe Size(20, 40)
    sizeOfFrameCrc("#dimensions 0: 20x\n".encodeToByteArray()).shouldBeNull()
    sizeOfFrameCrc("#dimensions 0: 0x40\n".encodeToByteArray()).shouldBeNull()
    sizeOfFrameCrc(PICTURE).shouldBeNull()
  }

  // EXIF orientation 6 turns the picture a quarter clockwise, which the decoder leaves to its caller, so its decode and
  // its measure both keep the stored size.
  @Test
  fun `an orientation 6 JPEG decodes and measures at its stored size`() {
    if (!fixture.available()) return
    val stored = BufferedImage(WIDE.width, WIDE.height, BufferedImage.TYPE_INT_RGB)
    val jpeg = ByteArrayOutputStream().also { check(ImageIO.write(stored, "jpeg", it)) }.toByteArray()
    val oriented = ImageSource.ofBytes(orientedJpeg(jpeg, ROTATED_CLOCKWISE))
    val decoder = FfmpegPictureDecoder(hostFfmpeg, scratch = scratch)

    val decoded = withClue("this ffmpeg decoded nothing") { decoder.decode(oriented).value().shouldNotBeNull() }
    withClue("the decode turned an orientation 6 JPEG") { decoded.size shouldBe WIDE }
    decoder.measure(oriented).value() shouldBe WIDE
  }

  @Test
  fun `a picture ffmpeg cannot read is refused with its own words`() {
    if (!fixture.available()) return
    val garbage = ImageSource.ofBytes("not a picture".encodeToByteArray())
    val decoder = FfmpegPictureDecoder(hostFfmpeg, scratch = scratch)
    val reasons =
      listOf(decoder.decode(garbage), decoder.measure(garbage)).map {
        it.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason
      }

    withClue("a failed read said nothing: $reasons") { reasons.all { it.isNotBlank() }.shouldBeTrue() }
  }

  // Writes its own pid where the test can read it, then sleeps well past any test's timeout. exec keeps the pid the
  // one the decoder spawned, so stopping it is what ends the sleep.
  private fun hangingBinary(): String {
    val script = File(directory, "hanging-ffmpeg")
    script.writeText("#!/bin/sh\necho $$ > '${pidFile.absolutePath}'\nexec sleep $HANG_SECONDS\n")
    check(script.setExecutable(true)) { "could not make $script executable" }
    return script.absolutePath
  }

  // The stand-in writes its pid once it is running, so this waits on the file rather than on a guess at how long a
  // spawn takes.
  private fun spawnedPid(): Long {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SPAWN_SECONDS)
    while (System.nanoTime() < deadline) {
      pidFile
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.toLongOrNull()
        ?.let { return it }
      Thread.sleep(POLL_MILLIS)
    }
    error("the stand-in ffmpeg never started")
  }

  private fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

  private fun <T> OverlayPictureDecode<T>.value(): T? = (this as? OverlayPictureRead.Read<T>)?.value

  private companion object {
    // The hop to 4:4:4 eight-bit samples at full and at limited range that comes before the matrix.
    const val FULL_HOP = "scale=in_range=pc:out_range=pc,format=yuv444p"
    const val LIMITED_HOP = "scale=in_range=tv:out_range=tv,format=yuv444p"

    val PICTURE = pngOf(OVERLAY_COLOR, OVERLAY_SIZE)
    val UNREADABLE = "not a picture the JDK reads".encodeToByteArray()
    val FRAME = Size(640, 360)
    val capabilities =
      RenderCapabilities(
        api = RenderApi.FilterGraph,
        supportsFragmentShader = false,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt2020),
        maxTextureSize = 16_384,
        features = emptySet(),
      )
    val hdrAttributes =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt2020,
        hdrTransfer = HdrTransfer.Pq,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )
    val sdrAttributes =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )
    val posix = !System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    const val SHORT_TIMEOUT = 1_500L
    const val HANG_SECONDS = 600
    const val SPAWN_SECONDS = 10L
    const val POLL_MILLIS = 10L
    const val DISK_FULL = "No space left on device"

    val WIDE = Size(40, 20)
    const val ROTATED_CLOCKWISE = 6
  }
}
