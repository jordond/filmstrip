package dev.jordond.filmstrip.effects.overlay

/**
 * Whether a [width] by [height] raster fits a texture at most [maxSide] pixels on a side.
 *
 * The side is the graphics API's `MAX_TEXTURE_SIZE`, the most a texture upload takes on a side, which the resolver
 * reads from its capabilities. Text and pictures past it are refused at resolve, before a texture is asked for.
 */
internal fun fitsTextureSide(
  width: Int,
  height: Int,
  maxSide: Int,
): Boolean = width <= maxSide && height <= maxSide

/**
 * The reason a [width] by [height] text raster is refused when [fitsTextureSide] says it is past [maxSide].
 *
 * @param graphicsApi The name of the graphics API the texture belongs to, such as "WebGL".
 */
internal fun textPastTextureSide(
  width: Int,
  height: Int,
  maxSide: Int,
  graphicsApi: String,
): String = "The $width by $height text is too long to draw. ${textureHolds(maxSide, graphicsApi)}"

/**
 * The reason a [width] by [height] overlay picture is refused when [fitsTextureSide] says it is past [maxSide].
 *
 * @param graphicsApi The name of the graphics API the texture belongs to, such as "WebGL".
 */
internal fun picturePastTextureSide(
  width: Int,
  height: Int,
  maxSide: Int,
  graphicsApi: String,
): String = "The $width by $height picture is too long to draw. ${textureHolds(maxSide, graphicsApi)}"

private fun textureHolds(
  maxSide: Int,
  graphicsApi: String,
): String = "A $graphicsApi texture on this device holds at most $maxSide pixels on a side."
