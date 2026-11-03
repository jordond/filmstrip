package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.HeifStills.ESSENTIAL
import dev.jordond.filmstrip.test.HeifStills.Item
import dev.jordond.filmstrip.test.HeifStills.box
import dev.jordond.filmstrip.test.HeifStills.fullBox
import dev.jordond.filmstrip.test.HeifStills.int32
import dev.jordond.filmstrip.test.HeifStills.joined
import dev.jordond.filmstrip.test.HeifStills.uInt16

/**
 * Writes HEIF stills whose primary item is an `iovl`, an image overlay, around a codestream the caller already has.
 *
 * The file is laid out the way [HeifStills] lays out a grid. The overlay item comes first, with an `ispe` of its
 * canvas, a three channel `pixi` and any `colr nclx`. Each input is a hidden item holding its own copy of one coded
 * image, with that image's `ispe`, the same `pixi` and its essential configuration, and an `iref dimg` derives the
 * overlay from them in order.
 */
@InternalFilmstripApi
public object HeifOverlays {
  /**
   * A HEIF file whose primary item draws one copy of [input] at each of [offsets] over a canvas of [canvas] painted
   * [fill], as the bytes of the file.
   *
   * @param brand The four letter major brand, `avif` or `heic`, also listed as compatible beside `mif1` and `miaf`.
   * @param input The coded image every input holds.
   * @param offsets Where each input's top left sits from the canvas's top left, in the order they are drawn.
   * @param canvas The canvas's width and height.
   * @param fill The canvas color as red, green, blue and alpha, each from 0 to 65535.
   * @param nclx The code points of the overlay item's `colr nclx` box, or null for none.
   * @param wide Whether the overlay's sizes and offsets are written in 32 bits rather than 16.
   */
  public fun overlay(
    brand: String,
    input: HeifThumbnail,
    offsets: List<Pair<Int, Int>>,
    canvas: Size,
    fill: List<Int> = OPAQUE_BLACK,
    nclx: CicpTag? = null,
    wide: Boolean = false,
  ): ByteArray {
    require(fill.size == 4) { "A canvas color is red, green, blue and alpha" }
    val field = { value: Int -> if (wide) int32(value) else uInt16(value) }
    val data =
      byteArrayOf(0, if (wide) 1 else 0) + fill.joined { uInt16(it) } + field(canvas.width) + field(canvas.height) +
        offsets.joined { (x, y) -> field(x) + field(y) }
    val pixi = fullBox("pixi", byteArrayOf(HeifStills.CHANNELS.toByte()) + ByteArray(HeifStills.CHANNELS) { 8 })
    val properties =
      listOfNotNull(
        fullBox("ispe", int32(canvas.width) + int32(canvas.height)),
        fullBox("ispe", int32(input.size.width) + int32(input.size.height)),
        pixi,
        input.configuration,
        nclx?.let { box("colr", "nclx".encodeToByteArray() + nclxFields(it)) },
      )
    val overlayAssociations = listOfNotNull(1, 3, nclx?.let { 5 }).map { it.toByte() }.toByteArray()
    val inputAssociations = byteArrayOf(2, 3, (ESSENTIAL or 4).toByte())
    val inputs =
      offsets.indices.map {
        Item(OVERLAY_ID + 1 + it, input.itemType, input.codestream, inputAssociations, true)
      }
    val dimg = box("dimg", uInt16(OVERLAY_ID) + uInt16(inputs.size) + inputs.joined { uInt16(it.id) })
    val items = listOf(Item(OVERLAY_ID, "iovl", data, overlayAssociations)) + inputs
    return HeifStills.heif(brand, properties, items, null, listOf(dimg))
  }

  private fun nclxFields(cicp: CicpTag): ByteArray =
    uInt16(cicp.primaries) + uInt16(cicp.transfer) + uInt16(cicp.matrix) +
      byteArrayOf(if (cicp.fullRange) FULL_RANGE_FLAG else 0)

  private const val OVERLAY_ID = 1
  private const val FULL_RANGE_FLAG: Byte = 0x80.toByte()
  private val OPAQUE_BLACK = listOf(0, 0, 0, 65535)
}
