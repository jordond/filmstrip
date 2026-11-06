package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.webcodecs.internal.BrowserPlanner
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.browserMaxTextureSide
import dev.jordond.filmstrip.webcodecs.internal.browserParityOf
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the browser planner lowers overlays: which scope each one lands on, what the color folds read
 * from them, and what the planner says it can draw.
 */
class BrowserOverlayPlanTest {
  // A clip overlay measures against the clip's frame and a composition one against the output's. The
  // second clip is narrower than the output the first one sets, so each rect says which frame it was
  // placed on.
  @Test
  fun overlaysLandOnTheClipOrTheRenderByScope() =
    runTest {
      val onClip = ImageOverlay(IMAGE, Corner.TopStart)
      val onComposition = ImageOverlay(IMAGE, Corner.BottomEnd)
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(FILLER), Clip(SOURCE, effects = listOf(onClip))))),
          effects = listOf(onComposition),
          audio = AudioSpec.Remove,
        )

      val render =
        lower(composition, mapOf(FILLER to infoOf(OUTPUT_FRAME), SOURCE to infoOf(CLIP_FRAME))).graph.shouldNotBeNull()
      val output = Size(render.width, render.height)
      output shouldBe OUTPUT_FRAME

      withClue("the filler drew ${render.clips.first().overlays}") {
        (
          render.clips
            .first()
            .overlays
            .isEmpty()
        ) shouldBe true
      }
      val clipDraw =
        (
          render.clips
            .last()
            .overlays
            .single()
            .drawAt(CLIP_LENGTH)
        ).shouldNotBeNull()
      clipDraw.rect shouldBe onClip.placedOn(CLIP_FRAME, PNG).rectOn(CLIP_FRAME)
      val compositionDraw =
        render.compositionOverlays
          .single()
          .drawAt(Duration.ZERO)
          .shouldNotBeNull()
      compositionDraw.rect shouldBe onComposition.placedOn(output, PNG).rectOn(output)
    }

  @Test
  fun anOverlayDrawsAtTheOpacityTheSharedSamplerAnswersMidRamp() =
    runTest {
      val fading = ImageOverlay(IMAGE, Corner.TopEnd, animation = fade(inDuration = RAMP))
      val composition = compositionOf(effects = listOf(fading))

      val overlay =
        lower(composition)
          .graph
          .shouldNotBeNull()
          .compositionOverlays
          .single()

      for (time in listOf(RAMP / 4, RAMP * 3 / 4)) {
        val expected = fading.frameAt(time, TimeRange(Duration.ZERO, CLIP_LENGTH)).shouldNotBeNull().opacity
        withClue("opacity at $time") { overlay.drawAt(time).shouldNotBeNull().opacity shouldBe expected }
      }
    }

  @Test
  fun theColorMatricesReadNothingFromAnOverlay() =
    runTest {
      val plain = compositionOf(clipEffects = listOf(Sepia(HALF)), effects = listOf(Sepia(HALF)))
      val overlaid =
        compositionOf(
          clipEffects = listOf(Sepia(HALF), ImageOverlay(IMAGE, Corner.TopStart)),
          effects = listOf(Sepia(HALF), ImageOverlay(IMAGE, Corner.BottomEnd)),
        )

      val without =
        lower(plain)
          .graph
          .shouldNotBeNull()
          .clips
          .single()
      val with =
        lower(overlaid)
          .graph
          .shouldNotBeNull()
          .clips
          .single()

      with.colorMatrix shouldBe without.colorMatrix
      with.compositionColorMatrix shouldBe without.compositionColorMatrix
      with.matrix.at(Duration.ZERO) shouldBe without.matrix.at(Duration.ZERO)
    }

  @Test
  fun overlaysAreExactOnTheBrowser() =
    runTest {
      browserParityOf(EffectIds.IMAGE_OVERLAY) shouldBe EffectParity.Exact
      browserParityOf(EffectIds.TEXT_OVERLAY) shouldBe EffectParity.Exact

      val composition =
        compositionOf(clipEffects = listOf(TextOverlay("clip")), effects = listOf(ImageOverlay(IMAGE, Corner.TopEnd)))
      val verdict = lower(composition).verdict.shouldBeInstanceOf<Verdict.Capable>()
      verdict.plan.parity shouldBe EffectParity.Exact
    }

  // A kept grade draws both kinds of overlay rather than refusing them, on either transfer, and the render hands the
  // compositor the transfer that maps them.
  @Test
  fun overlaysOnAKeptGradeResolveOnBothTransfers() =
    runTest {
      val composition =
        compositionOf(clipEffects = listOf(TextOverlay("clip")), effects = listOf(ImageOverlay(IMAGE, Corner.TopEnd)))

      for (transfer in HdrTransfer.entries) {
        val infos = composition.clips.associate { it.source to infoOf(OUTPUT_FRAME, transfer) }
        val lowering = lower(composition, infos, HDR_SPEC, HDR_DEVICE)

        val verdict = withClue("the $transfer plan") { lowering.verdict.shouldBeInstanceOf<Verdict.Capable>() }
        verdict.plan.parity shouldBe EffectParity.Exact
        val render = lowering.graph.shouldNotBeNull()
        render.hdrTransfer shouldBe transfer
        val clip = render.clips.single()
        withClue("the $transfer clip's overlays") { clip.overlays.size shouldBe 1 }
        withClue("the $transfer composition's overlays") { render.compositionOverlays.size shouldBe 1 }
      }
    }

  // WebGL's texture side is this backend's own limit, read off the page by the probe the planner hands its resolvers.
  @Test
  fun aPngAsLongAsThePagesTextureSidePlansAndOnePastIsRefused() =
    runTest {
      val side = browserMaxTextureSide()

      val fits = compositionOf(effects = listOf(ImageOverlay(stripOf(Size(side, 1)), Corner.TopStart)))
      val planned = lower(fits)
      withClue("a $side by 1 PNG planned as ${planned.verdict}") {
        planned.verdict.shouldBeInstanceOf<Verdict.Capable>()
      }
      planned.graph
        .shouldNotBeNull()
        .compositionOverlays.size shouldBe 1

      val past = compositionOf(effects = listOf(ImageOverlay(stripOf(Size(side + 1, 1)), Corner.TopStart)))
      val refused =
        withClue("a ${side + 1} by 1 PNG planned") {
          lower(past).verdict.shouldBeInstanceOf<Verdict.Incapable>()
        }
      val reason = refused.reasons.filterIsInstance<FilmstripError.UnsupportedEffect>().single()
      reason.specId shouldBe EffectIds.IMAGE_OVERLAY
      withClue(reason.message) {
        ("${side + 1} by 1 picture" in reason.message && "at most $side pixels on a side" in reason.message) shouldBe
          true
      }
    }

  @Test
  fun theBrowserAdvertisesTextRendering() =
    runTest {
      val seen = mutableListOf<RenderCapabilities>()
      val recording =
        object : EffectResolver {
          override fun resolve(
            spec: EffectSpec,
            capabilities: RenderCapabilities,
            attributes: Attributes,
          ): EffectResolution? {
            seen += capabilities
            return null
          }
        }
      val composition = compositionOf(effects = listOf(TextOverlay("caption")))

      BrowserPlanner(listOf(recording, BuiltInEffectResolver()))
        .lower(composition, Ask(SPEC, DEVICE, infosOf(composition)))

      withClue("no resolver was asked") { seen.isNotEmpty() shouldBe true }
      withClue("advertised ${seen.map { it.features }}") {
        seen.all { RenderFeature.TextRendering in it.features } shouldBe true
      }
    }

  private suspend fun lower(
    composition: EditComposition,
    infos: Map<MediaSource, MediaInfo> = infosOf(composition),
    spec: ExportSpec = SPEC,
    device: DeviceCapabilities = DEVICE,
  ): LoweredExport<BrowserRender> {
    val resolver = BuiltInEffectResolver()
    resolver.prepare(composition)
    return BrowserPlanner(listOf(resolver)).lower(composition, Ask(spec, device, infos))
  }

  private fun stripOf(size: Size): ImageSource =
    ImageSource.ofBytes(OverlayPixels.pngOf(size, AuthoredColors.MID_GREEN))

  private fun compositionOf(
    clipEffects: List<EffectSpec> = emptyList(),
    effects: List<EffectSpec> = emptyList(),
  ): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(SOURCE, effects = clipEffects)))),
      effects = effects,
      audio = AudioSpec.Remove,
    )

  private fun infosOf(composition: EditComposition): Map<MediaSource, MediaInfo> =
    composition.clips.associate { it.source to infoOf(OUTPUT_FRAME) }

  private fun infoOf(
    size: Size,
    transfer: HdrTransfer? = null,
  ): MediaInfo =
    MediaInfo(
      duration = CLIP_LENGTH,
      video =
        VideoTrackInfo(
          codedSize = size,
          displaySize = size,
          rotationDegrees = 0,
          pixelAspectRatio = 1f,
          frameRate = 30f,
          codec = trackCodecOf(if (transfer == null) "avc1" else "vp09"),
          bitDepth = if (transfer == null) 8 else 10,
          colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
          hdrTransfer = transfer,
          bitrate = null,
        ),
      audio = null,
      isExportable = true,
    )

  private companion object {
    val SOURCE: MediaSource = MediaSource.Bytes("a".encodeToByteArray())
    val FILLER: MediaSource = MediaSource.Bytes("filler".encodeToByteArray())
    val PNG = Size(8, 4)
    val IMAGE: ImageSource = ImageSource.ofBytes(OverlayPixels.pngOf(PNG, AuthoredColors.MID_GREEN))
    val OUTPUT_FRAME = Size(1280, 720)
    val CLIP_FRAME = Size(640, 720)
    val CLIP_LENGTH = 4.seconds
    val RAMP = 2.seconds
    const val HALF = 0.5f

    val SPEC = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None)
    val DEVICE =
      DeviceCapabilities(
        video = listOf(VideoEncoderCapability(VideoCodec.H264, null, Size(3840, 2160), null, null, false, 2)),
        audio = emptyList(),
        supportsHdrEncoding = false,
        concurrentSessionBudget = null,
      )

    val HDR_SPEC = ExportSpec(audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
    val HDR_DEVICE =
      DeviceCapabilities(
        video =
          listOf(VideoCodec.H264, VideoCodec.Vp9).map {
            VideoEncoderCapability(it, null, Size(3840, 2160), null, null, false, 2)
          },
        audio = emptyList(),
        supportsHdrEncoding = true,
        concurrentSessionBudget = null,
      )
  }
}
