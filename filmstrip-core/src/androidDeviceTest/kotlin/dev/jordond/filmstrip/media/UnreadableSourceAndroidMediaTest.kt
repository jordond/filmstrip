package dev.jordond.filmstrip.media

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.internal.NO_READABLE_TRACK
import dev.jordond.filmstrip.internal.PlatformProber
import dev.jordond.filmstrip.test.PrimedAudio
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Probes a file the shell wrote under `/data/local/tmp`, which the app can open but whose media server may not be
 * allowed to read it, and checks the platform prober either reads its track or reports it unreadable, never a source
 * with no tracks. A stock emulator's SELinux policy denies the media server and a Samsung phone's allows it.
 */
class UnreadableSourceAndroidMediaTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val shellPath = "/data/local/tmp/filmstrip-unreadable-${System.nanoTime()}.mp4"
  private val cached = File(context.cacheDir, "readable-${System.nanoTime()}.mp4")

  @AfterTest
  fun removeFiles() {
    shell("rm -f $shellPath")
    cached.delete()
  }

  @Test
  fun aFileTheMediaServerMayNotReadIsReadInFullOrUnreadable() =
    runTest {
      // Writing to the shell's own directory needs a shell with stdin, which UiAutomation hands out from API 31.
      assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      writeAsShell(shellPath, PrimedAudio.mp4())

      val result = PlatformProber().probe(MediaSource.of(shellPath))

      when (result) {
        is ProbeResult.Success -> {
          result.info.audioTracks.shouldNotBeEmpty()
        }
        is ProbeResult.Failure -> {
          val error = result.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
          error.source shouldBe MediaSource.of(shellPath).describe()
          error.message shouldStartWith NO_READABLE_TRACK
        }
      }
    }

  @Test
  fun theSameBytesTheAppOwnsStillRead() =
    runTest {
      cached.writeBytes(PrimedAudio.mp4())

      val result = PlatformProber().probe(MediaSource.of(cached.path))

      result
        .shouldBeInstanceOf<ProbeResult.Success>()
        .info.audioTracks
        .shouldNotBeEmpty()
    }

  private fun writeAsShell(
    path: String,
    bytes: ByteArray,
  ) {
    // UiAutomation splits a command on spaces and runs it without a shell, so there is no redirect to write with.
    val (stdout, stdin) = instrumentation.uiAutomation.executeShellCommandRw("dd of=$path")
    ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(bytes) }
    ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    shell("chmod 644 $path")
    shell("ls $path").trim() shouldBe path
  }

  private fun shell(command: String): String =
    ParcelFileDescriptor
      .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
      .use { it.readBytes().decodeToString() }
}
