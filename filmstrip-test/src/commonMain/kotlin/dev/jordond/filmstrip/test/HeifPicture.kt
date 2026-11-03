package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * A HEIF still painted one flat color, either an [AvifPicture] or a [HeicPicture], as the color boxes it carries and
 * the samples its codestream decodes to.
 *
 * It carries no expected value.
 *
 * @property name What the description is, for test messages.
 * @property nclx The code points of a `colr nclx` box, or null for none.
 * @property profile The ICC profile of a `colr prof` box, or null for none.
 * @property ycbcr The luma, blue difference and red difference codes every sample decodes to.
 */
@InternalFilmstripApi
public sealed interface HeifPicture {
  public val name: String
  public val nclx: CicpTag?
  public val profile: ByteArray?
  public val ycbcr: List<Int>

  /**
   * The coded image the picture's item holds.
   */
  public fun codestream(): ByteArray

  /**
   * The picture as the bytes of a HEIF file, built by [HeifStills.still].
   *
   * @param thumbnail A thumbnail item the file carries ahead of the picture, or null for none.
   */
  public fun bytes(thumbnail: HeifThumbnail? = null): ByteArray

  /**
   * The picture as a two-by-two grid of its codestream, with [nclx] on the grid item, built by [HeifStills.grid].
   *
   * It leaves out [profile].
   *
   * @param thumbnail A thumbnail item the file carries ahead of the grid, or null for none.
   */
  public fun gridBytes(thumbnail: HeifThumbnail? = null): ByteArray

  /**
   * The picture's coded item, for another HEIF still to carry as its thumbnail.
   */
  public fun thumbnail(): HeifThumbnail
}
