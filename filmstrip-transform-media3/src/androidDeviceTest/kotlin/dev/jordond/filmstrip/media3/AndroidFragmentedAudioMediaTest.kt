package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.movieLengthOf
import dev.jordond.filmstrip.test.PrimedAudio
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Plans and exports a fragmented audio-only MP4 whose AAC track opens on an encoder delay its edit list skips, laid as
 * an audio track under a shorter clip, and checks media3 lays and writes it at the length core reads off its fragments.
 */
class AndroidFragmentedAudioMediaTest {
  private val probe = Media3ExportProbe()
  private val written = mutableListOf<File>()

  @AfterTest
  fun remove() {
    written.forEach { it.delete() }
  }

  @Test
  fun aFragmentedClipWithAnEditIsLaidAndWrittenAtItsPresentedLength() =
    runTest(timeout = TIMEOUT) {
      assertWrittenAtSharedLength(PrimedAudio.fragmentedMp4())
    }

  @Test
  fun aFragmentedClipWithNoEditIsLaidAndWrittenAtEveryTickOfItsFragments() =
    runTest(timeout = TIMEOUT) {
      assertWrittenAtSharedLength(PrimedAudio.fragmentedMp4(edit = false))
    }

  // A trim inside the track's first unit opens its decode on that unit, which the edit puts before zero.
  @Test
  fun aFragmentedClipTrimmedInsideItsFirstUnitIsWrittenAtItsTrimmedLength() =
    runTest(timeout = TIMEOUT) {
      assertWrittenAtSharedLength(PrimedAudio.fragmentedMp4(), trimStart = 10.milliseconds)
    }

  private suspend fun assertWrittenAtSharedLength(
    bytes: ByteArray,
    trimStart: Duration = Duration.ZERO,
  ) {
    val whole = sharedLengthOf(bytes)
    val shared = whole - trimStart
    val source = File(probe.context.cacheDir, "fragmented-aac-${System.nanoTime()}.mp4").apply { writeBytes(bytes) }
    written += source
    val bed =
      Track(listOf(Clip(MediaSource.of(source.path), TimeRange.of(trimStart, whole))), content = TrackContent.Audio)
    val composition = Composition(listOf(Track(listOf(Clip(probe.fixture()))), bed)).withAudio(AudioSpec.AudioOnly)

    val plan = probe.plan(composition, ExportSpec())
    val file = probe.export(plan).also { written += it }
    val audio = decodeAudio(file)

    plan.duration shouldBe shared
    val planned = audioFramesOf(shared, audio.sampleRate)
    val frames = audio.samples.size.toLong()
    withClue("wrote $frames frames of audio for $shared, $planned frames, ${frames - planned} over") {
      frames shouldBeInRange planned - AAC_FRAME..planned + AAC_TAIL_FRAMES
    }
  }

  private suspend fun sharedLengthOf(bytes: ByteArray): Duration =
    movieLengthOf(bytes.size.toLong()) { start, count ->
      bytes.copyOfRange(start.toInt(), minOf(bytes.size, start.toInt() + count))
    }.shouldNotBeNull()

  private companion object {
    val TIMEOUT = 3.minutes
    const val AAC_FRAME = PrimedAudio.AAC_FRAME.toLong()

    // The AAC encoder's limit: it rounds the stream up to a whole access unit and flushes some of another.
    const val AAC_TAIL_FRAMES = 1_536L
  }
}
