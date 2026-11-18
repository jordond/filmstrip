package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.CompositeResult
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.animated.compositeFrames
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.OrientedAnimations
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The seams the browser's animated atlas shares with the other backends: the turn a tag asks for, the limits it names,
 * the one atlas a picture named twice is drawn from and the grade an overlay lands on.
 */
class WebAnimatedSeamsTest {
  @Test
  fun everyFrameOfATaggedApngIsInTheAtlasTurnedTheWayItsTagSays() =
    runTest {
      val canvas = OrientedAnimations.CANVAS
      val truth = AnimatedPictures.canvases(canvas, OrientedAnimations.OPS)
      for (exif in 1..8) {
        val orientation = PictureOrientation.ofExif(exif)
        val shown = orientation.orientedSize(canvas)
        val atlas = decodeOf(OrientedAnimations.apng(exif)).atlas

        withClue("exif $exif") {
          atlas.grid.cell shouldBe shown
          for ((index, frame) in truth.withIndex()) {
            val expected = orientedSamples(orientation, frame, canvas)
            val cell = atlas.grid.cells[index]
            for (y in 0 until shown.height) {
              for (x in 0 until shown.width) {
                val offset = ((cell.top + y) * atlas.grid.size.width + cell.left + x) * 4
                val painted = expected[y * shown.width + x]
                val read = (0 until 4).map { atlas.codes[offset + it].toInt() and 0xFF }
                val truthCodes = listOf(painted shr 16, painted shr 8, painted, painted ushr 24).map { it and 0xFF }
                if (truthCodes[3] == 0 && read[3] == 0) continue
                withClue("frame $index at $x,$y") { read shouldBe truthCodes }
              }
            }
          }
        }
      }
    }

  // The atlas holds eight bits a channel, which is the browser's limit and not the file's, so a sixteen bit APNG is
  // held as the code nearest each sample.
  @Test
  fun aSixteenBitSampleIsHeldAsTheNearestEightBitCode() {
    for (sample in 0..0xFFFF) {
      val expected = (sample + 128) / 257
      withClue("sample $sample") { (codeOf(sample.toShort()).toInt() and 0xFF) shouldBe expected }
    }
  }

  @Test
  fun aSixteenBitApngIsHeldAsTheEightBitCodesOfItsCompositedSamples() =
    runTest {
      val bytes = AnimatedApngs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED, sixteenBit = true)
      val picture = (animatedPictureOf(bytes) as AnimatedRead.Animated).picture
      val atlas = decodeOf(bytes).atlas
      var seen = 0

