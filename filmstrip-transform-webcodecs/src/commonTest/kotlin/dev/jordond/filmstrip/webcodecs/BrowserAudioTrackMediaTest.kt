package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.URL
import dev.jordond.filmstrip.media.defaultAudioTrackOf
import dev.jordond.filmstrip.media.fetch
import dev.jordond.filmstrip.media.toByteArray
import dev.jordond.filmstrip.test.ThreeAudio
import dev.jordond.filmstrip.test.ThreeAudioGapped
import dev.jordond.filmstrip.test.ThreeAudioMatroska
import dev.jordond.filmstrip.test.ThreeAudioTs
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.audio.StretchedAudio
import dev.jordond.filmstrip.webcodecs.internal.fetchRange
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserAudioPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Which audio track of a source with three of them the browser probes, exports and previews.
 *
 * The probe reads the published fixtures ffmpeg wrote, as MP4, as an MP4 whose track ids skip, as Matroska and as a
 * transport stream, served beside the test bundle. The exports and previews read a copy muxed here to the same shape
 * [ThreeAudio] describes, checked against it the same way. Each track is heard by the tone it carries.
 */
class BrowserAudioTrackMediaTest {
  @Test
  fun theProbeListsEveryAudioTrackOfThePublishedFixture() =
    runTest {
      val info = BrowserProber().probe(PUBLISHED).shouldBeInstanceOf<ProbeResult.Success>().info

      ThreeAudio.assertProbed(info)
    }

  @Test
  fun theProbeListsEveryAudioTrackOfThePublishedMatroskaFixture() =
    runTest {
      val source = MediaSource.Uri("/base/kotlin/${ThreeAudioMatroska.fileName}")

      ThreeAudioMatroska.assertProbed(BrowserProber().probe(source).shouldBeInstanceOf<ProbeResult.Success>().info)
    }

  @Test
  fun theProbeListsEveryAudioTrackOfThePublishedTransportStreamFixture() =
    runTest {
      val source = MediaSource.Uri("/base/kotlin/${ThreeAudioTs.fileName}")

      // mediabunny reads no language off a transport stream.
      ThreeAudioTs.assertProbed(
        BrowserProber().probe(source).shouldBeInstanceOf<ProbeResult.Success>().info,
        readsLanguage = false,
      )
    }

  // The ids 5, 9 and 12 are no track's place, so they can only come off the track headers.
  @Test
  fun theProbeNamesTheTracksOfAGappedFixtureByTheirTrackHeaders() =
    runTest {
      ThreeAudioGapped.assertProbed(BrowserProber().probe(GAPPED).shouldBeInstanceOf<ProbeResult.Success>().info)
    }

  @Test
  fun theProbeNamesTheTracksOfAGappedFixtureHeldInMemory() =
    runTest {
      val source = MediaSource.Bytes(gappedBlob().arrayBuffer().await().toByteArray())

      ThreeAudioGapped.assertProbed(BrowserProber().probe(source).shouldBeInstanceOf<ProbeResult.Success>().info)
    }

  @Test
  fun theProbeNamesTheTracksOfAGappedFixtureBehindAnObjectUrl() =
    runTest {
      val url = URL.createObjectURL(gappedBlob())
      try {
        val info = BrowserProber().probe(MediaSource.Uri(url)).shouldBeInstanceOf<ProbeResult.Success>().info

        ThreeAudioGapped.assertProbed(info)
      } finally {
        URL.revokeObjectURL(url)
      }
    }

  @Test
  fun aRangeOfAnObjectUrlIsAnsweredWithTheRangeAlone() =
    runTest {
      val url = URL.createObjectURL(gappedBlob())
      try {
        val answer = fetchRange(url, start = 4, count = 4)

        answer.isWhole shouldBe false
        answer.bytes.decodeToString() shouldBe "ftyp"
      } finally {
        URL.revokeObjectURL(url)
      }
    }

  @Test
  fun aClipPlaysTheMiddleTrackOfAGappedFixtureByItsHeaderId() =
    runTest {
      val middle = ThreeAudioGapped.tracks[1]
      val clip = Clip(GAPPED, effects = listOf(Brightness(BRIGHTNESS)), audioTrack = middle.id)

      val audio = exported(Composition(listOf(Track(listOf(clip)))))

      middle.id shouldBe AudioTrackId(9)
      audio.samples.dominantHz(audio.sampleRate) shouldBe (middle.toneHz.toDouble() plusOrMinus DOMINANT_STEP_HZ)
    }

  @Test
  fun theProbeListsEveryAudioTrackOfTheMuxedCopy() =
    runTest {
      val info = BrowserProber().probe(threeAudio()).shouldBeInstanceOf<ProbeResult.Success>().info

      ThreeAudio.assertProbed(info)
    }

