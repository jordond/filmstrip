package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import kotlin.time.Duration

/**
 * What one lowering made of an animated picture: how it read, and the bitmaps it holds when it drew.
 *
 * @property read How the file read.
 * @property cells The bitmap of every frame, or null when the file did not draw.
 */
internal class SharedAnimation(
  val read: AnimatedFlipbookRead,
  val cells: AnimatedCellsRead?,
)

/**
 * The animated pictures one lowering made, one for each picture and grade, so overlays naming the same picture hold one
 * bitmap for every frame between them.
 */
internal typealias SharedAnimations = LoweringShare<Pair<HdrTransfer?, Boolean>, SharedAnimation>

/**
 * The animated picture this overlay names, from [animations] when another overlay of the lowering made it already.
 *
 * Every frame is composited once and held as a bitmap. A graph makes a texture for each frame it draws, from the same
 * bitmaps, so the pictures are never decoded again. A picture whose texture side is past [maxSide], or whose
 * composition has no frame rate, is refused before any frame is composited.
 */
internal fun ImageOverlay.sharedAnimation(
  animations: SharedAnimations,
  bytes: ByteArray,
  maxSide: Int,
  attributes: Attributes,
): SharedAnimation =
  animations.valueFor(image, attributes.hdrTransfer to (sheet != null)) {
    when (val read = animatedRead(bytes, AnimatedCopies.Media3, sheet = sheet != null)) {
      is AnimatedFlipbookRead.Drawn -> {
        val refusal =
          flipbookRefusal(attributes, ANIMATED_NO_GRID) ?: animatedTextureRefusal(read.picture.shown, maxSide)
        SharedAnimation(
          read,
          if (refusal != null) {
            AnimatedCellsRead.Refused(refusal)
          } else {
            read.cellsFor(attributes.hdrTransfer, Duration.ZERO, checkNotNull(attributes.gridRate))
          },
        )
      }
      else -> {
        SharedAnimation(read, null)
      }
    }
  }
