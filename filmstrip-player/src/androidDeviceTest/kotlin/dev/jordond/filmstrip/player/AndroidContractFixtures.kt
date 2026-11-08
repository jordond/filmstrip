package dev.jordond.filmstrip.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.media3ExportEngine
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.eightBitFrameAt
import dev.jordond.filmstrip.test.eightBitFrameCovering
import dev.jordond.filmstrip.test.writeAtomically
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The context the instrumentation runs against, which is what media3 decodes and encodes on.
 */
internal fun contractContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

/**
 * The clip every Android contract suite plays, packaged into the test APK by the fixture task.
 *
 * The task is a dependency of the instrumented build, so the resource is there or the build failed
 * before a test ran. Nothing here skips: a suite that returns early on a missing fixture reports
 * green without having asserted anything.
 */
internal fun androidFixtureClip(): MediaSource {
  val file = File(contractContext().cacheDir, CLIP_NAME)
  if (!file.exists()) {
    val loader = AndroidContractFixtures::class.java.classLoader ?: fail("the test APK has no class loader")
    val stream = loader.getResourceAsStream(CLIP_NAME) ?: fail("$CLIP_NAME was not packaged into the test APK")
    stream.use { input -> file.writeAtomically { input.copyTo(it) } }
  }
  return MediaSource.of(file.path)
}

/**
 * The fixture's own samples remuxed under a container tag naming SMPTE 170M, so the clip reads as SD.
 *
 * A tag the muxer dropped would leave nothing for a test of SD colour to read, so that fails here.
 */