  @Test
  fun aReEncodedClipPlaysEachTrackItNames() =
    runTest {
      val source = threeAudio()
      ThreeAudio.tracks.forEach { track ->
        val clip = Clip(source, effects = listOf(Brightness(BRIGHTNESS)), audioTrack = track.id)

        assertHears(exported(Composition(listOf(Track(listOf(clip))))), track.id, "track ${track.id.value}")
      }
    }

  @Test
  fun aClipThatNamesNoTrackPlaysTheOneTheContainerFlags() =
    runTest {
      val source = threeAudio()
      val info = BrowserProber().probe(source).shouldBeInstanceOf<ProbeResult.Success>().info
      val flagged = defaultAudioTrackOf(info.audioTracks).shouldNotBeNull().id
      val clip = Clip(source, effects = listOf(Brightness(BRIGHTNESS)))

      assertHears(exported(Composition(listOf(Track(listOf(clip))))), flagged, "the default track")
    }

  @Test
  fun aCopyWritesOnlyTheTrackTheClipNames() =
    runTest {
      val last = ThreeAudio.tracks.last().id
      val composition = Composition(listOf(Track(listOf(Clip(threeAudio(), audioTrack = last)))))
      val plan = planOf(composition, ExportSpec())
      plan.path shouldBe ExportPath.Copy

      val output = outputOf(plan)

      val reader = SourceReader.of(output).shouldNotBeNull()
      try {
        reader.audioTracks().size shouldBe 1
      } finally {
        reader.close()
      }
      assertHears(decodeAudioSamples(output), last, "the copied track")
    }

  // Only a clip at another speed is handed over a slice at a time, which is what lets the test hear the preview.
  @Test
  fun aPreviewOfASpedClipHearsTheTrackItNames() =
    runTest {
      val first = ThreeAudio.tracks.first().id
      val clip = Clip(threeAudio(), audioTrack = first, speed = SPEED)
      val tracks = resolvedTracksOf(Composition(listOf(Track(listOf(clip)))))
      val sources = SourceCache()
      val slices = mutableListOf<StretchedAudio>()
      val preview = BrowserAudioPreview({ tracks }, sources) { slices += it }
      try {
        preview.start(Duration.ZERO)
      } finally {
        preview.release()
        sources.close()
      }

      val heard = slices.flatMap { it.channels[0].asList() }.toFloatArray()
      withClue("the preview scheduled ${slices.size} slices") {
        heard.dominantHz(slices.first().sampleRate) shouldBe
          (ThreeAudio.toneOf(first).toDouble() plusOrMinus DOMINANT_STEP_HZ)
      }
    }

  private suspend fun gappedBlob() = fetch(GAPPED.uri).await().blob().await()

  private suspend fun threeAudio(): MediaSource =
    MediaSource.Bytes(
      makeClipWithAudioTracks(
        tracks =
          ThreeAudio.tracks.map { track ->
            ToneTrack({ track.toneHz.toDouble() }, language = track.language, isDefault = track.isDefault)
          },
        frames = FRAMES,
      ),
    )

  private fun assertHears(
    audio: DecodedSamples,
    id: AudioTrackId,
    what: String,
  ) {
    withClue(what) {
      audio.samples.dominantHz(audio.sampleRate) shouldBe
        (ThreeAudio.toneOf(id).toDouble() plusOrMinus DOMINANT_STEP_HZ)
    }
  }

  private suspend fun exported(composition: Composition): DecodedSamples =
    decodeAudioSamples(outputOf(planOf(composition)))

  private suspend fun planOf(
    composition: Composition,
    spec: ExportSpec = autoAudioSpec(),
  ): ExportPlan =
    when (val verdict = engine().plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
    }

  private suspend fun outputOf(plan: ExportPlan): MediaSource {
    val statuses = engine().export(plan, MediaSink.Uri("")).toList()
    statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()?.let {
      throw AssertionError("the export failed: ${it.error.message}")
    }
    val success = statuses.filterIsInstance<ExportStatus.Success>().single()
    return MediaSource.Uri(success.output.shouldBeInstanceOf<MediaSink.Uri>().uri)
  }

  private suspend fun resolvedTracksOf(composition: Composition): List<ResolvedTrack> =
    when (val result = engine().resolve(composition, autoAudioSpec())) {
      is ResolveResult.Refused -> throw AssertionError("the plan was refused: ${result.error.message}")
      is ResolveResult.Resolved -> result.composition.tracks
    }

  private fun engine(): BrowserExportEngine =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    )

  private companion object {
    val PUBLISHED = MediaSource.Uri("/base/kotlin/${ThreeAudio.FILE_NAME}")
    val GAPPED = MediaSource.Uri("/base/kotlin/${ThreeAudioGapped.fileName}")
    const val FRAMES = 45
    const val BRIGHTNESS = 0.1f
    const val SPEED = 1.5
  }
}
