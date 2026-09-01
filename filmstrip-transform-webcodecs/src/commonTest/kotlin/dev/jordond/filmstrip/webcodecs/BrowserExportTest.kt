package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.CropRect
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.BrowserCompositor
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole published pipeline in a real browser: clips are synthesised, planned and exported
 * through the public facade, then decoded back through a reader that never saw the encoder.
 *
 * The assertions look at where a pixel landed, not only at how many there were. A frame counter
 * cannot tell an upside-down export from an upright one.
 */
class BrowserExportTest {
  @Test
  fun bytesPlanAndExport() =
    runTest {
      val bytes = makeClip()
      val success = exportOf(compositionOf(MediaSource.Bytes(bytes)))

      val video = assertNotNull(success.info.video)
      assertEquals(64, video.displaySize.width)
      assertEquals(64, video.displaySize.height)
      assertTrue(success.info.duration > Duration.ZERO, "duration was ${success.info.duration}")
    }

  @Test
  fun exportKeepsTheSourceUpright() =
    runTest {
      val bytes =
        makeClip { _, y ->
          if (y < HALF) Rgb.Red else Rgb.Blue
        }
      val success = exportOf(compositionOf(MediaSource.Bytes(bytes)), MediaSink.Uri(""))

      val frame = assertNotNull(decodeFrames(outputOf(success)).firstOrNull(), "the export decoded no frames")
      val top = frame.at(x = 0.5, y = 0.25)
      val bottom = frame.at(x = 0.5, y = 0.75)
      assertTrue(top.isNear(Rgb.Red), "the top of the frame was $top, and the source's top was red")
      assertTrue(bottom.isNear(Rgb.Blue), "the bottom of the frame was $bottom, and the source's bottom was blue")
    }

