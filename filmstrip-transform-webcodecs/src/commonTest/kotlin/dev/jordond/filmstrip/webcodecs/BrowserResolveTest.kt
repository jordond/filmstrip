package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the browser engine hands a preview, and what it costs to ask twice.
 *
 * The cap is taken in the middle of the range rather than at either end, since a frame that lands
 * on the natural size or on zero agrees with a lowering that ignored the cap entirely.
 */
class BrowserResolveTest {
  private val source = MediaSource.Bytes("clip".encodeToByteArray())
  private val composition =
    Composition(
      tracks = listOf(Track(listOf(Clip(source)))),
      effects = listOf(Brightness(BRIGHTNESS)),
      audio = AudioSpec.Remove,
    )

  @Test
  fun cappedResolveLowersSmallAndLaysTextOutAtTheExportFrame() =
    runTest {
      val recorded = RecordingResolver()
      val engine = engineOf(recorded)

      val natural = engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()
      val naturalSize = natural.composition.output.size
      naturalSize shouldBe NATURAL

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
      withClue("the capped frame is $cappedSize") { (cappedSize.width < naturalSize.width) shouldBe true }
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
  fun resolvingTwiceProbesOnce() =
    runTest {
      val prober = CountingProber(info())
      val engine =
        webCodecsExportEngine(
          components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
          prober = prober,
        )

      engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()
      engine.resolve(composition, ExportSpec(heightPx = CAPPED_HEIGHT)).shouldBeInstanceOf<ResolveResult.Resolved>()

      prober.calls shouldBe 1
    }

  private fun engineOf(recorded: EffectResolver) =
    webCodecsExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addEffectResolver(recorded)
          .build(),
      prober = CountingProber(info()),
    )

  private fun info(): MediaInfo =
    MediaInfo(
      duration = DURATION_MS.milliseconds,
      video =
        VideoTrackInfo(
          codedSize = NATURAL,
          displaySize = NATURAL,
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

  private companion object {
    val NATURAL = Size(1280, 720)
    const val CAP_FRACTION = 0.6f
    const val CAPPED_HEIGHT = 432
    const val BRIGHTNESS = 0.5f
    const val DURATION_MS = 2_000
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

/**
 * Answers every probe with the same info, counting how often it was asked.
 */
private class CountingProber(
  private val info: MediaInfo,
) : MediaProber {
  var calls: Int = 0
    private set

  override suspend fun probe(source: MediaSource): ProbeResult {
    calls++
    return ProbeResult.Success(info)
  }
}
