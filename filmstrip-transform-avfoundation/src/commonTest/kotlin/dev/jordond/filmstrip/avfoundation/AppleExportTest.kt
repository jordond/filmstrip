package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TICK
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.copyFormatDescription
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.LoopingCases
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.passesCovering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.runLengthOf
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.kCMFormatDescriptionExtension_FormatName
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The end-to-end pass over real clips, real AVFoundation, real VideoToolbox and a real file, read
 * back with the prober.
 *
 * A case fails when `FILMSTRIP_FIXTURES` is unset or names a directory missing its clip. Whether VideoToolbox's
 * encoders are hardware is asserted per target, outside this class.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleExportTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `reports what the encoders can do`() =
    runTest(timeout = TIMEOUT) {
      val result = withContext(Dispatchers.Default) { filmstrip.capabilities() }
      val capabilities = result.shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities

      withClue("expected at least one video encoder") { capabilities.video.isNotEmpty().shouldBeTrue() }
      capabilities.audio
        .single()
        .codec.name shouldBe "Aac"

      // The encoder tests pin each target's HDR answer, so this only checks the HEVC encoder that
      // answer rides on is listed.
      withClue("expected an HEVC encoder in the listing") {
        capabilities.video.any { it.codec == VideoCodec.Hevc }.shouldBeTrue()
      }
    }

  // One clip, trimmed, scaled, written and read back at the size the plan promised.
  @Test
  fun `exports one clip trimmed and scaled`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(500.milliseconds, 1_500.milliseconds) }
        }

      val output = temporaryPath("trimmed")
      val success = exported(composition, ExportSpec(heightPx = 240), output)

      success.info.video!!.displaySize shouldBe Size(426, 240)
      withClue("duration was ${success.info.duration}") {
        (success.info.duration in 800.milliseconds..1_300.milliseconds).shouldBeTrue()
      }
      withClue("the audio track did not survive") { success.info.audio.shouldNotBeNull() }

      remove(output)
    }

  @Test
  fun `concatenates clips of differing sizes onto one output frame`() =
    runTest(timeout = TIMEOUT) {
      val first = fixture("apple_export_a.mp4")
      val second = fixture("apple_export_b.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(first)) { trim(0.seconds, 1.seconds) }
          clip(MediaSource.of(second)) { trim(0.seconds, 1.seconds) }
        }

      val output = temporaryPath("concat")
      val success = exported(composition, ExportSpec(heightPx = 240), output)

      success.info.video!!.displaySize shouldBe Size(426, 240)
      withClue("duration was ${success.info.duration}") {
        (success.info.duration in 1_800.milliseconds..2_300.milliseconds).shouldBeTrue()
      }

      remove(output)
    }

  @Test
  fun `reframes a landscape clip to portrait`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          effects { crop(AspectRatio.Portrait, Fit.Crop) }
        }

      val output = temporaryPath("reframe")
      val success = exported(composition, ExportSpec(heightPx = 640), output)

      val size = success.info.video!!.displaySize
      withClue("expected a portrait frame, got $size") { (size.height > size.width).shouldBeTrue() }

      remove(output)
    }

  @Test
  fun `letterboxes and does not distort when the aspect does not match`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture("apple_export_portrait.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(portrait)) { trim(0.seconds, 1.seconds) }
          effects { scale(240, Fit.Contain) }
        }

      val output = temporaryPath("letterbox")
      val success = exported(composition, ExportSpec(), output)

      // Contain keeps the source aspect and writes the bars, so the frame stays the shape the clip
      // was instead of being stretched into the output's.
      success.info.video!!.displaySize shouldBe Size(134, 240)

      remove(output)
    }

  @Test
  fun `fills the letterbox bars with a solid color`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")
      val teal = Triple(0x11, 0xC2, 0xAA)

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          fill(Fill.Solid(teal.toArgb()))
          effects { crop(AspectRatio.Square, Fit.Contain) }
        }

      val output = temporaryPath("solid-fill")
      exported(composition, ExportSpec(), output)

      assertClose(samplePixel(output, 0.5f, 0.05f), teal, "top bar")
      assertClose(samplePixel(output, 0.5f, 0.95f), teal, "bottom bar")

      remove(output)
    }

  // The fill-paint-order regression: a composition brightness must not reach a bar it was not
  // given a color for, whatever it does to the clip's own pixels.
  @Test
  fun `a composition brightness leaves a solid fill's bars untouched`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")
      val teal = Triple(0x11, 0xC2, 0xAA)

      suspend fun exportedWith(
        name: String,
        factor: Float?,
      ): String {
        val composition =
          compositionOf {
            clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
            fill(Fill.Solid(teal.toArgb()))
            effects {
              crop(AspectRatio.Square, Fit.Contain)
              if (factor != null) brightness(factor)
            }
          }
        val output = temporaryPath("solid-fill-brightness-$name")
        exported(composition, ExportSpec(), output)
        return output
      }

      val plain = exportedWith("plain", null)
      val dimmed = exportedWith("dimmed", 0.5f)

      assertClose(samplePixel(dimmed, 0.5f, 0.05f), teal, "top bar")
      assertClose(samplePixel(dimmed, 0.5f, 0.95f), teal, "bottom bar")

      val fullCentre = luminance(samplePixel(plain, 0.5f, 0.5f))
      val dimCentre = luminance(samplePixel(dimmed, 0.5f, 0.5f))
      val ratio = dimCentre.toFloat() / fullCentre.toFloat()
      withClue("expected the centre near half brightness, got $ratio ($fullCentre -> $dimCentre)") {
        (ratio in 0.35f..0.65f).shouldBeTrue()
      }

      remove(plain)
      remove(dimmed)
    }

  // The same claim for the half of a matrix a brightness does not have. A contrast carries a bias,
  // Core Image holds the frame premultiplied, and an offset landing where the picture has no alpha
  // is exactly what would tint the bar the fill was given to own. The clip is a flat photo, so the
  // centre is a color the effect is predicted to move rather than one that merely differs.
  @Test
  fun `a composition contrast leaves a solid fill's bars untouched`() =
    runTest(timeout = TIMEOUT) {
      val teal = Triple(0x11, 0xC2, 0xAA)

      suspend fun exportedWith(
        name: String,
        factor: Float?,
      ): String {
        val composition =
          compositionOf {
            image(ImageSource.of(photoFile()), PHOTO)
            fill(Fill.Solid(teal.toArgb()))
            effects {
              crop(AspectRatio.Square, Fit.Contain)
              if (factor != null) contrast(factor)
            }
          }
        val output = temporaryPath("solid-fill-contrast-$name")
        exported(composition, ExportSpec(), output)
        return output
      }

      val plain = exportedWith("plain", null)
      val firmer = exportedWith("firmer", FIRMER)

      val graded = frameOf(firmer, PHOTO * MIDDLE)
      assertClose(graded.average(0.5f, 0.05f), teal, "top bar")
      assertClose(graded.average(0.5f, 0.95f), teal, "bottom bar")

      val before = frameOf(plain, PHOTO * MIDDLE).average(0.5f, 0.5f)
      val after = graded.average(0.5f, 0.5f)
      withClue("the contrast never reached the picture, which read $before plain and $after graded") {
        (distance(before, after) > COLOR_TOLERANCE).shouldBeTrue()
      }

      remove(plain)
      remove(firmer)
    }

  // Nothing here authors a fill, so this pins the default against a regression: the bars stay
  // black, the way every export wrote them before a fill could be chosen.
  @Test
  fun `leaves the letterbox bars black by default`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          effects { crop(AspectRatio.Square, Fit.Contain) }
        }

      val output = temporaryPath("black-fill")
      exported(composition, ExportSpec(), output)

      assertClose(samplePixel(output, 0.5f, 0.05f), BLACK, "top bar")
      assertClose(samplePixel(output, 0.5f, 0.95f), BLACK, "bottom bar")

      remove(output)
    }

  @Test
  fun `blurs the letterbox bars from the clip's own frame`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          fill(Fill.Blur)
          effects { crop(AspectRatio.Square, Fit.Contain) }
        }

      val output = temporaryPath("blurred-fill")
      exported(composition, ExportSpec(), output)

      val near = samplePixel(output, 0.15f, 0.05f)
      val far = samplePixel(output, 0.85f, 0.05f)

      near shouldNotBe BLACK
      withClue("expected the blur to vary across the bar, got $near and $far") {
        (distance(near, far) > COLOR_TOLERANCE).shouldBeTrue()
      }

      remove(output)
    }

  // The blur only ever reaches the bars, never the clip's own pixels. Comparing the centre against
  // the same composition exported with a solid fill instead is what proves that: nothing else here
  // tells the two exports apart, so a centre that still matches means the composite never covered
  // the clip.
  @Test
  fun `keeps the clip sharp under a blurred fill`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      suspend fun exportedWith(fill: Fill): String {
        val composition =
          compositionOf {
            clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
            fill(fill)
            effects { crop(AspectRatio.Square, Fit.Contain) }
          }
        val output = temporaryPath("blurred-sharp-${fill::class.simpleName}")
        exported(composition, ExportSpec(), output)
        return output
      }

      val blurred = exportedWith(Fill.Blur)
      val reference = exportedWith(Fill.Black)

      assertClose(samplePixel(blurred, 0.5f, 0.5f), samplePixel(reference, 0.5f, 0.5f), "clip centre")

      remove(blurred)
      remove(reference)
    }

  // dim is documented as a multiply, not an offset, so half dim should land the background
  // roughly halfway to black. An endpoint alone would not catch an offset standing in for a
  // multiply, since both reach black at dim = 1.
  @Test
  fun `dims the letterbox bars proportionally`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      suspend fun exportedWith(
        name: String,
        fill: Fill,
      ): String {
        val composition =
          compositionOf {
            clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
            fill(fill)
            effects { crop(AspectRatio.Square, Fit.Contain) }
          }
        val output = temporaryPath("dim-$name")
        exported(composition, ExportSpec(), output)
        return output
      }

      val undimmed = exportedWith("undimmed", Fill.Blur)
      val halfDimmed = exportedWith("half", Fill.Blurred(dim = 0.5f))

      val full = samplePixel(undimmed, 0.15f, 0.05f)
      val half = samplePixel(halfDimmed, 0.15f, 0.05f)
      val ratio = luminance(half).toFloat() / luminance(full).toFloat()

      withClue("expected half dim near half brightness, got $ratio ($full -> $half)") {
        (ratio in 0.35f..0.65f).shouldBeTrue()
      }

      remove(undimmed)
      remove(halfDimmed)
    }

  // Crop covers the frame itself, so the bars a Contain fit would need never open up. Sampling both
  // edges with a loud fill chosen is what proves it costs nothing when there is nowhere for it to
  // show.
  @Test
  fun `writes no bars when the fill cannot be seen`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")
      val magenta = Triple(0xFF, 0x00, 0xFF)

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          fill(Fill.Solid(magenta.toArgb()))
          // resolved.fit, which the final pin to the output frame reads, only ever comes from a
          // Scale effect's own fit. The crop alone would leave it defaulted to Contain.
          effects {
            crop(AspectRatio.Square, Fit.Crop)
            scale(300, Fit.Crop)
          }
        }

      val output = temporaryPath("crop-fill")
      exported(composition, ExportSpec(), output)

      withClue("the fill leaked into the top edge") {
        (distance(samplePixel(output, 0.5f, 0.02f), magenta) > COLOR_TOLERANCE).shouldBeTrue()
      }
      withClue("the fill leaked into the bottom edge") {
        (distance(samplePixel(output, 0.5f, 0.98f), magenta) > COLOR_TOLERANCE).shouldBeTrue()
      }

      remove(output)
    }

  /**
   * The plan claims a copy, so the file has to prove one ran.
   *
   * An encoder stamps its own name into the format description, so the source's survives a copy and
   * not a re-encode. The trim is the control: it is the same export down to the path the planner
   * picks, and a reader that always answered null would pass the first claim on its own. It cuts
   * off a sync sample and says nothing about moving, which is what leaves the copy out of reach.
   */
  @Test
  fun `copies the streams across when nothing touches them`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")
      val source = formatName(landscape)
      withClue("the fixture's own format description could not be read") { source.shouldNotBeNull() }

      val output = temporaryPath("copy")
      val composition = compositionOf { clip(MediaSource.of(landscape)) }
      val plan = filmstrip.plan(composition, ExportSpec()).shouldBeInstanceOf<Verdict.Capable>().plan
      plan.path shouldBe ExportPath.Copy
      exported(composition, ExportSpec(), output)
      formatName(output) shouldBe source

      val reEncoded = temporaryPath("transcode")
      val trimmed =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(500.milliseconds, 1.seconds) }
        }
      val control = filmstrip.plan(trimmed, ExportSpec()).shouldBeInstanceOf<Verdict.Capable>().plan
      control.path shouldBe ExportPath.Transcode
      exported(trimmed, ExportSpec(), reEncoded)
      withClue("the trimmed export passed the source's format off as its own") {
        formatName(reEncoded) shouldNotBe source
      }

      remove(output)
      remove(reEncoded)
    }

  @Test
  fun `writes a silent track when a clip is muted`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) {
            trim(0.seconds, 1.seconds)
            audio(AudioLevel.Mute)
          }
        }

      val output = temporaryPath("muted")
      val success = exported(composition, ExportSpec(heightPx = 240), output)

      // Mute keeps the track and writes silence into it, which is a different operation from
      // dropping it.
      withClue("the muted track was dropped and not silenced") { success.info.audio.shouldNotBeNull() }

      remove(output)
    }

  // The whole point of AudioSpec.AudioOnly is a file with no video track, so an assertion that only
  // checked the audio survived would pass even if a full video track were written alongside it.
  @Test
  fun `writes an audio track and drops video when the composition is audio-only`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          audio(AudioSpec.AudioOnly)
        }

      val output = temporaryPath("audio-only")
      val success = exported(composition, ExportSpec(), output)

      val audio = withClue("the audio track did not survive") { success.info.audio.shouldNotBeNull() }
      audio.sampleRate shouldBeGreaterThan 0
      withClue("AudioSpec.AudioOnly must not leave a video track behind") { success.info.video.shouldBeNull() }
      withClue("duration was ${success.info.duration}") {
        (success.info.duration in 800.milliseconds..1_300.milliseconds).shouldBeTrue()
      }

      remove(output)
    }

  /**
   * The mix, read back off a real exported file rather than off the graph that was handed to the
   * writer.
   *
   * The bed carries the one tone no other fixture does, so a level measured at 880 Hz belongs to it
   * and to nothing else. It is trimmed shorter than the composition and held off past zero, which is
   * what makes the offset and the loop separable: 880 Hz has to be absent before the bed opens, and
   * present again inside a pass the loop had to lay down to reach.
   *
   * Levels are compared as a ratio against the primary's own tone in the same window, so the
   * amplitude the fixtures were generated at and whatever AAC did to the output both divide out. An
   * absolute threshold would be pinning the fixture generator rather than the mix.
   */
  @Test
  fun `mixes a looping bed under the primary at the gain the plan folded`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")
      val bed = fixture("tone_bed_880.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape))
          track(TrackContent.Audio) {
            clip(MediaSource.of(bed)) { trim(Duration.ZERO, BED_PASS) }
            audio(AudioLevel.Volume(BED_VOLUME))
            startAt(BED_START)
            looping()
          }
        }

      val spec = ExportSpec(heightPx = 240)
      val resolved = resolved(composition, spec)
      val bedTrack = resolved.tracks.single { it.content == TrackContent.Audio }
      val primaryTrack = resolved.tracks.single { it.content != TrackContent.Audio }
      val primaryClip = primaryTrack.clips.single()

      // The plan holds one clip per pass, so which pass the window's middle lands in is read off
      // the laid list rather than worked out from the bed's own length.
      val middle = LOOPED + WINDOW / 2
      val pass = bedTrack.clips.indexOfLast { it.span.start <= middle }
      withClue("the window at $middle sits in pass ${pass + 1}, which the loop never had to lay down") {
        (pass >= 1).shouldBeTrue()
      }
      val bedClip = bedTrack.clips[pass]

      val output = temporaryPath("audio-bed")
      exported(composition, spec, output)
      val audio = audioOf(output)

      val primaryTone = audio.toneOver(LOOPED, WINDOW, PRIMARY_HZ)
      val bedTone = audio.toneOver(LOOPED, WINDOW, BED_HZ)
      val beforePrimary = audio.toneOver(BEFORE_BED, WINDOW, PRIMARY_HZ)
      val beforeBed = audio.toneOver(BEFORE_BED, WINDOW, BED_HZ)
      println("440Hz: $beforePrimary before the bed, $primaryTone inside pass ${pass + 1}")
      println("880Hz: $beforeBed before the bed, $bedTone inside pass ${pass + 1}")

      withClue("the primary's own tone never reached the file, reading $primaryTone") {
        (primaryTone > AUDIBLE).shouldBeTrue()
      }
      withClue("the window before the bed carries no primary, reading $beforePrimary") {
        (beforePrimary > AUDIBLE).shouldBeTrue()
      }
      withClue("the bed was audible at $beforeBed before its $BED_START start, against $bedTone once it had opened") {
        (beforeBed < bedTone * SILENT).shouldBeTrue()
      }

      // The gain the planner folded, read off the plan rather than typed here, and sampled in the
      // pass's own time at the point inside it the window's middle lands on.
      val expected = bedClip.gain.gainAt(middle - bedClip.span.start) / primaryClip.gain.gainAt(middle)
      val measured = bedTone / primaryTone
      withClue("expected the bed at $expected of the primary, measured $measured ($bedTone against $primaryTone)") {
        (abs(measured - expected) <= GAIN_TOLERANCE).shouldBeTrue()
      }

      remove(output)
    }

  /**
   * Every pass the plan laid, read back out of the written file rather than only the one instant
   * the test above reaches.
   *
   * The bed's trim, the track's offset and the composition's length divide into each other nowhere,
   * so the last pass is always cut and no reading sits near a pass boundary. The track's fade covers
   * the whole run instead of restarting each pass, and the readings a quarter and three quarters
   * along it are what separate the two: a fade replayed per pass opens pass two at silence where
   * this one opens well up the ramp.
   */
  @Test
  fun `measures every pass of a looping bed against the gain the plan laid`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture("apple_export_long.mp4")
      val bed = fixture("tone_bed_880.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(long)) { trim(TimeRange.of(Duration.ZERO, LoopingCases.PRIMARY_RUN)) }
          track(TrackContent.Audio) {
            clip(MediaSource.of(bed)) { trim(LoopingCases.BED_TRIM) }
            startAt(LoopingCases.BED_START)
            fadeIn(LoopingCases.TRACK_FADE)
            looping()
          }
        }
      val spec = ExportSpec(heightPx = 240)

      val resolved = resolved(composition, spec)
      val bedTrack = resolved.tracks.single { it.content == TrackContent.Audio }
      val laid = bedTrack.clips
      val primary =
        resolved.tracks
          .single { it.content != TrackContent.Audio }
          .clips
          .single()

      // Asserted before a sample is measured, so a schedule that laid the wrong passes fails by
      // name rather than as a level that missed.
      bedTrack.assertLaysPasses(LoopingCases.BED_LENGTHS, resolved.duration)
      assertFadeCarriesAcross(laid)

      val output = temporaryPath("loop-passes")
      assertRunsFor(loopExported(composition, spec, output), resolved.duration)

      val audio = audioOf(output)
      val fixtures = fixtureRatio(bed, long)
      LoopingCases.BED_READINGS.forEach { (pass, into) ->
        assertBedLevel(audio, fixtures, laid[pass], primary, into, "pass ${pass + 1}")
      }

      // Before the track opens the bed contributes nothing, held to the same tolerance every
      // reading against a folded gain is.
      val quiet = LoopingCases.BEFORE_BED
      val before = audio.bedAgainstPrimary(quiet)
      withClue("the bed read $before at $quiet, before its ${laid.first().span.start} start") {
        (before <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
      }

      remove(output)
    }

  /**
   * Two clips on one looping track, told apart by a clip volume rather than by frequency, since
   * there is only one bed tone to go round.
   *
   * Every pass lays A then B, so a reading inside B says the writer kept the two in order and cut
   * the run where the plan did rather than laying each clip on a loop of its own.
   */
  @Test
  fun `keeps two looping clips apart at the levels the plan laid`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture("apple_export_long.mp4")
      val bed = fixture("tone_bed_880.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(long)) { trim(TimeRange.of(Duration.ZERO, LoopingCases.PRIMARY_RUN)) }
          track(TrackContent.Audio) {
            clip(MediaSource.of(bed)) { trim(LoopingCases.LOUD_TRIM) }
            clip(MediaSource.of(bed)) {
              trim(LoopingCases.QUIET_TRIM)
              audio(AudioLevel.Volume(LoopingCases.QUIET_VOLUME))
            }
            startAt(LoopingCases.PAIR_START)
            looping()
          }
        }
      val spec = ExportSpec(heightPx = 240)

      val resolved = resolved(composition, spec)
      val bedTrack = resolved.tracks.single { it.content == TrackContent.Audio }
      val laid = bedTrack.clips
      val primary =
        resolved.tracks
          .single { it.content != TrackContent.Audio }
          .clips
          .single()
      bedTrack.assertLaysPasses(LoopingCases.PAIR_LENGTHS, resolved.duration)

      val output = temporaryPath("loop-two-clips")
      assertRunsFor(loopExported(composition, spec, output), resolved.duration)

      val audio = audioOf(output)
      val fixtures = fixtureRatio(bed, long)

      // A writer that stopped after the first pass leaves silence from here on, which the levels
      // below would report as a gain that missed rather than as a bed that never arrived.
      val (firstPass, firstInto) = LoopingCases.PAIR_READINGS.first()
      val present = audio.bedAgainstPrimary(laid[firstPass].span.start + firstInto)
      withClue("the bed read $present a pass past its own end, where it should still be playing") {
        (present > ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
      }

      LoopingCases.PAIR_READINGS.forEach { (index, into) ->
        val clip = if (laid[index].sourceIndex == 0) "clip A" else "clip B"
        assertBedLevel(audio, fixtures, laid[index], primary, into, "pass ${index / 2 + 1}, $clip")
      }

      remove(output)
    }

  /**
   * A looping video primary under a longer audio-only track, the case where the loop has to carry
   * pictures as well as samples.
   *
   * Before the passes were laid the writer ran the primary once and left the fill from there on, so
   * the frame inside the third pass is as much of the assertion as the tone is.
   */
  @Test
  fun `loops a video primary under a longer audio track`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture("apple_export_long.mp4")
      val bed = fixture("tone_bed_880.mp4")

      val composition =
        compositionOf {
          // Written as a track rather than as a primary clip, since only a track carries looping
          // and the builder promotes a leading track to the primary one.
          track {
            clip(MediaSource.of(bed)) { trim(LoopingCases.VIDEO_TRIM) }
            looping()
          }
          track(TrackContent.Audio) {
            clip(MediaSource.of(long)) { trim(TimeRange.of(Duration.ZERO, LoopingCases.UNDERLAY_RUN)) }
          }
        }
      val spec = ExportSpec(heightPx = 240)

      val resolved = resolved(composition, spec)
      val videoTrack = resolved.tracks.single { it.content != TrackContent.Audio }
      val laid = videoTrack.clips
      val tone =
        resolved.tracks
          .single { it.content == TrackContent.Audio }
          .clips
          .single()
      videoTrack.assertLaysPasses(LoopingCases.VIDEO_LENGTHS, resolved.duration)

      val output = temporaryPath("loop-video")
      assertRunsFor(loopExported(composition, spec, output), resolved.duration)

      val audio = audioOf(output)
      val fixtures = fixtureRatio(bed, long)
      LoopingCases.VIDEO_READINGS.forEach { (pass, into) ->
        assertBedLevel(audio, fixtures, laid[pass], tone, into, "pass ${pass + 1}")
      }

      // The audio-only track holds one level for the whole composition, so its own tone cannot move
      // across the readings. A track laid only as far as the primary's first pass reads nothing at
      // the later ones, which every ratio above would report as the loop being wrong instead.
      val reference = audio.toneAt(LOOP_VIDEO_REFERENCE, PRIMARY_HZ)
      LoopingCases.VIDEO_READINGS.forEach { (pass, into) ->
        val at = laid[pass].span.start + into
        val held = audio.toneAt(at, PRIMARY_HZ) / reference
        withClue("at $at the audio track read $held of the level it holds at $LOOP_VIDEO_REFERENCE") {
          (abs(held - 1f) <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
        }
      }

      // The picture inside a later pass, which is the bed's own pattern rather than the flat color
      // a composition with nothing laid on it falls back to.
      val (picturePass, pictureInto) = LoopingCases.VIDEO_READINGS.first()
      val pictureAt = laid[picturePass].span.start + pictureInto
      val fixtureSpread = spreadOf(frameOf(bed, LoopingCases.VIDEO_TRIM.start + pictureInto))
      val spread = spreadOf(frameOf(output, pictureAt))
      withClue(
        "the frame at $pictureAt spans $spread against the fixture's own $fixtureSpread, so it carries the fill",
      ) {
        (spread >= fixtureSpread * PICTURE_SPREAD_FLOOR).shouldBeTrue()
      }

      remove(output)
    }

  /**
   * A clip trimmed off the frame grid, handed to the next one on it.
   *
   * 1.010 s is 30.3 slots at 30 fps and rounds down, and 1.020 s is 30.6 and rounds up, so the seam moves to either
   * side of where the plan put it. The second fixture is the first one's pattern turned a third of the way round the
   * color wheel, so which clip a frame came from reads off which of the two it sits closer to.
   */
  @Test
  fun `hands a clip trimmed off the frame grid to the next one on it`() =
    runTest(timeout = TIMEOUT) {
      val first = fixture("apple_export_a.mp4")
      val second = fixture("apple_export_b.mp4")

      listOf(1_010.milliseconds, 1_020.milliseconds).forEach { length ->
        val composition =
          compositionOf {
            clip(MediaSource.of(first)) { trim(Duration.ZERO, length) }
            clip(MediaSource.of(second)) { trim(Duration.ZERO, 1.seconds) }
          }

        val output = temporaryPath("off-grid-seam")
        exported(composition, ExportSpec(frameRate = FIXTURE_RATE, heightPx = 240), output)

        assertOnTheGrid(sampleTimesOf(output), length + 1.seconds, FIXTURE_RATE, "a first clip of $length")
        val seam = outputSlotOf(length, FIXTURE_RATE)
        val closing = outputSlotTime(seam - 1, FIXTURE_RATE)
        val opening = outputSlotTime(seam, FIXTURE_RATE)
        val fromFirst = frameOf(first, closing)
        val fromSecond = frameOf(second)
        assertShows(frameOf(output, closing), fromFirst, fromSecond, "the first clip's last slot at $closing")
        assertShows(frameOf(output, opening), fromSecond, fromFirst, "the second clip's first slot at $opening")

        remove(output)
      }
    }

  /**
   * A primary that starts part way into a slot writes every frame once, on the grid.
   *
   * 17 ms is 0.51 of a slot at 30 fps and 16 ms is 0.48, so one start rounds up and the other down. The clip's first
   * frame lands on the slot its start rounds to, and the fill holds every slot before that one.
   */
  @Test
  fun `opens a primary that starts inside a slot on the slot its start rounds to`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")
      val picture = spreadOf(frameOf(landscape))

      listOf(17.milliseconds, 16.milliseconds).forEach { start ->
        val composition =
          compositionOf {
            track {
              clip(MediaSource.of(landscape)) { trim(Duration.ZERO, 2.seconds) }
              startAt(start)
            }
          }

        val output = temporaryPath("off-grid-start")
        exported(composition, ExportSpec(frameRate = FIXTURE_RATE, heightPx = 240), output)

        assertOnTheGrid(sampleTimesOf(output), start + 2.seconds, FIXTURE_RATE, "a primary starting at $start")
        val opening = outputSlotOf(start, FIXTURE_RATE)
        (0L..opening).forEach { slot ->
          val at = outputSlotTime(slot, FIXTURE_RATE)
          val spread = spreadOf(frameOf(output, at))
          val carriesPicture = spread >= picture * PICTURE_SPREAD_FLOOR
          withClue(
            "a primary starting at $start opens on slot $opening, and the frame at $at spans $spread against $picture",
          ) {
            carriesPicture shouldBe (slot == opening)
          }
        }

        remove(output)
      }
    }

  /**
   * A looping clip whose track runs part of a slot past its last frame, laid again on every pass.
   *
   * The clip is 26 frames at 25 fps held until 1.030 s, which is 25.75 slots, so a pass that rounds up runs on past
   * the clip's own end. That slot holds the clip's last frame, read inside the part past the end as well, and the next
   * pass still opens on its own slot.
   */
  @Test
  fun `holds a looping clip's last frame when a pass runs past its end`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture("apple_export_long.mp4")
      val offGrid = NSTemporaryDirectory() + "filmstrip-apple-off-grid-clip.mov"
      withContext(Dispatchers.Default) { writeOffGridClip(offGrid, OFF_GRID_RATE, OFF_GRID_FRAMES, OFF_GRID_END) }

      val composition =
        compositionOf {
          track {
            clip(MediaSource.of(offGrid))
            looping()
          }
          track(TrackContent.Audio) {
            clip(MediaSource.of(long)) { trim(TimeRange.of(Duration.ZERO, OFF_GRID_RUN)) }
          }
        }
      val spec = ExportSpec(frameRate = OFF_GRID_RATE)
      val resolved = resolved(composition, spec)
      val laid = resolved.tracks.single { it.content != TrackContent.Audio }.clips

      val output = temporaryPath("off-grid-loop")
      assertRunsFor(loopExported(composition, spec, output), resolved.duration)

      assertOnTheGrid(sampleTimesOf(output), resolved.duration, OFF_GRID_RATE, "a looping clip")
      val first = outputSlotsOf(laid[0].span, OFF_GRID_RATE)
      val held = outputSlotTime(first.last, OFF_GRID_RATE)
      val clipEnds = outputSlotTime(first.first, OFF_GRID_RATE) + OFF_GRID_END
      val pastTheEnd = clipEnds + (outputSlotTime(first.last + 1, OFF_GRID_RATE) - clipEnds) / 2
      val reopened = outputSlotTime(outputSlotsOf(laid[1].span, OFF_GRID_RATE).first, OFF_GRID_RATE)
      frameIndexOf(frameOf(output, held)) shouldBe OFF_GRID_FRAMES - 1
      frameIndexOf(frameOf(output, pastTheEnd)) shouldBe OFF_GRID_FRAMES - 1
      frameIndexOf(frameOf(output, reopened)) shouldBe 0

      remove(output)
      remove(offGrid)
    }

  /**
   * A clip whose picture ends a whole slot before its sound does, laid twice from just short of half a slot in.
   *
   * The picture runs 1.000 s inside a 1.005 s movie. From 19 ms at 25 fps the first pass covers 26 slots, so its last
   * slot lies wholly past the picture. The clip's last frame holds from its own slot to the end of the pass, nothing is
   * written off the grid, and the second pass, which fits inside the picture, opens on its own slot. A thumbnail
   * rendered from the same lowering shows the held frame as well.
   */
  @Test
  fun `holds a clip's last frame over a whole slot when its sound outlasts its picture`() =
    runTest(timeout = TIMEOUT) {
      val offGrid = NSTemporaryDirectory() + "filmstrip-apple-short-picture.mov"
      withContext(Dispatchers.Default) {
        writeOffGridClip(offGrid, OFF_GRID_RATE, SHORT_FRAMES, SHORT_PICTURE, audioUntil = SHORT_SOUND)
      }

      val composition =
        compositionOf {
          track {
            clip(MediaSource.of(offGrid))
            clip(MediaSource.of(offGrid))
            startAt(SHORT_START)
          }
        }
      val spec = ExportSpec(frameRate = OFF_GRID_RATE)
      val resolved = resolved(composition, spec)
      val laid = resolved.tracks.single().clips

      val output = temporaryPath("short-picture")
      exported(composition, spec, output)

      val first = outputSlotsOf(laid[0].span, OFF_GRID_RATE)
      val opens = outputSlotTime(first.first, OFF_GRID_RATE)
      val lastFrame = opens + SHORT_PICTURE * (SHORT_FRAMES - 1) / SHORT_FRAMES
      val pictureEnds = opens + SHORT_PICTURE
      val passEnds = outputSlotTime(first.last + 1, OFF_GRID_RATE)
      val pastThePicture = pictureEnds + (passEnds - pictureEnds) / 2
      val lateInTheTail = passEnds - (passEnds - pictureEnds) / 8

      val times = sampleTimesOf(output)
      times.forEach { time ->
        val slot = outputSlotTime(outputSlotOf(time, OFF_GRID_RATE), OFF_GRID_RATE)
        withClue("a frame was written at $time, off the grid: $times") {
          ((time - slot).absoluteValue <= SAMPLE_TOLERANCE).shouldBeTrue()
        }
      }
      val written = times.map { outputSlotOf(it, OFF_GRID_RATE) }
      written shouldBe written.distinct().sorted()
      val shown =
        (0 until outputSlotOf(resolved.duration, OFF_GRID_RATE)).filterNot { slot ->
          slot in first && outputSlotTime(slot, OFF_GRID_RATE) >= pictureEnds
        }
      withClue("every slot inside a picture carries its own frame: $times") {
        written.containsAll(shown).shouldBeTrue()
      }

      listOf(lastFrame, pictureEnds, pastThePicture, lateInTheTail).forEach { at ->
        withClue("the frame at $at") { frameIndexOf(frameOf(output, at)) shouldBe SHORT_FRAMES - 1 }
      }
      val second = outputSlotTime(outputSlotsOf(laid[1].span, OFF_GRID_RATE).first, OFF_GRID_RATE)
      frameIndexOf(frameOf(output, second)) shouldBe 0

      val lowered = resolved.toAvComposition()
      listOf(pastThePicture, lateInTheTail).forEach { at ->
        val thumbnail = frameOf(lowered.composition, lowered.videoComposition, at)
        withClue("the thumbnail at $at") { frameIndexOf(thumbnail) shouldBe SHORT_FRAMES - 1 }
      }

      remove(output)
      remove(offGrid)
    }

  /**
   * A primary whose picture ends seconds before the audio track under it does.
   *
   * The clip is 26 frames at 25 fps held until 1.030 s, so its last slot shows its last frame, and the bed runs the
   * composition to five seconds, then to 0.3 of a slot past that, where the audio outlasts the last slot. The video
   * track writes one frame a slot up to the last slot and no further, and every frame in the tail is the one the
   * primary's last slot wrote. A badge fading in over longer than the clip runs is caught part way, and it stays that
   * way over the tail rather than carrying on or dropping out with the clip. A thumbnail of the same lowering shows the
   * held frame too.
   */
  @Test
  fun `holds the primary's last frame to the end under a longer audio track`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture("apple_export_long.mp4")
      val offGrid = NSTemporaryDirectory() + "filmstrip-apple-held-tail.mov"
      withContext(Dispatchers.Default) { writeOffGridClip(offGrid, OFF_GRID_RATE, OFF_GRID_FRAMES, OFF_GRID_END) }
      val badge =
        ImageOverlay(ImageSource.of(photoFile()), Corner.TopStart, scale = BADGE_SCALE, animation = fadeIn(BADGE_FADE))

      listOf(HELD_RUN, HELD_RUN_PAST_SLOT).forEach { run ->
        val composition =
          compositionOf {
            clip(MediaSource.of(offGrid)) { addEffects(listOf(badge)) }
            track(TrackContent.Audio) {
              clip(MediaSource.of(long)) { trim(TimeRange.of(Duration.ZERO, run)) }
            }
          }
        val spec = ExportSpec(frameRate = OFF_GRID_RATE)
        val resolved = resolved(composition, spec)
        val tail = resolved.heldTail
        withClue("under a $run bed the tail holds only $tail, not seconds of it") {
          (tail.count() > OFF_GRID_RATE).shouldBeTrue()
        }

        val output = temporaryPath("held-tail")
        exported(composition, spec, output)

        val times = sampleTimesOf(output)
        withClue("under a $run bed") { times.size.toLong() shouldBe resolved.videoSlots }
        assertOnTheGrid(times, resolved.duration, OFF_GRID_RATE, "a primary held under a $run bed")
        assertTrackEndsAt(output, AVMediaTypeVideo, outputSlotTime(resolved.videoSlots, OFF_GRID_RATE))
        assertTrackEndsAt(output, AVMediaTypeAudio, resolved.duration)

        val lastCovered = outputSlotTime(tail.first - 1, OFF_GRID_RATE)
        val held = listOf(tail.first, (tail.first + tail.last) / 2, tail.last).map { outputSlotTime(it, OFF_GRID_RATE) }
        val covered = frameOf(output, lastCovered)
        frameIndexOf(covered) shouldBe OFF_GRID_FRAMES - 1
        val shown = covered.average(BADGE_X, BADGE_Y)
        withClue("the badge at $lastCovered reads $shown, the same as the frame under it") {
          (distance(shown, covered.average(HALF, HALF)) > BADGE_VISIBLE).shouldBeTrue()
        }
        held.forEach { at ->
          val frame = frameOf(output, at)
          withClue("the frame at $at under a $run bed") { frameIndexOf(frame) shouldBe OFF_GRID_FRAMES - 1 }
          withClue(
            "the badge at $at reads ${frame.average(BADGE_X, BADGE_Y)} against $shown on the last covered slot",
          ) {
            (distance(frame.average(BADGE_X, BADGE_Y), shown) <= BADGE_HELD).shouldBeTrue()
          }
        }

        val lowered = resolved.toAvComposition()
        val thumbnail = frameOf(lowered.composition, lowered.videoComposition, held[1])
        withClue("the thumbnail at ${held[1]}") { frameIndexOf(thumbnail) shouldBe OFF_GRID_FRAMES - 1 }

        remove(output)
      }
      remove(offGrid)
    }

  /**
   * Asserts the [mediaType] track of the file at [path] ends at [end], to within one tick of the backend's timescale.
   */
  private fun assertTrackEndsAt(
    path: String,
    mediaType: String?,
    end: Duration,
  ) {
    val track =
      AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        .tracksWithMediaType(mediaType)
        .single() as AVAssetTrack
    val ends = CMTimeRangeGetEnd(track.timeRange).toDuration()
    withClue("the $mediaType track ends at $ends, not at $end") {
      ((ends - end).absoluteValue <= MEDIA_TICK).shouldBeTrue()
    }
  }

  /**
   * A copy writes the source's own samples and shows them for exactly as long as the trim runs, so a trim ending off
   * the frame grid shows every sample the source shows before that end.
   *
   * 1.010 s is 30.3 frames of the fixture, so a copy cut on the grid would stop at 1.000 s and drop the sample shown
   * from there. The samples a shown one is decoded from are written as well, past the end the copy stops showing at,
   * so only the shown ones are compared.
   */
  @Test
  fun `copies a clip trimmed off the frame grid sample for sample`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition = compositionOf { clip(MediaSource.of(landscape)) { trim(Duration.ZERO, COPY_TRIM_END) } }
      filmstrip
        .plan(composition, ExportSpec())
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.path shouldBe ExportPath.Copy

      val output = temporaryPath("off-grid-copy")
      exported(composition, ExportSpec(), output)

      val shown = videoEndOf(output)
      withClue("the copy shows its picture until $shown, not the $COPY_TRIM_END trim") {
        ((shown - COPY_TRIM_END).absoluteValue <= SAMPLE_TOLERANCE).shouldBeTrue()
      }
      sampleTimesOf(output).filter { it < shown } shouldBe sampleTimesOf(landscape).filter { it < COPY_TRIM_END }

      remove(output)
    }

  /**
   * A copy of a clip whose sound outlasts its picture writes the picture's own samples, each as long as the source
   * shows it.
   *
   * The picture runs 1.000 s at 25 fps inside 1.030 s of sound, which is 25.75 slots, so a clip laid on the grid would
   * run a slot past the picture and stretch its last sample over it.
   */
  @Test
  fun `copies a clip whose sound outlasts its picture without stretching a sample`() =
    runTest(timeout = TIMEOUT) {
      val offGrid = NSTemporaryDirectory() + "filmstrip-apple-copy-short-picture.mov"
      withContext(Dispatchers.Default) {
        writeOffGridClip(offGrid, OFF_GRID_RATE, SHORT_FRAMES, SHORT_PICTURE, COPY_SOUND, encodesAudio = true)
      }

      val composition = compositionOf { clip(MediaSource.of(offGrid)) }
      filmstrip
        .plan(composition, ExportSpec())
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.path shouldBe ExportPath.Copy

      val output = temporaryPath("short-picture-copy")
      exported(composition, ExportSpec(), output)

      videoSamplesOf(output) shouldBe videoSamplesOf(offGrid)

      remove(output)
      remove(offGrid)
    }

  /**
   * A copy of a 48 fps clip written at a timescale that names each frame exactly, which 600 a second does not, keeps
   * every sample at the time and for the length its source holds it.
   */
  @Test
  fun `copies a clip at the times its source holds each sample`() =
    runTest(timeout = TIMEOUT) {
      val source = NSTemporaryDirectory() + "filmstrip-apple-copy-whole-tick.mov"
      withContext(Dispatchers.Default) {
        writeOffGridClip(source, WHOLE_TICK_RATE, INDEXED_FRAMES, 1.seconds * INDEXED_FRAMES / WHOLE_TICK_RATE)
      }

      val composition = compositionOf { clip(MediaSource.of(source)) }
      filmstrip
        .plan(composition, ExportSpec())
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.path shouldBe ExportPath.Copy

      val output = temporaryPath("whole-tick-copy")
      exported(composition, ExportSpec(), output)

      videoSamplesOf(output) shouldBe videoSamplesOf(source)

      remove(output)
      remove(source)
    }

  /**
   * A clip at 48 fps and a still after it, at a rate the backend's timescale divides, exported at that rate.
   *
   * Every frame, the still's included, is written on its slot's own time, one a slot, and a thumbnail rendered from
   * the same lowering at each of the clip's slots shows that slot's frame.
   */
  @Test
  fun `writes every frame on its own time at a rate the timescale divides`() =
    runTest(timeout = TIMEOUT) {
      assertWritesEveryFrameOnItsSlot(WHOLE_TICK_RATE, SAMPLE_TOLERANCE, "whole-tick-rate")
    }

  /**
   * A clip at 32 fps and a still after it, at a rate the backend's timescale does not divide, exported at that rate.
   *
   * Every frame, the still's included, is written on its own slot to within a tick, one a slot, and a thumbnail
   * rendered from the same lowering at each of the clip's slots shows that slot's frame rather than one on some other
   * cadence.
   */
  @Test
  fun `writes every frame on its slot at a rate the timescale does not divide`() =
    runTest(timeout = TIMEOUT) {
      assertWritesEveryFrameOnItsSlot(UNEVEN_RATE, MEDIA_TICK, "uneven-rate")
    }

  @Test
  fun `streams progress that only ever climbs`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture("apple_export_long.mp4")

      val composition = compositionOf { clip(MediaSource.of(long)) }
      val output = temporaryPath("progress")
      // 360 divides the fixture's frame exactly, so the plan needs no adjustment and Started is
      // genuinely the first thing a caller sees.
      val statuses =
        withContext(Dispatchers.Default) {
          filmstrip.export(composition, ExportSpec(heightPx = 360), MediaSink.of(output)).toList()
        }

      statuses.first().shouldBeInstanceOf<ExportStatus.Started>()
      val progress = statuses.filterIsInstance<ExportStatus.Progress>()
      withClue("expected at least one progress report") { progress.isNotEmpty().shouldBeTrue() }
      progress.zipWithNext().forEach { (first, second) ->
        withClue("progress went backwards") { (second.fraction >= first.fraction).shouldBeTrue() }
      }
      statuses.last().shouldBeInstanceOf<ExportStatus.Success>()

      remove(output)
    }

  // Cancelling the collector cancels the export, and everything at the destination belongs to the
  // run, so nothing is left behind.
  @Test
  fun `cancelling the collector leaves no partial file`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture("apple_export_long.mp4")
      val output = temporaryPath("cancelled")
      val composition = compositionOf { clip(MediaSource.of(long)) }

      // Cancelled on Started, not after a fixed window. A hardware encode of even a long
      // clip outruns any window worth waiting for, and a run that finished first would be checking
      // that a successful export keeps its file, which is the opposite claim.
      withContext(Dispatchers.Default) {
        val started = CompletableDeferred<Unit>()
        val job =
          launch {
            filmstrip.export(composition, ExportSpec(heightPx = 360), MediaSink.of(output)).collect {
              if (it is ExportStatus.Started) started.complete(Unit)
            }
          }
        started.await()
        job.cancelAndJoin()
      }

      NSFileManager.defaultManager.fileExistsAtPath(output) shouldBe false
    }

  @Test
  fun `resolves a temporary sink to the file it wrote`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
        }

      val success =
        withContext(Dispatchers.Default) {
          val statuses = filmstrip.export(composition, ExportSpec(heightPx = 180), MediaSink.Temporary).toList()
          when (val finished = statuses.last()) {
            is ExportStatus.Failure -> error(finished.error.message)
            else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
          }
        }

      val path = success.output.shouldBeInstanceOf<MediaSink.Path>().path
      NSFileManager.defaultManager.fileExistsAtPath(path) shouldBe true
      remove(path)
    }

  @Test
  fun `reports an unwritable destination and does not start`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition = compositionOf { clip(MediaSource.of(landscape)) }
      val statuses =
        withContext(Dispatchers.Default) {
          filmstrip
            .export(composition, ExportSpec(heightPx = 240), MediaSink.of("/nonexistent/dir/out.mp4"))
            .toList()
        }

      val failure = statuses.last().shouldBeInstanceOf<ExportStatus.Failure>()
      failure.error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
      // Nothing was opened, so the export never began.
      withClue("the export started on an unwritable sink") {
        statuses.none { it is ExportStatus.Started }.shouldBeTrue()
      }
    }

  @Test
  fun `leaves nothing behind when the export fails`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")
      val output = temporaryPath("leftover")

      val composition = compositionOf { clip(MediaSource.of(landscape)) }
      withContext(Dispatchers.Default) {
        filmstrip.export(composition, ExportSpec(heightPx = 240), MediaSink.of(output)).toList()
      }
      remove(output)

      NSFileManager.defaultManager.fileExistsAtPath(output) shouldBe false
    }

  private suspend fun exported(
    composition: EditComposition,
    spec: ExportSpec,
    output: String,
  ): ExportStatus.Success =
    withContext(Dispatchers.Default) {
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
        is Verdict.Capable, is Verdict.Degraded -> Unit
      }

      val statuses = filmstrip.export(composition, spec, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }

  /**
   * Exports [composition] to [output] and hands back what the written file says about itself.
   *
   * Scaling the long fixture to the spec's target height can land between two widths VideoToolbox
   * accepts, which the planner reports as an adjustment and which none of the looping tests
   * measures. That one is let through and every other kind is not.
   */
  private suspend fun loopExported(
    composition: EditComposition,
    spec: ExportSpec,
    output: String,
  ): MediaInfo =
    withContext(Dispatchers.Default) {
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Incapable -> {
          error(verdict.reasons.joinToString { it.message })
        }
        is Verdict.Capable -> {
        }
        is Verdict.Degraded -> {
          withClue("the plan degraded for more than the frame: ${verdict.adjustments.map { it.message }}") {
            verdict.adjustments.all { it.kind == AdjustmentKind.ResolutionClamped }.shouldBeTrue()
          }
        }
      }

      val statuses = filmstrip.export(composition, spec, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>().info
      }
    }

  /**
   * Asserts [written] runs for as long as [planned], to within one frame of the output.
   *
   * A looping track that laid its last pass whole rather than cutting it overruns by the rest of
   * that pass, most of a second here, so one frame separates the two without pinning how much
   * padding the writer put at the end.
   */
  private fun assertRunsFor(
    written: MediaInfo,
    planned: Duration,
  ) {
    val frame = 1.seconds / (written.video?.frameRate?.toDouble() ?: OUTPUT_FRAME_RATE)
    val overrun = written.duration - planned
    withClue("the file runs for ${written.duration} against the planned $planned, out by $overrun on a $frame frame") {
      (abs(overrun.inWholeMicroseconds) <= frame.inWholeMicroseconds).shouldBeTrue()
    }
  }

  /**
   * Exports a clip written at [frameRate] followed by a still, at that same rate, and asserts every frame lands within
   * [tolerance] of its own slot, one a slot, and that a thumbnail of the same lowering at each of the clip's slots
   * shows that slot's frame. The still's frames are cut from the seed for [frameRate], so the seed is held to the grid
   * too.
   */
  private suspend fun assertWritesEveryFrameOnItsSlot(
    frameRate: Int,
    tolerance: Duration,
    name: String,
  ) {
    val written = NSTemporaryDirectory() + "filmstrip-apple-$name.mov"
    withContext(Dispatchers.Default) {
      writeOffGridClip(written, frameRate, INDEXED_FRAMES, 1.seconds * INDEXED_FRAMES / frameRate)
    }

    val composition =
      compositionOf {
        clip(MediaSource.of(written))
        image(ImageSource.of(photoFile()), STILL_RUN)
      }
    val spec = ExportSpec(frameRate = frameRate)
    val resolved = resolved(composition, spec)

    val output = temporaryPath(name)
    exported(composition, spec, output)

    val times = sampleTimesOf(output)
    val slots = outputSlotOf(resolved.duration, frameRate)
    withClue("the export wrote ${times.size} frames where the grid holds $slots: $times") {
      times.size.toLong() shouldBe slots
    }
    times.forEachIndexed { index, time ->
      val slot = outputSlotTime(index.toLong(), frameRate)
      withClue("frame $index was written at $time, off its slot at $slot") {
        ((time - slot).absoluteValue <= tolerance).shouldBeTrue()
      }
    }

    val lowered = resolved.toAvComposition()
    (0 until INDEXED_FRAMES).forEach { index ->
      val at = outputSlotTime(index.toLong(), frameRate)
      val thumbnail = frameOf(lowered.composition, lowered.videoComposition, at)
      withClue("the thumbnail at $at") { frameIndexOf(thumbnail) shouldBe index }
    }

    remove(output)
    remove(written)
  }

  /**
   * Asserts [times] holds one sample for every slot of the output frame grid at [frameRate] up to [end], each on its
   * own slot.
   */
  private fun assertOnTheGrid(
    times: List<Duration>,
    end: Duration,
    frameRate: Int,
    what: String,
  ) {
    val slots = outputSlotOf(end, frameRate)
    withClue("$what wrote ${times.size} frames where the grid holds $slots: $times") {
      times.size.toLong() shouldBe slots
    }
    times.forEachIndexed { index, time ->
      val slot = outputSlotTime(index.toLong(), frameRate)
      withClue("$what wrote frame $index at $time, off its slot at $slot: $times") {
        ((time - slot).absoluteValue <= SAMPLE_TOLERANCE).shouldBeTrue()
      }
    }
  }

  /**
   * Asserts [frame] reads as [expected] rather than as [other], measured over a grid of patches across all three.
   */
  private fun assertShows(
    frame: FrameProbe,
    expected: FrameProbe,
    other: FrameProbe,
    what: String,
  ) {
    val near = differenceOf(frame, expected)
    val far = differenceOf(frame, other)
    withClue("$what sits $near from the clip it should show and $far from the other one") {
      (near < far).shouldBeTrue()
    }
  }

  /**
   * How far apart two frames read, summed over a grid of patches at the same place in each.
   */
  private fun differenceOf(
    first: FrameProbe,
    second: FrameProbe,
  ): Int =
    (0 until PICTURE_GRID).sumOf { row ->
      (0 until PICTURE_GRID).sumOf { column ->
        val x = (column + HALF) / PICTURE_GRID
        val y = (row + HALF) / PICTURE_GRID
        distance(first.average(x, y), second.average(x, y))
      }
    }

  /**
   * Pins every laid pass of this track by which clip of the track it plays and by the span it holds,
   * against the run [passesCovering] derives for [lengths] over a composition of [duration].
   *
   * Whether the track loops decides how much of that composition it covers, so a track laid down
   * once is held to its own clips rather than to the whole run.
   */
  private fun ResolvedTrack.assertLaysPasses(
    lengths: List<Duration>,
    duration: Duration,
  ) {
    val expected =
      passesCovering(lengths, runLengthOf(looping, start, lengths, duration)).map { pass ->
        Triple(pass.index, start + pass.offset, start + pass.offset + pass.length)
      }
    clips.map { Triple(it.sourceIndex, it.span.start, it.span.endExclusive) } shouldBe expected
  }

  /**
   * Asserts each of [laid] opens on the gain the pass before it closed on.
   *
   * This is the whole difference between a track fade measured over the run and one replayed every
   * pass, and it is read off the plan rather than out of the file because a fade that restarts is
   * already wrong before anything is encoded.
   */
  private fun assertFadeCarriesAcross(laid: List<ResolvedClip>) {
    laid.zipWithNext { earlier, later ->
      val closed = earlier.gain.gainAt(earlier.duration)
      val opened = later.gain.gainAt(Duration.ZERO)
      withClue("a pass opened at $opened where the one before it closed at $closed, so the fade restarted") {
        (abs(opened - closed) <= ResolvedGain.PRODUCT_TOLERANCE).shouldBeTrue()
      }
    }
  }

  /**
   * Asserts the bed reads at [clip]'s laid gain [into] that clip, against the 440 Hz track's own
   * level at the same instant.
   *
   * Where on the composition clock that lands comes off the laid span rather than being written
   * out here, so the level measured and the gain sampled cannot drift apart. Either curve is
   * measured from zero at its own clip's start, so [against] is sampled at the instant less that
   * clip's own span start.
   */
  private fun assertBedLevel(
    audio: AudioProbe,
    fixtures: Float,
    clip: ResolvedClip,
    against: ResolvedClip,
    into: Duration,
    what: String,
  ) {
    withClue("$into runs past $what, which is ${clip.duration} long") { (into < clip.duration).shouldBeTrue() }
    val at = clip.span.start + into
    val expected = fixtures * (clip.gain.gainAt(into) / against.gain.gainAt(at - against.span.start))
    val measured = audio.bedAgainstPrimary(at)
    withClue("at $at, $into into $what, the bed read $measured against $expected from the laid gain") {
      (abs(measured - expected) <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
    }
  }

  /**
   * How loud the bed fixture's tone is against the long fixture's, so how loud either was generated
   * divides out of every reading taken from the mix.
   */
  private fun fixtureRatio(
    bed: String,
    primary: String,
  ): Float = audioOf(bed).toneAt(FIXTURE_READING, BED_HZ) / audioOf(primary).toneAt(FIXTURE_READING, PRIMARY_HZ)

  /**
   * The 880 Hz tone measured against the 440 Hz one at the same instant.
   *
   * Every level is read as a ratio of the two because an AAC round trip attenuates the whole mix,
   * and an absolute reading would be pinning the codec rather than the gain.
   */
  private fun AudioProbe.bedAgainstPrimary(at: Duration): Float = toneAt(at, BED_HZ) / toneAt(at, PRIMARY_HZ)

  /**
   * How much of [frequencyHz] the file carries around [at].
   *
   * The window is centred on [at] rather than opened there, because a reading taken across a ramp
   * is the mean gain over the window and a linear ramp's mean sits at its middle. Anchoring the
   * window's start instead would read a fade high by half the window's own rise.
   */
  private fun AudioProbe.toneAt(
    at: Duration,
    frequencyHz: Int,
  ): Float = toneOver(at - TONE_WINDOW / 2, TONE_WINDOW, frequencyHz)

  /**
   * How far apart the lightest and darkest of a grid of samples across [frame] sit.
   *
   * A fill is one flat color and reads near nothing here, where the fixture's own pattern spans
   * most of the range, so this says whether a frame carries a picture at all. A grid rather than
   * every pixel because a few dozen points already separate a pattern from a flat color.
   */
  private fun spreadOf(frame: FrameProbe): Int {
    val samples =
      (0 until PICTURE_GRID).flatMap { row ->
        (0 until PICTURE_GRID).map { column ->
          luminance(frame.at((column + HALF) / PICTURE_GRID, (row + HALF) / PICTURE_GRID))
        }
      }
    return samples.max() - samples.min()
  }

  /**
   * The graph an export of [composition] runs, which is where the folded gain a backend consumes
   * lives.
   *
   * Negotiated through the same engine the export goes through, so the curve read here is the curve
   * the writer was handed rather than a second fold of the same levels.
   */
  private suspend fun resolved(
    composition: EditComposition,
    spec: ExportSpec,
  ): ResolvedComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      val engine = avFoundationExportEngine(components)
      when (val result = engine.resolve(composition, spec)) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  /**
   * Where the file at [path] stops showing its picture, read off its video track rather than the file's duration.
   */
  private fun videoEndOf(path: String): Duration {
    val track =
      AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        .tracksWithMediaType(AVMediaTypeVideo)
        .single() as AVAssetTrack
    return CMTimeRangeGetEnd(track.timeRange).toDuration()
  }

  /**
   * The name the encoder stamped into this file's video format description.
   */
  private fun formatName(path: String): String? {
    val track =
      AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        .tracksWithMediaType(AVMediaTypeVideo)
        .firstOrNull() as? AVAssetTrack ?: return null
    val format = track.copyFormatDescription() ?: return null

    try {
      val name = CMFormatDescriptionGetExtension(format, kCMFormatDescriptionExtension_FormatName) ?: return null
      return CFBridgingRelease(CFRetain(name)) as? String
    } finally {
      CFRelease(format)
    }
  }

  /**
   * The flat photo the fill's contrast case draws from, written into the temporary directory once.
   */
  private fun photoFile(): String = photoFixture("filmstrip-apple-export-photo", PHOTO_SIZE, PHOTO_COLOR)

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    val TIMEOUT = 2.seconds * 60
    val BLACK = Triple(0, 0, 0)

    // The photo the contrast case lays over the fill, held long enough to read inside its span
    // rather than on either edge of it.
    val PHOTO = 1.seconds
    const val MIDDLE = 0.4
    val PHOTO_SIZE = Size(640, 360)

    // Mid-range on all three channels, so the gain moves every one of them without any running out
    // of range and clamping to the same answer a broken lowering would give.
    val PHOTO_COLOR = Triple(0x66, 0x99, 0xCC)

    const val FIRMER = 1.5f

    // The tones the fixtures were generated with. Only the bed carries 880Hz, which is what lets a
    // level measured in the mix be attributed to one source.
    const val PRIMARY_HZ = 440
    const val BED_HZ = 880

    // Shorter than the composition, so the bed has to be laid down more than once to cover it.
    val BED_PASS = 500.milliseconds
    val BED_START = 500.milliseconds
    const val BED_VOLUME = 0.5f

    // Long enough to hold hundreds of cycles of either tone, and clear of both ends of the pass it
    // sits in so an encoder's priming cannot drag a neighbouring stretch into the measurement.
    val WINDOW = 250.milliseconds
    val BEFORE_BED = 150.milliseconds
    val LOOPED = 1_100.milliseconds

    // A presence floor rather than a claim about any gain, which is what the ratio makes. Well
    // under the level the fixtures were generated at and well over what a silent stretch reads.
    const val AUDIBLE = 0.02f
    const val SILENT = 0.15f
    const val GAIN_TOLERANCE = 0.05f

    // A hundred milliseconds, eighty-eight cycles of the bed's tone. Long enough for the two
    // frequencies to separate cleanly and short enough to sit well inside one pass.
    val TONE_WINDOW = 100.milliseconds

    // Inside both fixtures, where each holds its own steady tone.
    val FIXTURE_READING = 500.milliseconds

    // The fixtures' own rate, read only when a written file names none.
    const val OUTPUT_FRAME_RATE = 30.0

    // Inside the first pass, where the audio track's level is read before the loop repeats.
    val LOOP_VIDEO_REFERENCE = 500.milliseconds

    // How much of the fixture's own range a written frame has to span to count as carrying its
    // picture. A flat fill spans almost nothing, so half is generous and still nowhere near it.
    const val PICTURE_SPREAD_FLOOR = 0.5f

    // Points across a frame's width and height, sampled at the middle of each cell.
    const val PICTURE_GRID = 8
    const val HALF = 0.5f

    // The fixtures' own rate, asked for by the cases that count frames, so every frame written is one the source holds.
    const val FIXTURE_RATE = 30

    // How far a written sample may sit from its slot, which is the slot's own rounding to whole microseconds.
    val SAMPLE_TOLERANCE = 1.microseconds

    // A clip written in process, 26 frames at 25 fps held until 1.030 s, so each pass of it is 25.75 slots. The audio
    // track runs the composition to three passes of it.
    const val OFF_GRID_RATE = 25
    const val OFF_GRID_FRAMES = 26
    val OFF_GRID_END = 1_030.milliseconds
    val OFF_GRID_RUN = 3_090.milliseconds

    // A clip whose picture runs 25 frames at 25 fps inside a movie its silent sound runs 5 ms longer. Laid from 19 ms,
    // just short of half a slot, its first pass rounds up to end a whole slot past the picture.
    const val SHORT_FRAMES = 25
    val SHORT_PICTURE = 1.seconds
    val SHORT_SOUND = 1_005.milliseconds
    val SHORT_START = 19.milliseconds

    // How long the bed under a held primary runs, seconds past the primary's own picture. The second ends 0.3 of a slot
    // past a slot at OFF_GRID_RATE, so the audio outlasts the last slot the video writes.
    val HELD_RUN = 5.seconds
    val HELD_RUN_PAST_SLOT = 5_012.milliseconds

    // A badge in the top corner of the square off-grid clip, fading in over longer than that clip runs, and where its
    // middle lands. Well clear of the centre the frame index is read at.
    const val BADGE_SCALE = 0.4f
    val BADGE_FADE = 4.seconds
    const val BADGE_X = 0.24f
    const val BADGE_Y = 0.15f

    // How far the badge has to move its pixel from the frame under it to count as drawn, and how close two held
    // readings of it have to be, both summed over three channels.
    const val BADGE_VISIBLE = 24
    const val BADGE_HELD = 9

    // A trim end 0.3 of a frame past a frame of the fixture, and sound running 0.75 of a slot past a picture, both of
    // which a copy keeps exactly.
    val COPY_TRIM_END = 1_010.milliseconds
    val COPY_SOUND = 1_030.milliseconds

    // A rate the backend's timescale divides into whole ticks, one it does not, and a clip short enough to index every
    // frame of it at either.
    const val WHOLE_TICK_RATE = 48
    const val UNEVEN_RATE = 32
    const val INDEXED_FRAMES = 26

    // How long the still after that clip holds, a whole number of slots at either rate.
    val STILL_RUN = 500.milliseconds
  }
}
