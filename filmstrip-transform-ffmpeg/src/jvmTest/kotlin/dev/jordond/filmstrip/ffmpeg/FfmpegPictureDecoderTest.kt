package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_COLOUR
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.interruptibly
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The child processes an overlay picture is read through, which never outlive the read.
 *
 * A stand-in for ffmpeg that records its process id and then sleeps is what a hung decode looks like from here, so a
 * test can stop it part way and prove it is gone.
 */
class FfmpegPictureDecoderTest {
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
    runBlocking {
      if (!posix) return@runBlocking
      val resolver = BuiltInEffectResolver(FfmpegPictureDecoder(hangingBinary(), "ffprobe", scratch = scratch))
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

      assertFalse(alive(pid), "the decode's ffmpeg is still running after the plan was cancelled")
      assertTrue(planning.isCancelled, "the plan did not end cancelled")
      assertNull(resolved.get(), "the plan carried on with ${resolved.get()} after it was cancelled")
      assertIs<InterruptedException>(thrown.get(), "the interrupt never reached the plan")
      assertTrue(scratch.list().orEmpty().isEmpty(), "the picture's bytes were left in ${scratch.list()?.toList()}")
    }

  @Test
  fun `a decode that runs past its timeout is stopped and says why`() {
    if (!posix) return
    val decoder = FfmpegPictureDecoder(hangingBinary(), "ffprobe", timeoutMillis = SHORT_TIMEOUT, scratch = scratch)

    val refused = assertIs<OverlayPictureRead.Refused>(decoder.decode(ImageSource.ofBytes(PICTURE)))

    assertFalse(alive(spawnedPid()), "the decode's ffmpeg outlived its timeout")
    assertTrue(refused.reason.contains("$SHORT_TIMEOUT ms"), refused.reason)
    assertTrue(scratch.list().orEmpty().isEmpty(), "the picture's bytes were left in ${scratch.list()?.toList()}")
  }

  @Test
  fun `picture bytes that fail to write leave no file behind and spawn nothing`() {
    if (!posix) return
    val decoder =
      FfmpegPictureDecoder(hangingBinary(), "ffprobe", scratch = scratch) { file, bytes ->
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        throw IOException(DISK_FULL)
      }

    val refused = assertIs<OverlayPictureRead.Refused>(decoder.decode(ImageSource.ofBytes(PICTURE)))

    assertEquals(DISK_FULL, refused.reason)
    assertTrue(scratch.list().orEmpty().isEmpty(), "the half written picture was left in ${scratch.list()?.toList()}")
    assertFalse(pidFile.exists(), "ffmpeg was spawned on a picture that never finished writing")
  }

  @Test
  fun `measures a WebP the JDK cannot read at its own size`() {
    if (!fixture.available()) return
    val webp = encoded(PICTURE, "webp", listOf("-c:v", "libwebp", "-lossless", "1")) ?: return
    val decoder = FfmpegPictureDecoder("ffmpeg", "ffprobe", scratch = scratch)

    assertEquals(OVERLAY_SIZE, decoder.measure(ImageSource.ofBytes(webp.readBytes())).value())
    assertEquals(OVERLAY_SIZE, decoder.measure(ImageSource.of(webp.absolutePath)).value())
    assertTrue(scratch.list().orEmpty().isEmpty(), "the picture's bytes were left in ${scratch.list()?.toList()}")
    webp.delete()
  }

  @Test
  fun `a picture ffmpeg cannot read is refused with its own words`() {
    if (!fixture.available()) return
    val garbage = ImageSource.ofBytes("not a picture".encodeToByteArray())
    val decoder = FfmpegPictureDecoder("ffmpeg", "ffprobe", scratch = scratch)
    val reasons =
      listOf(decoder.decode(garbage), decoder.measure(garbage)).map { assertIs<OverlayPictureRead.Refused>(it).reason }

    assertTrue(reasons.all { it.isNotBlank() }, "a failed read said nothing: $reasons")
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

  private fun <T> OverlayPictureRead<T>.value(): T? = (this as? OverlayPictureRead.Read<T>)?.value

  private companion object {
    val PICTURE = pngOf(OVERLAY_COLOUR, OVERLAY_SIZE)
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
    val posix = !System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    const val SHORT_TIMEOUT = 1_500L
    const val HANG_SECONDS = 600
    const val SPAWN_SECONDS = 10L
    const val POLL_MILLIS = 10L
    const val DISK_FULL = "No space left on device"
  }
}