internal fun androidSdTaggedClip(): MediaSource {
  androidFixtureClip()
  val cache = contractContext().cacheDir
  val tagged = File(cache, "sd-tagged-$CLIP_NAME")
  val extractor = MediaExtractor().apply { setDataSource(File(cache, CLIP_NAME).path) }
  val muxer = MediaMuxer(tagged.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  try {
    val video =
      (0 until extractor.trackCount).first {
        extractor
          .getTrackFormat(it)
          .getString(MediaFormat.KEY_MIME)
          .orEmpty()
          .startsWith("video/")
      }
    extractor.selectTrack(video)
    val format =
      extractor.getTrackFormat(video).apply {
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
      }
    val track = muxer.addTrack(format)
    muxer.start()
    val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
    val info = MediaCodec.BufferInfo()
    while (true) {
      val size = extractor.readSampleData(buffer, 0)
      if (size < 0) break
      info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
      muxer.writeSampleData(track, buffer, info)
      extractor.advance()
    }
    muxer.stop()
  } finally {
    muxer.release()
    extractor.release()
  }
  val written = MediaExtractor().apply { setDataSource(tagged.path) }
  try {
    written.getTrackFormat(0).getInteger(MediaFormat.KEY_COLOR_STANDARD) shouldBe MediaFormat.COLOR_STANDARD_BT601_NTSC
  } finally {
    written.release()
  }
  return MediaSource.of(tagged.path)
}

private const val SAMPLE_BUFFER_BYTES = 1 shl 20

/**
 * One trimmed clip of the fixture, with [effects] over the whole composition.
 */
internal fun androidFixtureComposition(
  effects: List<EffectSpec> = emptyList(),
  fill: Fill = Fill.Black,
): EditComposition =
  EditComposition(
    tracks = listOf(Track(listOf(Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
    effects = effects,
    fill = fill,
  )

/**
 * The fixture composition with its clip handed over as the bytes of the file.
 */
internal fun androidFixtureBytesComposition(): EditComposition {
  val named = androidFixtureClip().shouldBeInstanceOf<MediaSource.Path>()
  val bytes = File(named.path).readBytes()
  return EditComposition(
    tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
  )
}

/**
 * The frame the export writes at [position], decoded back out of the file it wrote.
 *
 * This is the export path and not a second preview: the edit is negotiated again through
 * [media3ExportEngine], lowered again by `toMedia3`, and run through `Transformer` onto a real file
 * with a real encoder at [PREVIEW_PARITY_EXPORT_BITRATE]. Only the encoder separates it from the preview's own
 * frame, which is the one thing a preview is documented not to carry, and it is what the thresholds in
 * [AndroidPixelContractMediaTest] are set against.
 *
 * Each composition is exported once and its file kept, since the suite compares several frames of
 * the same edit.
 */
internal suspend fun androidExportFrame(
  composition: EditComposition,
  position: Duration,
): TestFrame = eightBitFrameAt(androidExportedFile(composition), position).toTestFrame()

/**
 * The frame [composition] exports that a player shows at [position], the last one the file presents at or before it.
 *
 * The export is the one [androidExportFrame] reads.
 */
internal suspend fun androidExportFrameCovering(
  composition: EditComposition,
  position: Duration,
): TestFrame = eightBitFrameCovering(androidExportedFile(composition), position).toTestFrame()

/**
 * The file [composition] exports to, written once per composition.
 */
internal suspend fun androidExportedFile(composition: EditComposition): File =
  exports.getOrPut(composition) { export(composition) }

internal suspend fun export(composition: EditComposition): File {
  val engine = media3ExportEngine(CONTRACT_COMPONENTS)
  val plan =
    when (val verdict = engine.plan(composition, ExportSpec(bitrate = PREVIEW_PARITY_EXPORT_BITRATE))) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> fail("the export refused the fixture: ${verdict.reasons.map { it.message }}")
    }

  val finished = engine.export(plan, MediaSink.temporary()).toList().last()
  if (finished is ExportStatus.Failure) fail("the export failed: ${finished.error.message}")
  val success = finished as? ExportStatus.Success ?: fail("the export ended on $finished")
  return when (val output = success.output) {
    is MediaSink.Path -> File(output.path)
    else -> fail("a finished export resolves to a real path, and gave $output")
  }
}

/**
 * This bitmap as the tightly packed RGBA the comparison helpers take.
 *
 * Read through `getPixels` rather than out of the backing buffer, since a bitmap is free to pad its
 * rows and the packed form must carry none of that. The alpha channel is written full: an exported
 * frame is opaque, and the decoder can hand back whatever it likes there.
 */
private fun Bitmap.toTestFrame(): TestFrame {
  val colors = IntArray(width * height)
  getPixels(colors, 0, width, 0, 0, width, height)

  val pixels = ByteArray(colors.size * CHANNELS)
  for (index in colors.indices) {
    val color = colors[index]
    val base = index * CHANNELS
    pixels[base] = (color shr RED_SHIFT).toByte()
    pixels[base + 1] = (color shr GREEN_SHIFT).toByte()
    pixels[base + 2] = color.toByte()
    pixels[base + 3] = OPAQUE
  }
  return TestFrame(pixels, Size(width, height))
}

/**
 * The components a host that registered the media3 backend would hand a preview.
 *
 * Both sides of the pixel contract lower through this one registry, so a difference between them is
 * a difference in how the graph is built rather than in what was registered.
 */
internal val CONTRACT_COMPONENTS: ComponentRegistry =
  ComponentRegistry
    .Builder()
    .addEffectResolver(
      BuiltInEffectResolver(),
    ).build()

/**
 * Long enough to play through and seek inside, and a whole number of frames at the fixture's rate.
 */
internal val CLIP_LENGTH: Duration = 1500.milliseconds

/**
 * Two runs of the fixture clip, the second travelling under [FIXTURE_PAN].
 *
 * The panned clip starts at [PANNED_CLIP_START] rather than at zero, which is the only layout that
 * separates a chain reading the clip it was decoded from from one reading the composition. At zero
 * the two clocks are the same number.
 */
internal fun androidPannedClipComposition(): EditComposition =
  EditComposition(
    tracks =
      listOf(
        Track(
          listOf(
            Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
            Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH), effects = listOf(FIXTURE_PAN)),
          ),
        ),
      ),
  )

/**
 * Where the panned clip's span starts, which is the end of the clip that runs before it.
 */
internal val PANNED_CLIP_START: Duration = CLIP_LENGTH

/**
 * The pan a panned fixture travels under, from a window at one edge of the frame to one at the
 * other.
 *
 * Windows narrow enough that two readings inside a span are plainly different pictures, and linear
 * so every fraction of the travel is a figure both backends work out the same way.
 */
internal val FIXTURE_PAN: KenBurns =
  KenBurns(
    from = NormalizedRect(0f, 0f, 0.4f, 1f),
    to = NormalizedRect(0.6f, 0f, 1f, 1f),
    easing = Easing.Linear,
  )

/**
 * Two readings inside a panned span, either side of the halfway point every curve agrees on.
 */
internal val PAN_FRACTIONS: List<Double> = listOf(0.4, 0.6)

/**
 * The frame the fixture decodes at, which is the frame an export of it writes.
 */
internal val FIXTURE_FRAME: Size = Size(640, 360)

/**
 * Composition times both suites compare at, each landing exactly on the fixture's 30fps grid.
 */
internal val PROBE_POSITIONS: List<Duration> = listOf(300.milliseconds, 900.milliseconds)

/**
 * The SSIM a frame the player draws of the fixture, a preview readback or a precise thumbnail, holds to against the
 * same frame read out of an export of it.
 *
 * The export has been through the encoder once and the drawn frame has not, so this sits below
 * [TWO_PREVIEW_MIN_SSIM], what two renderings of one graph score. It sits above the frames either side, which differ
 * from it by grain, once the export is at [PREVIEW_PARITY_EXPORT_BITRATE]. A filter that dims the picture dims the
 * grain too, so between sync samples a neighbour of a dimmed frame reads above this, and only an undimmed composition
 * holds a tile to its own frame there.
 */
internal const val ENCODED_MIN_SSIM: Double = 0.985

/**
 * The SSIM two preview readbacks of the same frame hold to against each other, where neither went through an encoder.
 *
 * Only decoder noise separates the two, so this sits above [ENCODED_MIN_SSIM]. A graph that drew the wrong frame
 * scores far below it.
 */
internal const val TWO_PREVIEW_MIN_SSIM: Double = 0.99

/**
 * The frame rate the fixture's `FixtureSpec` pins, which is the output frame rate a composition of it plays at.
 */
internal const val FIXTURE_FRAME_RATE = 30

/**
 * How long one frame of the fixture runs for, at [FIXTURE_FRAME_RATE].
 */
internal val FIXTURE_FRAME_STEP: Duration = 1.seconds / FIXTURE_FRAME_RATE

/**
 * How far apart the fixture's sync samples sit, which is the keyframe interval it was encoded at.
 *
 * An upper bound rather than the exact spacing: x264 is free to place one early and does.
 */
internal val FIXTURE_SYNC_INTERVAL: Duration = 1.seconds

/**
 * A composition time well inside a group of pictures rather than near either end of one, and still
 * exactly on the fixture's frame grid.
 *
 * What tells a decode to the requested frame apart from a snap to the nearest sync sample. A
 * position at either end agrees under both, which is what a probe on the grid's edges would miss.
 */
internal val MID_GOP_POSITION: Duration = 700.milliseconds

private const val CLIP_NAME = "apple_export_a.mp4"
private const val CHANNELS = 4
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val OPAQUE = 0xFF.toByte()

private val exports = mutableMapOf<EditComposition, File>()

private object AndroidContractFixtures

/**
 * A composition over a path nothing can open, which no planner can resolve.
 */
internal fun androidFixtureBrokenComposition(): EditComposition =
  EditComposition(tracks = listOf(Track(listOf(Clip(MediaSource.of("/does/not/exist/filmstrip.mp4"))))))
