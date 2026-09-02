package dev.jordond.filmstrip.webcodecs

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
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ResolveResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
    EditComposition(
      tracks = listOf(Track(listOf(Clip(source)))),
      effects = listOf(Brightness(BRIGHTNESS)),
      audio = AudioSpec.Remove,
    )

  @Test
  fun cappedResolveLowersSmallAndLaysTextOutAtTheExportFrame() =
    runTest {
      val recorded = RecordingResolver()
      val engine = engineOf(recorded)

      val natural = assertIs<ResolveResult.Resolved>(engine.resolve(composition, ExportSpec()))
      val naturalSize = natural.composition.output.size
      assertEquals(NATURAL, naturalSize)

      val cap = (naturalSize.height * CAP_FRACTION).toInt()
      val capped =
        assertIs<ResolveResult.Resolved>(engine.resolve(composition, ExportSpec(targetHeight = cap), naturalSize))
      val cappedSize = capped.composition.output.size

      assertEquals(cap, cappedSize.height)
      assertTrue(cappedSize.width < naturalSize.width, "the capped frame is $cappedSize")
      assertEquals(naturalSize, capped.composition.layoutSize)

      // The natural frame is settled before the cap is applied to it, so the cap's own lowering is
      // the second of the two.
      assertEquals(2, recorded.seen.size)
      val uncapped = recorded.seen.first()
      assertEquals(naturalSize, uncapped.inputSize)
      assertEquals(naturalSize, uncapped.layoutSize)

      val underCap = recorded.seen.last()
      assertEquals(cappedSize, underCap.inputSize)
      assertEquals(naturalSize, underCap.layoutSize)
    }

  @Test
  fun resolvingTwiceProbesOnce() =
    runTest {
      val prober = CountingProber(info())
      val engine =
        browserExportEngine(
          components = ComponentRegistry.Builder().add(BuiltInEffectResolver()).build(),
          prober = prober,
        )

      assertIs<ResolveResult.Resolved>(engine.resolve(composition, ExportSpec()))
      assertIs<ResolveResult.Resolved>(engine.resolve(composition, ExportSpec(targetHeight = CAPPED_HEIGHT)))

      assertEquals(1, prober.calls)
    }

  private fun engineOf(recorded: EffectResolver) =
    browserExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .add(BuiltInEffectResolver())
          .add(recorded)
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
