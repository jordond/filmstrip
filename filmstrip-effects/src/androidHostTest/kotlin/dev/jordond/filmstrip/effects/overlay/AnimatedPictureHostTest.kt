package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPeakBytes
import dev.jordond.filmstrip.picture.animated.animatedPeakRefusal
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.animated.animatedSourceBytes
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.OrientedAnimations
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What media3 refuses of an animated picture and what it counts for one, before any frame is decoded.
 *
 * media3 holds a bitmap for every frame, so the count is made with the row [AnimatedCopies.Media3] names, and each
 * frame is uploaded whole, so the texture side is measured on the canvas.
 */
class AnimatedPictureHostTest {
  private val overlay = ImageOverlay(ImageSource.Path("/fixtures/a.gif"), Corner.TopStart)
  private val bytes = AnimatedGifs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED)
  private val picture = (animatedPictureOf(bytes) as AnimatedRead.Animated).picture

  @Test
  fun aSheetOnAnAnimatedFileIsRefusedByName() {
    val sheeted = ImageOverlay(overlay.image, Corner.TopStart, sheet = SpriteSheet(2, 2, 100.milliseconds))

    animatedRead(bytes, AnimatedCopies.Media3, sheet = sheeted.sheet != null)
      .shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldBe SHEET_ON_ANIMATED
  }

  @Test
  fun anAnimatedAvifIsRefusedWithTheSentenceCoreGives() {
    val avis = byteArrayOf(0, 0, 0, 16) + "ftypavis".encodeToByteArray() + ByteArray(4)

    animatedRead(
      avis,
      AnimatedCopies.Media3,
    ).shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldBe
      ANIMATED_AVIF_REFUSED
  }

  @Test
  fun aStillPictureTakesTheRouteOfAStill() {
    animatedRead(
      AnimatedGifs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED.take(1)),
      AnimatedCopies.Media3,
    ) shouldBe
      AnimatedFlipbookRead.Still
  }

  @Test
  fun everyFrameIsABitmapOfFourBytesATexel() {
    AnimatedCopies.Media3.frameBytes shouldBe 4
    AnimatedCopies.Media3.cellBytes shouldBe 0
  }

  @Test
  fun theCountIsTheFramesAndTheTwoCanvasesOfTheWalk() {
    val texels = picture.canvas.width.toLong() * picture.canvas.height
    val source = checkNotNull(animatedSourceBytes(picture))

    val peak =
      checkNotNull(animatedPeakBytes(picture.canvas, picture.frames.size, AnimatedCopies.Media3, source = source))

    peak shouldBe texels * picture.frames.size * 4 + texels * 2 * 8 + source
  }

  @Test
  fun aPictureFitsTheCeilingAtItsEdgeAndNotOneByteOver() {
    val source = checkNotNull(animatedSourceBytes(picture, bytes.size.toLong()))
    val peak =
      checkNotNull(animatedPeakBytes(picture.canvas, picture.frames.size, AnimatedCopies.Media3, source = source))

    animatedRead(bytes, AnimatedCopies.Media3, ceiling = peak).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
    animatedRead(
      bytes,
      AnimatedCopies.Media3,
      ceiling = peak - 1,
    ).shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldBe
      animatedPeakRefusal(picture, AnimatedCopies.Media3, peak - 1, bytes.size.toLong())
  }

  @Test
  fun everyFrameHasACellOfTheCanvasSize() {
    val drawn = animatedRead(bytes, AnimatedCopies.Media3).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()

    drawn.grid.cells.size shouldBe picture.frames.size
    drawn.grid.cells
      .toSet()
      .size shouldBe picture.frames.size
    drawn.grid.cell shouldBe picture.canvas
  }

  // The canvas is wider than it is tall, so the width is the longest side, and a side between the two ends is checked
  // as well as the ends.
  @Test
  fun theTextureSideIsMeasuredOnTheCanvas() {
    val canvas = picture.canvas
    val side = maxOf(canvas.width, canvas.height)

    animatedTextureRefusal(canvas, side).shouldBeNull()
    animatedTextureRefusal(canvas, side + (MAX_SIDE - side) / 2).shouldBeNull()
    animatedTextureRefusal(canvas, MAX_SIDE).shouldBeNull()
    withClue("one texel short of the canvas") {
      animatedTextureRefusal(canvas, side - 1) shouldBe
        picturePastTextureSide(canvas.width, canvas.height, side - 1, OPEN_GL_ES)
    }
    animatedTextureRefusal(Size(canvas.height, canvas.width), side - 1) shouldBe
      picturePastTextureSide(canvas.height, canvas.width, side - 1, OPEN_GL_ES)
  }

  @Test
  fun aCompositionWithNoFrameRateIsRefusedWithTheSharedSentence() {
    val spec = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)

    val resolution = BuiltInEffectResolver().resolve(spec, CAPABILITIES, attributes(frameRate = null))

    resolution.shouldBeInstanceOf<EffectResolution.Unsupported>().message shouldBe ANIMATED_NO_GRID
  }

  @Test
  fun aTurnedPictureIsMeasuredAgainstTheTextureSideAtItsShownSize() {
    val turned = (animatedPictureOf(OrientedAnimations.apng(6)) as AnimatedRead.Animated).picture
    val drawn =
      animatedRead(OrientedAnimations.apng(6), AnimatedCopies.Media3).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()

    drawn.picture.shown shouldBe Size(turned.canvas.height, turned.canvas.width)
    drawn.grid.cell shouldBe drawn.picture.shown
    val shown = drawn.picture.shown
    val side = maxOf(shown.width, shown.height)
    animatedTextureRefusal(shown, side).shouldBeNull()
    animatedTextureRefusal(shown, side - 1) shouldBe
      picturePastTextureSide(shown.width, shown.height, side - 1, OPEN_GL_ES)
  }

  @Test
  fun twoOverlaysOfOnePictureAreMadeOncePerLowering() {
    val image = ImageSource.ofBytes(bytes)
    val first = ImageOverlay(image, Corner.TopStart)
    val second = ImageOverlay(image, Corner.BottomEnd)
    val animations = SharedAnimations()
    animations.readFor(
      Composition(tracks = listOf(Track(emptyList())), effects = listOf(first, second)),
    )
    val unset = attributes(frameRate = null)

    val a = first.sharedAnimation(animations, bytes, MAX_SIDE, unset)
    val b = second.sharedAnimation(animations, bytes, MAX_SIDE, unset)

    b shouldBeSameInstanceAs a
    a.cells.shouldBeInstanceOf<AnimatedCellsRead.Refused>().message shouldBe ANIMATED_NO_GRID
  }

  private fun attributes(frameRate: Float?): Attributes =
    Attributes(
      inputSize = Size(1280, 720),
      outputSize = Size(1280, 720),
      layoutSize = Size(1280, 720),
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = frameRate,
      span = TimeRange.of(Duration.ZERO, 3.seconds),
    )

  private companion object {
    const val MAX_SIDE = 4096
    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.OpenGlEs,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
        maxTextureSize = MAX_SIDE,
        features = emptySet(),
      )
  }
}
