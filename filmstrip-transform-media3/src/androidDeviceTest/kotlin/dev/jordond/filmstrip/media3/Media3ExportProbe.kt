package dev.jordond.filmstrip.media3

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.eightBitFrameAt
import dev.jordond.filmstrip.test.firstEightBitFrame
import dev.jordond.filmstrip.test.writeAtomically
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.junit.AssumptionViolatedException
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Exports a composition through the media3 backend on the device and reads frames back out of the
 * file it wrote.
 *
 * Each test class holds its own probe, so each gets its own [filmstrip]. Fixtures are copied out of
 * the test resources into [context]'s cache. A fixture that was not bundled, or a device that lacks
 * what a test needs, fails an assumption, so the test reports skipped rather than passed.
 */
internal class Media3ExportProbe(
  extras: FilmstripBuilder.() -> Unit = {},
) {
  val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  val filmstrip: Filmstrip =
    Filmstrip(context) {
      media3Backend()
      extras()
    }

  /**
   * Plans [composition] at [spec], exports it to a temporary file and returns that file.
   *
   * A refused plan or a failed export throws, so the test fails with the reason.
   */
  suspend fun export(
    composition: Composition,
    spec: ExportSpec = SPEC,
  ): File = export(plan(composition, spec))

  /**
   * Exports [source] as the only clip on one track, with [effects] on the composition.
   */
  suspend fun export(
    source: MediaSource,
    effects: List<EffectSpec>,
  ): File = export(Composition(listOf(Track(listOf(Clip(source)))), effects))

  /**
   * Runs [plan] to a temporary file and returns that file, throwing when the export fails.
   *
   * The statuses are collected off the test scheduler, whose virtual clock would spin the backend's
   * progress poll instead of pacing it.
   */
  suspend fun export(plan: ExportPlan): File =
    when (val output = finish(plan).output) {
      is MediaSink.Path -> File(output.path)
      else -> throw AssertionError("a finished export resolves to a real path, and gave $output")
    }

  /**
   * Exports [composition] at [spec] and reads its first frame back as the codes the encoder wrote, with the matrix
   * and range the file records.
   *
   * A test that reads an exact color reads it here, through the file's own tag, rather than through
   * `MediaMetadataRetriever`'s conversion.
   */
  suspend fun written(
    composition: Composition,
    spec: ExportSpec = SPEC,
  ): Written = writtenBy(finish(plan(composition, spec)))

  /**
   * The first frame of the file [finished] wrote, as [written] reads it.
   */
  fun writtenBy(finished: ExportStatus.Success): Written {
    val file = File(finished.output.shouldBeInstanceOf<MediaSink.Path>().path)
    val (matrix, fullRange) = colorTagOf(file)
    return Written(firstEightBitFrame(file), matrix, fullRange, rotationOf(file))
  }

  /**
   * Runs [plan] to a temporary file and returns the [ExportStatus.Success] it finished on, for a
   * test that reads the adjustments as well as the file. A failed export throws.
   *
   * The statuses are collected off the test scheduler, as in [export].
   */
  suspend fun finish(plan: ExportPlan): ExportStatus.Success {
    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.temporary()).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")
    return finished.shouldBeInstanceOf<ExportStatus.Success>()
  }

  /**
   * Plans [composition] at [spec], throwing with the reasons when the plan is refused.
   */
  suspend fun plan(
    composition: Composition,
    spec: ExportSpec = SPEC,
  ): ExportPlan =
    when (val verdict = filmstrip.plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
    }

  /**
   * Probes [source] with [filmstrip], failing the test when the probe does not succeed.
   */
  suspend fun infoOf(source: MediaSource): MediaInfo =
    filmstrip.probe(source).shouldBeInstanceOf<ProbeResult.Success>().info

  /**
   * Decodes the frame of [video] nearest [at].
   */
  fun frame(
    video: File,
    at: Duration = MID_CLIP,
  ): Bitmap = eightBitFrameAt(video, at)

  /**
   * The test resource [name] copied into the cache. Skips the calling test when the fixture is not
   * among the test APK's resources.
   */
  fun fixture(name: String = CLIP): MediaSource = MediaSource.of(fixtureFile(name).path)

  /**
   * The same fixture as [fixture] gives, as the file it was copied to, for a test that reads the
   * file itself rather than editing it. Skips the calling test when the fixture is not among the
   * test APK's resources.
   */
  fun fixtureFile(name: String = CLIP): File {
    val stream =
      javaClass.classLoader?.getResourceAsStream(name)
        ?: throw AssumptionViolatedException("the fixture $name is not among the test APK's resources")
    val file = File(context.cacheDir, name)
    stream.use { input -> file.writeAtomically { input.copyTo(it) } }
    return file
  }

  /**
   * The orange badge from [badgeFile] as a watermark in [corner], at [BADGE_SCALE] and full
   * opacity.
   */
  fun watermark(
    corner: Corner,
    margin: Float = WATERMARK_MARGIN,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
  ) = ImageOverlay(
    ImageSource.of(badgeFile(context).path),
    corner,
    margin,
    BADGE_SCALE,
    1f,
    visibleDuring,
    animation,
  )

  companion object {
    val TIMEOUT = 5.minutes
    val SPEC = ExportSpec(heightPx = 240)

    // Halfway through the two second fixture.
    val MID_CLIP = 1_000.milliseconds

    const val CLIP = "android_export_a.mp4"
    const val CLIP_B = "android_export_b.mp4"

    // Ten-bit BT.2020, one PQ and one HLG.
    const val HDR_CLIP = "android_export_hdr.mp4"
    const val HLG_CLIP = "android_export_hdr_hlg.mp4"

    const val WATERMARK_MARGIN = 0.02f
  }
}
