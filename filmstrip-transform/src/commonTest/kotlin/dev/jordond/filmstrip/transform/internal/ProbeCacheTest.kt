package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

// A probe suspends, so this is a cache only if a caller arriving mid-probe waits for the answer
// rather than asking the prober the same question again.
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeCacheTest {
  @Test
  fun `a read arriving while a probe is in flight spawns no probe of its own`() =
    runTest {
      val prober = GatedProber()
      val cache = ProbeCache(prober)

      val first = async { cache.read(compositionOf(SOURCE)) }
      val second = async { cache.read(compositionOf(SOURCE)) }
      runCurrent()

      prober.answer()
      val results = listOf(first.await(), second.await())

      prober.probes shouldBe 1
      results.forEach { assertIs<ProbeCacheResult.Read>(it).infos[SOURCE] shouldBe INFO }
    }

  @Test
  fun `a source named twice in one composition is read once`() =
    runTest {
      val prober = GatedProber().apply { answer() }
      val cache = ProbeCache(prober)

      val read = cache.read(compositionOf(SOURCE, SOURCE))

      prober.probes shouldBe 1
      assertIs<ProbeCacheResult.Read>(read).infos[SOURCE] shouldBe INFO
    }

  @Test
  fun `a read of one source does not wait on a probe of another`() =
    runTest {
      val prober = GatedProber()
      val cache = ProbeCache(prober)

      val parked = async { cache.read(compositionOf(SOURCE)) }
      runCurrent()

      prober.answer(OTHER)
      val other = cache.read(compositionOf(OTHER))

      assertIs<ProbeCacheResult.Read>(other).infos[OTHER] shouldBe INFO
      parked.isActive shouldBe true

      prober.answer(SOURCE)
      assertIs<ProbeCacheResult.Read>(parked.await()).infos[SOURCE] shouldBe INFO
    }

  private fun compositionOf(vararg sources: MediaSource): EditComposition =
    EditComposition(tracks = listOf(Track(sources.map { Clip(it) })), audio = AudioSpec.Remove)
}

/**
 * A prober that parks until it is told to answer, so one probe can be left in flight while a second
 * caller reaches the cache.
 */
private class GatedProber : MediaProber {
  private val gates = mutableMapOf<MediaSource, CompletableDeferred<Unit>>()

  var probes: Int = 0
    private set

  override suspend fun probe(source: MediaSource): ProbeResult {
    probes++
    gateFor(source).await()
    return ProbeResult.Success(INFO)
  }

  fun answer(source: MediaSource = SOURCE) {
    gateFor(source).complete(Unit)
  }

  private fun gateFor(source: MediaSource): CompletableDeferred<Unit> = gates.getOrPut(source) { CompletableDeferred() }
}

private val SOURCE = MediaSource.of("/fixtures/gated.mp4")

private val OTHER = MediaSource.of("/fixtures/other.mp4")

private val INFO =
  MediaInfo(
    duration = 6.seconds,
    video =
      VideoTrackInfo(
        codedSize = Size(1920, 1080),
        displaySize = Size(1920, 1080),
        rotationDegrees = 0,
        pixelAspectRatio = 1f,
        frameRate = 30f,
        codec = trackCodecOf("avc1"),
        bitDepth = 8,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        bitrate = Bitrate.mbps(12),
      ),
    audio = null,
    isExportable = true,
  )
