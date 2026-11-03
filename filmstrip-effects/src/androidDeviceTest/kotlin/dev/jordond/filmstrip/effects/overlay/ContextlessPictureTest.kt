package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a `content://` overlay picture comes to when there is no Context to open its provider through.
 *
 * It is refused with [FilmstripContext.MISSING_CONTEXT], the message the other Android readers report for a missing
 * Context, rather than as a picture whose bytes could not be read. A `file` uri needs no Context and is unaffected.
 */
class ContextlessPictureTest {
  @Test
  fun aContentUriReadWithoutAContextIsRefusedForTheMissingContext() {
    val source = ImageSource.Uri(CONTENT_URI)
    val overlay = ImageOverlay(source, Corner.TopStart, 0f, OVERLAY_SCALE)

    val read = source.read(context = null)
    val resolved = with(BuiltInEffectResolver()) { overlay.toOverlay(read, PREVIEW, MAX_SIDE) }

    read.shouldBeInstanceOf<ContextlessPicture>()
    resolved.shouldBeInstanceOf<EffectResolution.Unsupported>().message shouldBe FilmstripContext.MISSING_CONTEXT
  }

  @Test
  fun aMissingFileUriIsUnreadableRatherThanContextless() {
    ImageSource.Uri("file:///nowhere/overlay.png").read(context = null).shouldBeInstanceOf<UnreadablePicture>()
  }

  private companion object {
    const val CONTENT_URI = "content://dev.jordond.filmstrip.test/overlay.png"
    const val OVERLAY_SCALE = 0.2f
    const val MAX_SIDE = 4_096
    val FRAME = Size(1280, 720)
    val PREVIEW =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )
  }
}
