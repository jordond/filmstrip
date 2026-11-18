package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.picture.animated.AnimatedCopies

/**
 * What one lowering made of an animated picture: how it read, and the frames it holds when it drew.
 *
 * @property read How the file read.
 * @property cells The codes of every frame, or null when the file did not draw.
 */
internal class SharedAnimation(
  val read: AnimatedFlipbookRead,
  val cells: PictureRead<SheetPixels>?,
)

/**
 * The animated pictures one lowering made, one for each picture and for whether its overlay has a sprite sheet, so
 * overlays naming the same picture hold the codes of every frame once between them.
 */
internal typealias SharedAnimations = LoweringShare<Boolean, SharedAnimation>

/**
 * The animated picture this overlay names, from [animations] when another overlay of the lowering made it already.
 *
 * Every frame is composited once and held as the codes of its own cell. A composition with no frame rate is refused
 * before any frame is composited.
 */
internal fun ImageOverlay.sharedAnimation(
  animations: SharedAnimations,
  bytes: ByteArray,
  attributes: Attributes,
): SharedAnimation =
  animations.valueFor(image, sheet != null) {
    when (val read = animatedRead(bytes, AnimatedCopies.Apple, sheet = sheet != null)) {
      is AnimatedFlipbookRead.Drawn -> {
        val refusal = flipbookRefusal(attributes, ANIMATED_NO_GRID)
        SharedAnimation(read, if (refusal == null) read.framesAsPixels() else PictureRead.Unsupported(refusal))
      }
      else -> {
        SharedAnimation(read, null)
      }
    }
  }