      compositeFrames(picture, { null }) { index, frame ->
        val cell = atlas.grid.cells[index]
        for (y in 0 until frame.height) {
          for (x in 0 until frame.width) {
            val offset = ((cell.top + y) * atlas.grid.size.width + cell.left + x) * 4
            for (channel in 0 until 4) {
              val sample = frame.rgba[(y * frame.width + x) * 4 + channel]
              atlas.codes[offset + channel] shouldBe codeOf(sample)
            }
          }
        }
        seen++
      } shouldBe CompositeResult.Done
      seen shouldBe picture.frames.size
    }

  @Test
  fun anOverlayAskedForBeforeTheFrameRateIsKnownIsRefusedWithTheSharedSentence() =
    runTest {
      val overlay = ImageOverlay(ImageSource.ofBytes(gif()), Corner.TopStart)

      val refused = resolve(overlay, attributes(frameRate = null)).shouldBeInstanceOf<EffectResolution.Unsupported>()

      refused.message shouldBe ANIMATED_NO_GRID
    }

  @Test
  fun anAtlasPastTheTextureSideAndASheetAreRefusedWithTheSentencesOfTheSharedRead() =
    runTest {
      val bytes = gif()
      val image = ImageSource.ofBytes(bytes)
      val drawn = animatedRead(bytes, AnimatedCopies.Browser).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
      val side = maxOf(drawn.grid.size.width, drawn.grid.size.height)

      resolve(ImageOverlay(image, Corner.TopStart), attributes(), capabilities(side - 1))
        .shouldBeInstanceOf<EffectResolution.Unsupported>()
        .message shouldBe drawn.refusalFor(sheet = false, maxSide = side - 1)
      resolve(ImageOverlay(image, Corner.TopStart), attributes(), capabilities(side))
        .shouldBeInstanceOf<EffectResolution.Resolved>()
      resolve(ImageOverlay(image, Corner.TopStart, sheet = SpriteSheet(2, 2, 100.milliseconds)), attributes())
        .shouldBeInstanceOf<EffectResolution.Unsupported>()
        .message shouldBe drawn.refusalFor(sheet = true, maxSide = null)
    }

  @Test
  fun twoOverlaysOfOnePictureAreDrawnFromOneAtlas() =
    runTest {
      val image = ImageSource.ofBytes(gif())
      val first = ImageOverlay(image, Corner.TopStart)
      val second = ImageOverlay(image, Corner.BottomEnd)
      val resolver = BuiltInEffectResolver()
      resolver.prepare(compositionOf(first, second))

      val a = overlayOf(resolver.resolve(first, capabilities(), attributes()))
      val b = overlayOf(resolver.resolve(second, capabilities(), attributes()))

      a.source.shouldBeInstanceOf<OverlaySource.Codes>().codes shouldBeSameInstanceAs
        b.source.shouldBeInstanceOf<OverlaySource.Codes>().codes
    }

  @Test
  fun anAnimatedPictureResolvesOnAKeptGradeFromTheSameAtlas() =
    runTest {
      val image = ImageSource.ofBytes(gif())
      val overlay = ImageOverlay(image, Corner.TopStart)
      val resolver = BuiltInEffectResolver()
      resolver.prepare(compositionOf(overlay))
      val atlas = decodeOf(gif()).atlas

      for (transfer in HdrTransfer.entries) {
        val drawn = overlayOf(resolver.resolve(overlay, capabilities(hdr = true), attributes(transfer = transfer)))

        withClue("$transfer") {
          drawn.size shouldBe atlas.grid.size
          drawn.source
            .shouldBeInstanceOf<OverlaySource.Codes>()
            .codes.size shouldBe atlas.codes.size
        }
      }
    }

  private suspend fun resolve(
    overlay: ImageOverlay,
    attributes: Attributes,
    capabilities: RenderCapabilities = capabilities(),
  ): EffectResolution {
    val resolver = BuiltInEffectResolver()
    resolver.prepare(compositionOf(overlay))
    return checkNotNull(resolver.resolve(overlay, capabilities, attributes))
  }

  private suspend fun decodeOf(bytes: ByteArray): OverlayDecode.Animated {
    val source = ImageSource.ofBytes(bytes)
    return OverlayImages(ceiling = CEILING)
      .apply { preload(source) }[source]
      .shouldNotBeNull()
      .shouldBeInstanceOf<OverlayDecode.Animated>()
  }

  private fun overlayOf(resolution: EffectResolution?): WebGlOverlay =
    resolution
      .shouldBeInstanceOf<EffectResolution.Resolved>()
      .effect.overlay
      .shouldNotBeNull()

  private fun compositionOf(vararg effects: EffectSpec): Composition =
    Composition(listOf(Track(listOf(Clip(MediaSource.ofUri("blob:clip"), effects = effects.toList())))))

  private fun gif(): ByteArray = AnimatedGifs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED, loops = 0)

  private fun capabilities(
    maxSide: Int = 4_096,
    hdr: Boolean = false,
  ) = RenderCapabilities(
    api = RenderApi.WebGl,
    supportsFragmentShader = true,
    supportsComputeShader = false,
    supportsHdr = hdr,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
    maxTextureSize = maxSide,
    features = emptySet(),
  )

  private fun attributes(
    frameRate: Float? = 30f,
    transfer: HdrTransfer? = null,
  ) = Attributes(
    inputSize = Size(1280, 720),
    outputSize = Size(1280, 720),
    layoutSize = Size(1280, 720),
    colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
    hdrTransfer = transfer,
    frameRate = frameRate,
    span = TimeRange.of(Duration.ZERO, 10.seconds),
  )

  private companion object {
    const val CEILING = 1L shl 30
  }
}
