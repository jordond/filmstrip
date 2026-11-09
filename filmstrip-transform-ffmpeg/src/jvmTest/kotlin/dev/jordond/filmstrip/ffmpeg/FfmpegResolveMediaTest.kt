package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaProberFactory
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the ffmpeg engine hands a preview, and what it costs to ask twice.
 *
 * The cap is taken in the middle of the range rather than at either end, since a frame that lands
 * on the natural size or on zero agrees with a lowering that ignored the cap entirely.
 *
 * Skipped rather than failed when there is no ffmpeg, on the same terms as the export test.
 */
class FfmpegResolveMediaTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")

  private val composition
    get() =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of(landscape.absolutePath))))),
        effects = listOf(Brightness(BRIGHTNESS)),
        audio = AudioSpec.Remove,
      )

  @Test
  fun `a capped resolve lowers at the capped frame and lays text out at the export's`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      val recorded = RecordingResolver()
      val engine =
        FfmpegExportEngine(
          ComponentRegistry
            .Builder()
            .addEffectResolver(BuiltInEffectResolver())
            .addEffectResolver(recorded)
            .build(),
          FfmpegRuntime.of(FfmpegConfig()),
        )

      val natural = engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()
      val naturalSize = natural.composition.output.size

      val cap = (naturalSize.height * CAP_FRACTION).toInt()
      val capped =
        engine
          .resolve(
            composition,
            ExportSpec(heightPx = cap),
            naturalSize,
          ).shouldBeInstanceOf<ResolveResult.Resolved>()
      val cappedSize = capped.composition.output.size

      cappedSize.height shouldBe cap
      withClue("the capped frame is $cappedSize") { (cappedSize.width < naturalSize.width).shouldBeTrue() }
      capped.composition.layoutSize shouldBe naturalSize

      // The natural frame is settled before the cap is applied to it, so the cap's own lowering is
      // the second of the two.
      recorded.seen.size shouldBe 2
      val uncapped = recorded.seen.first()
      uncapped.inputSize shouldBe naturalSize
      uncapped.layoutSize shouldBe naturalSize

      val underCap = recorded.seen.last()
      underCap.inputSize shouldBe cappedSize
      underCap.layoutSize shouldBe naturalSize
    }

  @Test
  fun `resolving twice spawns one ffprobe`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      // Unshared, because the count this asserts is what a cold cache costs and every other suite
      // in this module resolves through the runtime the default config is memoised against.
      val runtime = FfmpegRuntime.unshared(FfmpegConfig())
      val engine =
        FfmpegExportEngine(ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(), runtime)

      engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()
      engine.resolve(composition, ExportSpec(heightPx = CAPPED_HEIGHT)).shouldBeInstanceOf<ResolveResult.Resolved>()

      runtime.probeSpawns shouldBe 1
    }

  // A host prober answers ahead of ffprobe here the way it does on every other engine, so what it says the source is
  // is what the plan is built on, and ffprobe is only asked for the primaries a MediaInfo does not hold.
  @Test
  fun `a registered prober answers ahead of ffprobe`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      val runtime = FfmpegRuntime.unshared(FfmpegConfig())
      val components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addMediaProberFactory(MediaProberFactory { MediaProber { ProbeResult.Success(hostInfo) } })
          .build()
      val engine = FfmpegExportEngine(components, runtime)

      val resolved = engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()

      resolved.composition.output.size shouldBe HOST_SIZE
      runtime.probeSpawns shouldBe 0
      runtime.sdColorSpawns shouldBe 1
    }

  // One edit is negotiated to plan it, to resolve it for a preview and again to export it. A sync
  // sample read fresh each time could move between those, and a window that opened in one place for
  // the preview and another for the file is exactly what the snap exists to stop.
  @Test
  fun `planning and resolving a snapped trim spawns one sync-sample probe`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      val runtime = FfmpegRuntime.unshared(FfmpegConfig())
      val engine =
        FfmpegExportEngine(ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(), runtime)
      val trimmed =
        Composition(
          listOf(
            Track(
              listOf(
                Clip(
                  source = MediaSource.of(landscape.absolutePath),
                  trim = TimeRange.of(TRIM_START, TRIM_END),
                  snapWithin = SNAP_WITHIN,
                ),
              ),
            ),
          ),
        )

      engine.plan(trimmed, ExportSpec())
      engine.resolve(trimmed, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()

      runtime.syncSampleSpawns shouldBe 1
    }

  // Everything the backend builds resolves through one runtime per config, so the second engine to
  // see a clip probes nothing. A count rather than an instance check, since what the sharing is for
  // is the cache rather than the object.
  @Test
  fun `two engines built from an equal config share one probe cache`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      val first = ffmpegExportEngine(components)
      val second = ffmpegExportEngine(components)

      first.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()
      val spawns = FfmpegRuntime.of(FfmpegConfig()).probeSpawns
      second.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()

      FfmpegRuntime.of(FfmpegConfig()).probeSpawns shouldBe spawns
    }

  // The ladder costs an encode per encoder the build carries, which is the most expensive thing this
  // backend does before its first frame. Every thumbnail run builds an engine of its own, so the
  // answer has to outlive the engine that measured it.
  @Test
  fun `two engines over one runtime measure the encoder ladder once`() =
    runTest(timeout = TIMEOUT) {
      // Unshared, because what this asserts is what a cold runtime costs and every other suite in
      // this module measures through the one the default config is memoised against.
      val runtime = FfmpegRuntime.unshared(FfmpegConfig())
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      val first = FfmpegExportEngine(components, runtime)
      val second = FfmpegExportEngine(components, runtime)

      // Gated on the toolchain rather than on a fixture, since this reads nothing off disk and the
      // fixtures download whether or not the machine has an ffmpeg to run them through.
      if (first.capabilities() is CapabilitiesResult.Failure) return@runTest
      second.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>()

      runtime.capabilityMeasures shouldBe 1
    }

  private companion object {
    val TIMEOUT = 5.minutes

    // A window inside the landscape fixture, opening between two of its sync samples so the snap
    // has somewhere to move the cut to.
    val TRIM_START = 1_500.milliseconds
    val TRIM_END = 3_000.milliseconds
    val SNAP_WITHIN = 2.seconds

    const val CAP_FRACTION = 0.6f
    const val CAPPED_HEIGHT = 216
    const val BRIGHTNESS = 0.5f

    // Half the landscape fixture's own frame, so a plan built on ffprobe's answer cannot land on it.
    val HOST_SIZE = Size(320, 180)

    val hostInfo =
      MediaInfo(
        duration = 2.seconds,
        video =
          VideoTrackInfo(
            codedSize = HOST_SIZE,
            displaySize = HOST_SIZE,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            frameRate = 30f,
            codec = trackCodecOf("avc1"),
            bitDepth = 8,
            colorSpace = ColorSpace.Bt709,
            hdrTransfer = null,
            bitrate = null,
          ),
        audio = null,
        isExportable = true,
      )
  }
}

/**
 * Records the attributes every effect is lowered against, then declines so the real resolver runs.
 */
private class RecordingResolver : EffectResolver {
  val seen: MutableList<Attributes> = mutableListOf()

  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    seen += attributes
    return null
  }
}
