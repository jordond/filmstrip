package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
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
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What the ffmpeg engine hands a preview, and what it costs to ask twice.
 *
 * The cap is taken in the middle of the range rather than at either end, since a frame that lands
 * on the natural size or on zero agrees with a lowering that ignored the cap entirely.
 *
 * Skipped rather than failed when there is no ffmpeg, on the same terms as the export test.
 */
class FfmpegResolveTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")

  private val composition
    get() =
      EditComposition(
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
            .add(BuiltInEffectResolver())
            .add(recorded)
            .build(),
          FfmpegRuntime.of(FfmpegConfig()),
        )

      val natural = assertIs<ResolveResult.Resolved>(engine.resolve(composition, ExportSpec()))
      val naturalSize = natural.composition.output.size

      val cap = (naturalSize.height * CAP_FRACTION).toInt()
      val capped =
        assertIs<ResolveResult.Resolved>(engine.resolve(composition, ExportSpec(targetHeight = cap), naturalSize))
      val cappedSize = capped.composition.output.size

      cappedSize.height shouldBe cap
      assertTrue(cappedSize.width < naturalSize.width, "the capped frame is $cappedSize")
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
        FfmpegExportEngine(ComponentRegistry.Builder().add(BuiltInEffectResolver()).build(), runtime)

      assertIs<ResolveResult.Resolved>(engine.resolve(composition, ExportSpec()))
      assertIs<ResolveResult.Resolved>(engine.resolve(composition, ExportSpec(targetHeight = CAPPED_HEIGHT)))

      runtime.probeSpawns shouldBe 1
    }

  // Everything the backend builds resolves through one runtime per config, so the second engine to
  // see a clip probes nothing. A count rather than an instance check, since what the sharing is for
  // is the cache rather than the object.
  @Test
  fun `two engines built from an equal config share one probe cache`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      val components = ComponentRegistry.Builder().add(BuiltInEffectResolver()).build()
      val first = ffmpegExportEngine(components)
      val second = ffmpegExportEngine(components)

      assertIs<ResolveResult.Resolved>(first.resolve(composition, ExportSpec()))
      val spawns = FfmpegRuntime.of(FfmpegConfig()).probeSpawns
      assertIs<ResolveResult.Resolved>(second.resolve(composition, ExportSpec()))

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
      val components = ComponentRegistry.Builder().add(BuiltInEffectResolver()).build()
      val first = FfmpegExportEngine(components, runtime)
      val second = FfmpegExportEngine(components, runtime)

      // Gated on the toolchain rather than on a fixture, since this reads nothing off disk and the
      // fixtures download whether or not the machine has an ffmpeg to run them through.
      if (first.capabilities() is CapabilitiesResult.Failure) return@runTest
      assertIs<CapabilitiesResult.Success>(second.capabilities())

      runtime.capabilityMeasures shouldBe 1
    }

  private companion object {
    val TIMEOUT = 5.minutes
    const val CAP_FRACTION = 0.6f
    const val CAPPED_HEIGHT = 216
    const val BRIGHTNESS = 0.5f
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