  @Test
  fun cropKeepsTheRequestedRegionTheRightWayUp() =
    runTest {
      // The built-in resolver builds a crop matrix as `offsetV = 1 - rect.bottom`, which is only the
      // right region once the upload is flipped. Retaining filmstrip's top half has to come out as
      // the source's top half, and nothing but it.
      val bytes = makeClip { _, y -> if (y < HALF) Rgb.Red else Rgb.Blue }
      val composition =
        EditComposition(
          tracks =
            listOf(
              Track(
                listOf(
                  Clip(MediaSource.Bytes(bytes), effects = listOf(CropRect(NormalizedRect(0f, 0f, 1f, 0.5f)))),
                ),
              ),
            ),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frame = assertNotNull(decodeFrames(outputOf(success)).firstOrNull())
      for (y in listOf(0.15, 0.5, 0.85)) {
        for (x in listOf(0.1, 0.5, 0.9)) {
          val colour = frame.at(x, y)
          assertTrue(colour.isNear(Rgb.Red), "the crop kept $colour at ($x, $y), and the source's top half was red")
        }
      }
    }

  @Test
  fun letterboxBarsAreBlackAndTheClipIsNotCropped() =
    runTest {
      // The first clip sets the output size and fills it, so anything left in the bars afterwards
      // is the previous clip rather than a clear. The second is half as tall, so it letterboxes.
      val filler = makeClip(colour = Rgb.Green)
      val wide =
        makeClip(width = 64, height = 32) { _, y ->
          if (y < WIDE_RED_ROWS) Rgb.Red else Rgb.Blue
        }

      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(filler)), Clip(MediaSource.Bytes(wide))))),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frames = decodeFrames(outputOf(success))
      assertTrue(frames.size > 1, "the export decoded ${frames.size} frames")
      val letterboxed = frames.last()

      assertTrue(
        letterboxed.at(x = 0.5, y = 0.12).isNear(Rgb.Black),
        "the top bar was ${letterboxed.at(0.5, 0.12)}, and a letterbox bar is cleared to black",
      )
      assertTrue(
        letterboxed.at(x = 0.5, y = 0.88).isNear(Rgb.Black),
        "the bottom bar was ${letterboxed.at(0.5, 0.88)}, and a letterbox bar is cleared to black",
      )
      // The drawn band runs from a quarter to three quarters of the frame, and the source's top
      // eighth is red. Sampling a quarter of the way down lands inside it only when the whole
      // source is fitted into the band rather than centre-cropped into it.
      assertTrue(
        letterboxed.at(x = 0.5, y = 0.3125).isNear(Rgb.Red),
        "the top of the letterboxed clip was ${letterboxed.at(0.5, 0.3125)}, and the source's top was red",
      )
      assertTrue(
        letterboxed.at(x = 0.5, y = 0.6).isNear(Rgb.Blue),
        "the middle of the letterboxed clip was ${letterboxed.at(0.5, 0.6)}, and the source's middle was blue",
      )
    }

  @Test
  fun aSolidFillOfANonBlackColourPaintsTheBars() =
    runTest {
      val success = exportOf(letterboxComposition(Fill.Solid(PURPLE_ARGB)), MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      assertTrue(
        letterboxed.at(x = 0.5, y = 0.12).isNear(PURPLE_RGB),
        "the top bar was ${letterboxed.at(0.5, 0.12)}, and the fill was $PURPLE_RGB",
      )
      assertTrue(
        letterboxed.at(x = 0.5, y = 0.88).isNear(PURPLE_RGB),
        "the bottom bar was ${letterboxed.at(0.5, 0.88)}, and the fill was $PURPLE_RGB",
      )
    }

  // A composition-level brightness must not reach a bar it was not given a colour for, whatever
  // it does to the clip's own pixels.
  @Test
  fun aCompositionBrightnessLeavesASolidFillsBarsUntouched() =
    runTest {
      val composition = letterboxComposition(Fill.Solid(PURPLE_ARGB), effects = listOf(Brightness(HALF_BRIGHTNESS)))
      val success = exportOf(composition, MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      assertTrue(
        letterboxed.at(x = 0.5, y = 0.12).isNear(PURPLE_RGB),
        "the top bar was ${letterboxed.at(0.5, 0.12)}, and a composition brightness should not reach it",
      )
      val dimmedBlue = Rgb(Rgb.Blue.red / 2, Rgb.Blue.green / 2, Rgb.Blue.blue / 2)
      assertTrue(
        letterboxed.at(x = 0.5, y = 0.6).isNear(dimmedBlue),
        "the clip's own pixels read ${letterboxed.at(0.5, 0.6)}, and half of ${Rgb.Blue} is $dimmedBlue",
      )
    }

  @Test
  fun aBlurredFillPaintsBarsThatAreNotFlat() =
    runTest {
      val success = exportOf(letterboxComposition(Fill.Blur), MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      // The wide clip's top eighth is red and the rest is blue, so a bar sample near the very edge
      // of the frame stays red while one closer to the sharp clip picks up blue from the blur.
      val nearEdge = letterboxed.at(x = 0.5, y = 0.03)
      val nearClip = letterboxed.at(x = 0.5, y = 0.23)
      assertTrue(
        !nearEdge.isNear(nearClip, tolerance = FLAT_TOLERANCE),
        "the bar read $nearEdge at the edge and $nearClip near the clip, which is too flat for a blur",
      )
    }

  @Test
  fun aBlurredFillLeavesTheSharpClipAlone() =
    runTest {
      val success = exportOf(letterboxComposition(Fill.Blur), MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      val centre = letterboxed.at(x = 0.5, y = 0.6)
      assertTrue(centre.isNear(Rgb.Blue), "the sharp region read $centre, and the source's middle was blue")
    }

  @Test
  fun aFullyDimmedBlurredFillIsBlack() =
    runTest {
      val success = exportOf(letterboxComposition(Fill.Blurred(dim = 1f)), MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      assertTrue(
        letterboxed.at(x = 0.5, y = 0.12).isNear(Rgb.Black),
        "a fully dimmed bar was ${letterboxed.at(0.5, 0.12)}, and dim = 1f should read black",
      )
    }

  @Test
  fun aHalfDimmedBlurredFillIsHalfAsBrightAsUndimmed() =
    runTest {
      // A multiplicative gain and an additive fade toward black agree at both ends of dim, which
      // is exactly why aFullyDimmedBlurredFillIsBlack cannot tell them apart on its own. Checking a
      // fractional dim against the same pixel undimmed is what catches an offset instead of a gain.
      val undimmed = exportOf(letterboxComposition(Fill.Blurred(dim = 0f)), MediaSink.Uri(""))
      val halfDimmed = exportOf(letterboxComposition(Fill.Blurred(dim = 0.5f)), MediaSink.Uri(""))

      val undimmedBar = decodeFrames(outputOf(undimmed)).last().at(x = 0.5, y = 0.12)
      val halfDimmedBar = decodeFrames(outputOf(halfDimmed)).last().at(x = 0.5, y = 0.12)
      val expected = Rgb(undimmedBar.red / 2, undimmedBar.green / 2, undimmedBar.blue / 2)
      assertTrue(
        halfDimmedBar.isNear(expected, tolerance = HALF_DIM_TOLERANCE),
        "half dim read $halfDimmedBar, and half of the undimmed $undimmedBar is $expected",
      )
    }

  // Halving reads as half the signal, not as a lifted black: an additive brightness of the same
  // size would land this grey near white instead.
  @Test
  fun brightnessScalesEveryChannel() =
    runTest {
      val bytes = makeClip(colour = Rgb.Grey)
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = listOf(Brightness(HALF_BRIGHTNESS)))))),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frame = assertNotNull(decodeFrames(outputOf(success)).firstOrNull())
      val dimmed = frame.at(x = 0.5, y = 0.5)
      val expected = Rgb(Rgb.Grey.red / 2, Rgb.Grey.green / 2, Rgb.Grey.blue / 2)
      assertTrue(
        dimmed.isNear(expected),
        "a half-brightness clip encoded as $dimmed, and half of ${Rgb.Grey} is $expected",
      )
    }

  @Test
  fun aRequestedFrameRateResamples() =
    runTest {
      val bytes = makeClip(frames = 12, frameRate = 30)
      val spec =
        ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, frameRate = 15)
      val success = exportOf(compositionOf(MediaSource.Bytes(bytes)), MediaSink.Uri(""), spec)

      assertEquals(15f, assertNotNull(success.info.video).frameRate)
      // Twelve frames at thirty is four hundred milliseconds, which is six slots at fifteen.
      assertEquals(6, decodeFrames(outputOf(success)).size)
    }

  @Test
  fun theSourceFrameRateIsProbedRatherThanAssumed() =
    runTest {
      val bytes = makeClip(frames = 12, frameRate = 20)
      val success = exportOf(compositionOf(MediaSource.Bytes(bytes)), MediaSink.Uri(""))

      val rate = assertNotNull(assertNotNull(success.info.video).frameRate)
      assertTrue(abs(rate - 20f) <= RATE_TOLERANCE, "the export ran at $rate, and the source runs at 20")
      // A rate that defaulted to thirty would ask for eighteen frames out of a twelve frame source.
      assertEquals(12, decodeFrames(outputOf(success)).size)
    }

  @Test
  fun trimKeepsOnlyTheRequestedWindow() =
    runTest {
      // Each frame is a shade brighter than the one before, so the first frame of the export says
      // which source frame the trim actually started from.
      val bytes = makeClip(frames = 60, frameRate = 30) { index, _ -> Rgb(index * RAMP_STEP, 0, 0) }
      val composition =
        EditComposition(
          tracks =
            listOf(
              Track(
                listOf(
                  Clip(MediaSource.Bytes(bytes), trim = TimeRange(TRIM_START, TRIM_END)),
                ),
              ),
            ),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frames = decodeFrames(outputOf(success))
      assertEquals(15, frames.size, "half a second at thirty frames a second is fifteen frames")

      val first = frames.first().at(x = 0.5, y = 0.5)
      val expected = Rgb(TRIM_START_FRAME * RAMP_STEP, 0, 0)
      assertTrue(
        first.isNear(expected),
        "the export opened on $first, and the source's frame at one second is $expected",
      )
    }

  @Test
  fun aUriSinkHandsBackABlobUrl() =
    runTest {
      val success = exportOf(compositionOf(MediaSource.Bytes(makeClip())), MediaSink.Uri(""))

      val output = assertIs<MediaSink.Uri>(success.output)
      assertTrue(output.uri.startsWith("blob:"), "a Uri sink handed back ${output.uri}")
      assertTrue(decodeFrames(MediaSource.Uri(output.uri)).isNotEmpty(), "the blob url decoded nothing")
    }

  @Test
  fun aTemporarySinkResolvesToAName() =
    runTest {
      val success = exportOf(compositionOf(MediaSource.Bytes(makeClip())), MediaSink.Temporary)

      val output = assertIs<MediaSink.Path>(success.output)
      assertTrue(output.path.endsWith(".mp4"), "a Temporary sink resolved to ${output.path}")
    }

  @Test
  fun untouchedExportCopiesPacketsRatherThanReencoding() =
    runTest {
      val bytes = makeClip()
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val sourcePackets = readEncodedVideoPackets(MediaSource.Bytes(bytes))
      val copiedPackets = readEncodedVideoPackets(outputOf(success))

      assertEquals(sourcePackets.size, copiedPackets.size, "the copy carried a different packet count")
      sourcePackets.zip(copiedPackets).forEachIndexed { index, (expected, actual) ->
        assertTrue(expected.contentEquals(actual), "packet $index differed between the source and the copy")
      }
    }

  // On a copy, ExportPlanner reports the source's own codec rather than the ladder's pick, and
  // VideoCodec has entries this backend never encodes. Planning and exporting a VP8 or AV1 source
  // must still copy it rather than throw building an encoder config.
  @Test
  fun untouchedVp8ExportCopiesPacketsRatherThanReencoding() =
    runTest {
      val bytes = makeClipInCodec("vp8")
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val sourcePackets = readEncodedVideoPackets(MediaSource.Bytes(bytes))
      val copiedPackets = readEncodedVideoPackets(outputOf(success))

      assertEquals(sourcePackets.size, copiedPackets.size, "the copy carried a different packet count")
      sourcePackets.zip(copiedPackets).forEachIndexed { index, (expected, actual) ->
        assertTrue(expected.contentEquals(actual), "packet $index differed between the source and the copy")
      }
    }

  @Test
  fun untouchedAv1ExportCopiesPacketsRatherThanReencoding() =
    runTest {
      val bytes = makeClipInCodec("av1")
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val sourcePackets = readEncodedVideoPackets(MediaSource.Bytes(bytes))
      val copiedPackets = readEncodedVideoPackets(outputOf(success))

      assertEquals(sourcePackets.size, copiedPackets.size, "the copy carried a different packet count")
      sourcePackets.zip(copiedPackets).forEachIndexed { index, (expected, actual) ->
        assertTrue(expected.contentEquals(actual), "packet $index differed between the source and the copy")
      }
    }

  // A copy is the one path that carries sound: it takes the source's compressed audio packets
  // across untouched, the same way it does video.
  @Test
  fun untouchedExportCarriesAudioThrough() =
    runTest {
      val bytes = makeClipWithAudio()
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val audio = assertNotNull(decodeAudio(outputOf(success)), "the copy produced no readable audio track")
      assertTrue(audio.sampleCount > 0, "the copy's audio track decoded no samples")
      assertTrue(audio.durationUs > 0, "the copy's audio track had no duration")
    }

  // A trim forces the encode path rather than a copy, which is the one path that decodes, mixes and
  // re-encodes audio rather than carrying compressed packets across untouched.
  @Test
  fun aTranscodedExportCarriesAudibleAudio() =
    runTest {
      if (!encodesAac()) return@runTest
      val bytes = makeClipWithAudio(frames = 60, frameRate = 30)
      val composition =
        EditComposition(
          tracks =
            listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), trim = TimeRange(TRIM_START, TRIM_END))))),
          audio = AudioSpec.Keep,
        )
      val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
      val success = exportOf(composition, MediaSink.Uri(""), spec)

      val audio = assertNotNull(decodeAudio(outputOf(success)), "the export produced no readable audio track")
      assertTrue(audio.durationUs > 0, "the export's audio track had no duration")
      assertTrue(
        audio.peakAmplitude > SILENCE_THRESHOLD,
        "the export's audio peaked at ${audio.peakAmplitude}, expected an audible tone",
      )
    }

  // The second-track refusal covers a video-carrying track only, which is what lets a bed reach
  // the mix at all.
  @Test
  fun aMusicBedOnASecondAudioOnlyTrackReachesTheOutput() =
    runTest {
      if (!encodesAac()) return@runTest
      val video = makeClip(frames = 30, frameRate = 30)
      val bed = makeClipWithAudio(frames = 30, frameRate = 30)
      val composition =
        EditComposition(
          tracks =
            listOf(
              Track(listOf(Clip(MediaSource.Bytes(video)))),
              Track(listOf(Clip(MediaSource.Bytes(bed))), content = TrackContent.Audio),
            ),
          audio = AudioSpec.Keep,
        )
      val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
      val success = exportOf(composition, MediaSink.Uri(""), spec)

      val audio = assertNotNull(decodeAudio(outputOf(success)), "the export produced no readable audio track")
      assertTrue(
        audio.peakAmplitude > SILENCE_THRESHOLD,
        "the bed's tone did not reach the output, peak was ${audio.peakAmplitude}",
      )
    }

  // Proves ResolvedClip.gain reaches the mix end to end, rather than only in BrowserAudioMixTest's
  // isolated graph.
  @Test
  fun aMutedClipEncodesSilence() =
    runTest {
      if (!encodesAac()) return@runTest
      val bytes = makeClipWithAudio(frames = 30, frameRate = 30)
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes)).withAudio(AudioLevel.Mute)))),
          audio = AudioSpec.Keep,
        )
      val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
      val success = exportOf(composition, MediaSink.Uri(""), spec)

      val audio = assertNotNull(decodeAudio(outputOf(success)), "the export produced no readable audio track")
      assertTrue(
        audio.peakAmplitude < SILENCE_THRESHOLD,
        "a muted clip's export peaked at ${audio.peakAmplitude}, expected silence",
      )
    }

  // AudioSpec.AudioOnly asks for a file with an audio track and no video track, so the video
  // pipeline never runs and nothing writes a video track to the output.
  @Test
  fun anAudioOnlyExportWritesNoVideoTrack() =
    runTest {
      if (!encodesAac()) return@runTest
      val success = exportOf(audioOnlyComposition(), MediaSink.Uri(""), aacSpec())

      assertFalse(hasVideoTrack(outputOf(success)), "an audio-only export wrote a video track")
    }

  @Test
  fun anAudioOnlyExportCarriesAudibleAudio() =
    runTest {
      if (!encodesAac()) return@runTest
      val success = exportOf(audioOnlyComposition(), MediaSink.Uri(""), aacSpec())

      val audio = assertNotNull(decodeAudio(outputOf(success)), "the export produced no readable audio track")
      assertTrue(audio.sampleCount > 0, "the export's audio track decoded no samples")
      assertTrue(
        audio.peakAmplitude > SILENCE_THRESHOLD,
        "the export's audio peaked at ${audio.peakAmplitude}, expected an audible tone",
      )
    }

  @Test
  fun anAudioOnlyExportReportsAnAudioTrackAndNoVideoOne() =
    runTest {
      if (!encodesAac()) return@runTest
      val success = exportOf(audioOnlyComposition(), MediaSink.Uri(""), aacSpec())

      assertNull(success.info.video, "an audio-only export reported a video track")
      val audio = assertNotNull(success.info.audio, "an audio-only export reported no audio track")
      assertTrue(audio.sampleRate > 0, "the reported track ran at ${audio.sampleRate} hertz")
      assertTrue(audio.channelCount > 0, "the reported track carried ${audio.channelCount} channels")

      val drift = (success.info.duration - AUDIO_ONLY_DURATION).absoluteValue
      assertTrue(drift < DURATION_TOLERANCE, "the export ran ${success.info.duration}, the composition runs 1s")
    }

  // The mirror of the audio-only case. A render that writes video writes the mix into the same
  // file, so the info has to name both tracks rather than only the one the path is named after.
  @Test
  fun anExportCarryingBothTracksReportsBothOfThem() =
    runTest {
      if (!encodesAac()) return@runTest
      val bytes = makeClipWithAudio(frames = 30, frameRate = 30)
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), aacSpec())

      assertNotNull(success.info.video, "an export carrying both tracks reported no video track")
      val audio = assertNotNull(success.info.audio, "an export carrying both tracks reported no audio track")
      assertTrue(audio.sampleRate > 0, "the reported track ran at ${audio.sampleRate} hertz")
      assertTrue(audio.channelCount > 0, "the reported track carried ${audio.channelCount} channels")
    }

  @Test
  fun anAudioOnlyExportLandsAsM4a() =
    runTest {
      if (!encodesAac()) return@runTest
      val success = exportOf(audioOnlyComposition(), MediaSink.Temporary, aacSpec())

      val output = assertIs<MediaSink.Path>(success.output)
      assertTrue(output.path.endsWith(".m4a"), "an audio-only export resolved to ${output.path}")
    }

  @Test
  fun compositorsAreHandedBack() {
    // A browser keeps around sixteen live WebGL contexts, but headless Chrome evicts the oldest
    // rather than refusing the next, so what this holds is only that the release path runs cleanly
    // however many exports a page makes.
    repeat(CONTEXT_CHURN) { BrowserCompositor.create(64, 64, Fill.Black).release() }
  }

  @Test
  fun cancellingStopsBeforeSuccess() =
    runTest {
      val filmstrip = filmstrip()
      val plan = planOf(filmstrip, compositionOf(MediaSource.Bytes(makeClip(frames = 60))))

      val statuses =
        filmstrip
          .export(plan, MediaSink.Uri(""))
          .takeWhile { it !is ExportStatus.Progress }
          .toList()

      assertTrue(
        statuses.none { it is ExportStatus.Success },
        "a cancelled export still finished: ${statuses.map { it::class.simpleName }}",
      )
    }

  private fun filmstrip(): Filmstrip = Filmstrip { webCodecsBackend() }

  private fun compositionOf(source: MediaSource): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(source)))),
      audio = AudioSpec.Remove,
    )

  /**
   * The same two-clip letterbox fixture as [letterboxBarsAreBlackAndTheClipIsNotCropped], with a
   * caller-chosen fill so the bars can be asserted against something other than the default black,
   * and an optional composition-level effect chain.
   */
  private suspend fun letterboxComposition(
    fill: Fill,
    effects: List<EffectSpec> = emptyList(),
  ): EditComposition {
    val filler = makeClip(colour = Rgb.Green)
    val wide = makeClip(width = 64, height = 32) { _, y -> if (y < WIDE_RED_ROWS) Rgb.Red else Rgb.Blue }
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(filler)), Clip(MediaSource.Bytes(wide))))),
      effects = effects,
      audio = AudioSpec.Remove,
      fill = fill,
    )
  }

  /**
   * One second of video with a tone under it, asked for as audio and nothing else.
   */
  private suspend fun audioOnlyComposition(): EditComposition {
    val bytes = makeClipWithAudio(frames = 30, frameRate = 30)
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
      audio = AudioSpec.AudioOnly,
    )
  }

  private fun aacSpec(): ExportSpec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)

  /**
   * Whether this browser can encode AAC, which is the same question the backend's own probe asks.
   *
   * Chrome hands AAC encoding to the platform, so it is there on macOS and Windows and absent on
   * Linux, where every headless CI runner lives. Decoding is built in everywhere, which is why a
   * copy carries a source's AAC across on a browser that cannot write any.
   */
  private suspend fun encodesAac(): Boolean {
    val result = filmstrip().capabilities()
    val capabilities = assertIs<CapabilitiesResult.Success>(result).capabilities
    return capabilities.audio.any { it.codec == AudioCodec.Aac }
  }

  private suspend fun planOf(
    filmstrip: Filmstrip,
    composition: EditComposition,
    spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None),
  ): ExportPlan =
    when (val verdict = filmstrip.plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
    }

  private suspend fun exportOf(
    composition: EditComposition,
    to: MediaSink = MediaSink.Temporary,
    spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None),
  ): ExportStatus.Success {
    val filmstrip = filmstrip()
    val statuses = filmstrip.export(planOf(filmstrip, composition, spec), to).toList()
    val failure = statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()
    if (failure != null) throw AssertionError("the export failed: ${failure.error.message}")
    return assertNotNull(
      statuses.filterIsInstance<ExportStatus.Success>().singleOrNull(),
      "statuses were ${statuses.map { it::class.simpleName }}",
    )
  }

  private fun outputOf(success: ExportStatus.Success): MediaSource =
    MediaSource.Uri(assertIs<MediaSink.Uri>(success.output).uri)

  private companion object {
    const val HALF = 32
    const val HALF_BRIGHTNESS = 0.5f
    const val WIDE_RED_ROWS = 8
    const val FLAT_TOLERANCE = 20
    const val HALF_DIM_TOLERANCE = 24
    const val PURPLE_ARGB = 0xFFA060C8.toInt()
    val PURPLE_RGB = Rgb(0xA0, 0x60, 0xC8)
    const val RATE_TOLERANCE = 2f
    const val RAMP_STEP = 4
    val TRIM_START = 1000.milliseconds
    val TRIM_END = 1500.milliseconds
    const val TRIM_START_FRAME = 30
    const val CONTEXT_CHURN = 24
    const val SILENCE_THRESHOLD = 0.01f
    val AUDIO_ONLY_DURATION = 1.seconds

    // An AAC encoder pads the last frame out to its own block size, so the file runs a little past
    // the composition however exact the mix was.
    val DURATION_TOLERANCE = 250.milliseconds
  }
}
