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
 * overlay from them in order. A nested input is a hidden `grid` item of one tile instead, its tile written after every
 * input.
 */
@InternalFilmstripApi
public object HeifOverlays {
  /**
   * A HEIF file whose primary item draws one coded image at each of [offsets] over a canvas of [canvas] painted
   * [fill], as the bytes of the file.
   *
   * @param brand The four letter major brand, `avif` or `heic`, also listed as compatible beside `mif1` and `miaf`.
   * @param input The coded image every input holds unless [images] says otherwise.
   * @param offsets Where each input's top left sits from the canvas's top left, in the order they are drawn.
   * @param canvas The canvas's width and height.
   * @param fill The canvas color as red, green, blue and alpha, each from 0 to 65535.
   * @param nclx The code points of the overlay item's `colr nclx` box, or null for none.
   * @param wide Whether the overlay's sizes and offsets are written in 32 bits rather than 16.
   * @param images The coded image each input holds, one for each of [offsets], [input] for all of them by default.
   * @param nested The indices of the inputs written as a `grid` item of one tile, the tile holding the input's image.
   * @param alpha An alpha plane drawn the same way, an `iovl` alpha item with one copy of the plane at each of
   * [alphaOffsets] over the same canvas painted [alphaFill], written after every other item, or null for none.
   * @param alphaFill The alpha item's canvas color as red, green, blue and alpha, each from 0 to 65535.
   * @param alphaOffsets Where each copy of the alpha plane sits, [offsets] by default.
   */
  public fun overlay(
    brand: String,
    input: HeifThumbnail,
    offsets: List<Pair<Int, Int>>,
    canvas: Size,
    fill: List<Int> = OPAQUE_BLACK,
    nclx: CicpTag? = null,
    wide: Boolean = false,
    images: List<HeifThumbnail> = offsets.map { input },
    nested: Set<Int> = emptySet(),
    alpha: HeifAlphaPlane? = null,
    alphaFill: List<Int> = fill,
    alphaOffsets: List<Pair<Int, Int>> = offsets,
  ): ByteArray {
    require(fill.size == 4 && alphaFill.size == 4) { "A canvas color is red, green, blue and alpha" }
    require(images.size == offsets.size) { "Every input holds one image" }
    val field = { value: Int -> if (wide) int32(value) else uInt16(value) }
    val layout = { color: List<Int>, at: List<Pair<Int, Int>> ->
      byteArrayOf(0, if (wide) 1 else 0) + color.joined { uInt16(it) } + field(canvas.width) + field(canvas.height) +
        at.joined { (x, y) -> field(x) + field(y) }
    }
    val pixi = fullBox("pixi", byteArrayOf(HeifStills.CHANNELS.toByte()) + ByteArray(HeifStills.CHANNELS) { 8 })
    val shared =
      listOfNotNull(
        fullBox("ispe", int32(canvas.width) + int32(canvas.height)),
        pixi,
        nclx?.let { box("colr", "nclx".encodeToByteArray() + nclxFields(it)) },
      )
    val distinct = images.distinct()
    val properties =
      shared +
        distinct.flatMap { listOf(fullBox("ispe", int32(it.size.width) + int32(it.size.height)), it.configuration) }
    val ispeOf = { image: HeifThumbnail -> shared.size + 1 + 2 * distinct.indexOf(image) }
    val coded = { image: HeifThumbnail ->
      byteArrayOf(ispeOf(image).toByte(), PIXI_INDEX, (ESSENTIAL or (ispeOf(image) + 1)).toByte())
    }
    val overlayAssociations = listOfNotNull(1, 2, nclx?.let { 3 }).map { it.toByte() }.toByteArray()
    val inputs =
      images.mapIndexed { at, image ->
        val id = OVERLAY_ID + 1 + at
        if (at in nested) {
          val size = uInt16(image.size.width) + uInt16(image.size.height)
          Item(id, "grid", byteArrayOf(0, 0, 0, 0) + size, byteArrayOf(ispeOf(image).toByte(), PIXI_INDEX), true)
        } else {
          Item(id, image.itemType, image.codestream, coded(image), true)
        }
      }
    val tiles =
      nested.sorted().mapIndexed { at, index ->
        val image = images[index]
        Item(OVERLAY_ID + 1 + images.size + at, image.itemType, image.codestream, coded(image), true)
      }
    val tileDimgs =
      nested.sorted().zip(tiles) { index, tile -> box("dimg", uInt16(inputs[index].id) + uInt16(1) + uInt16(tile.id)) }
    val dimg = box("dimg", uInt16(OVERLAY_ID) + uInt16(inputs.size) + inputs.joined { uInt16(it.id) })
    val items = listOf(Item(OVERLAY_ID, "iovl", layout(fill, offsets), overlayAssociations)) + inputs + tiles
    val derivations = listOf(dimg) + tileDimgs
    if (alpha == null) return HeifStills.heif(brand, properties, items, null, derivations)

    val first = properties.size + 1
    val alphaProperties = with(HeifStills) { alpha.properties(alpha.size) + alpha.configuration + alpha.auxC() }
    val alphaId = OVERLAY_ID + items.size
    val alphaItem =
      Item(
        alphaId,
        "iovl",
        layout(alphaFill, alphaOffsets),
        byteArrayOf(
          1,
          (first + 1).toByte(),
          (
            first +
              3
          ).toByte(),
        ),
      )
    val planeAssociations = byteArrayOf(first.toByte(), (first + 1).toByte(), (ESSENTIAL or (first + 2)).toByte())
    val planes =
      alphaOffsets.indices.map {
        Item(
          alphaId + 1 + it,
          alpha.itemType,
          alpha.codestream,
          planeAssociations,
          true,
        )
      }
    val alphaDimg = box("dimg", uInt16(alphaId) + uInt16(planes.size) + planes.joined { uInt16(it.id) })
    return HeifStills.heif(
      brand,
      properties + alphaProperties,
      items + alphaItem + planes,
      null,
      derivations + alphaDimg + with(HeifStills) { alpha.references(alphaId, OVERLAY_ID) },
    )
  }

  private fun nclxFields(cicp: CicpTag): ByteArray =
    uInt16(cicp.primaries) + uInt16(cicp.transfer) + uInt16(cicp.matrix) +
      byteArrayOf(if (cicp.fullRange) FULL_RANGE_FLAG else 0)

  private const val OVERLAY_ID = 1
  private const val PIXI_INDEX: Byte = 2
  private const val FULL_RANGE_FLAG: Byte = 0x80.toByte()
  private val OPAQUE_BLACK = listOf(0, 0, 0, 65535)
}
