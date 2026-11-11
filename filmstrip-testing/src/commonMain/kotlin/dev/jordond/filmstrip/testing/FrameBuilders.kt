package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.PlatformImage
import kotlin.time.Duration

/**
 * Makes a rendered frame holding an opaque black picture, for scripting what [FakeFilmstrip] renders.
 *
 * @param at Where in the composition the frame landed.
 * @param widthPx The picture's width, in pixels.
 * @param heightPx The picture's height, in pixels.
 * @param colorSpace The color space the picture is in.
 * @return The frame, which the caller owns and must close.
 */
public fun testFrame(
  at: Duration = Duration.ZERO,
  widthPx: Int = 16,
  heightPx: Int = 9,
  colorSpace: ColorSpace = ColorSpace.Bt709,
): FrameResult.Success = FrameResult.Success(testPlatformImage(widthPx, heightPx), at, colorSpace)

/**
 * Makes an opaque black [PlatformImage] of the size asked for.
 *
 * On Android the picture is a real `Bitmap`, so a host test needs a runtime that has one, such as Robolectric. The
 * stubbed framework a plain host test runs against cannot make it.
 *
 * @param widthPx The picture's width, in pixels.
 * @param heightPx The picture's height, in pixels.
 * @return The image, which the caller owns and must close.
 */
public expect fun testPlatformImage(
  widthPx: Int = 16,
  heightPx: Int = 9,
): PlatformImage
