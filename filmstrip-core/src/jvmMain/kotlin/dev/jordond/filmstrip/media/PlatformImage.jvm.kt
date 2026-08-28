package dev.jordond.filmstrip.media

import java.awt.image.BufferedImage

/**
 * The JVM form, wrapping a [BufferedImage].
 *
 * The image belongs to this object. Nothing else may hold it after [close]. There is no native
 * buffer to free here, so [close] only drops the reference.
 */
public actual class PlatformImage(
  private var image: BufferedImage?,
) : AutoCloseable {
  public actual val widthPx: Int
    get() = image?.width ?: 0

  public actual val heightPx: Int
    get() = image?.height ?: 0

  /**
   * The underlying image, still owned by this object.
   *
   * For a caller who wants to draw the frame rather than copy it.
   *
   * @return the image, or null once [close] has been called.
   */
  public fun asBufferedImage(): BufferedImage? = image

  public actual fun toRgba8888(): ByteArray {
    val source = image ?: return ByteArray(0)
    val width = source.width
    val height = source.height
    val argb = source.getRGB(0, 0, width, height, null, 0, width)
    val bytes = ByteArray(width * height * BYTES_PER_PIXEL)

    argb.forEachIndexed { index, pixel ->
      val offset = index * BYTES_PER_PIXEL
      bytes[offset] = (pixel shr RED_SHIFT).toByte()
      bytes[offset + 1] = (pixel shr GREEN_SHIFT).toByte()
      bytes[offset + 2] = pixel.toByte()
      bytes[offset + 3] = (pixel shr ALPHA_SHIFT).toByte()
    }

    return bytes
  }

  actual override fun close() {
    image = null
  }

  private companion object {
    const val BYTES_PER_PIXEL = 4
    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
  }
}
