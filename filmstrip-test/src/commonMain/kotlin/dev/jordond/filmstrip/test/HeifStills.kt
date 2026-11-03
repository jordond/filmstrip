package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * Writes the HEIF stills [ColorPictures] holds, AVIF and HEIC, around a codestream the caller already has.
 *
 * The file is `ftyp`, then `meta`, then `mdat` holding every item's data. `meta` names the primary item, which is
 * either one coded item or a `grid` of coded tiles, locates each item in `mdat` through `iloc`, and gives each an
 * `ispe` size, a three channel `pixi`, the caller's decoder configuration and any `colr` boxes. A thumbnail, when
 * there is one, is the first item in the file and points at the primary item through an `iref thmb`. An alpha plane,
 * when there is one, follows the primary item and its tiles, laid out the same way, and points at the primary item
 * through an `iref auxl`.
 */
@InternalFilmstripApi
public object HeifStills {
  /**
   * A HEIF file holding [codestream] as its primary item, as the bytes of the file.
   *
   * The item's properties are associated in the order `ispe`, `pixi`, [configuration], then a `colr prof` for [profile]
   * and a `colr nclx` for [nclx]. Only the configuration is marked essential.
   *
   * @param brand The four letter major brand, `avif` or `heic`, also listed as compatible beside `mif1` and `miaf`.
   * @param itemType The coded item's four letter type, `av01` or `hvc1`.
   * @param configuration The whole decoder configuration box, such as an `av1C` or `hvcC`.
   * @param codestream The coded image the item's data holds.
   * @param size The image's width and height.
   * @param nclx The code points of a `colr nclx` box, or null for none.
   * @param profile The ICC profile of a `colr prof` box, or null for none.
   * @param bitDepth The bits per channel `pixi` declares.
   * @param thumbnail A thumbnail item written ahead of the primary item, or null for none.
   * @param alpha An alpha item written after the primary item, or null for none.
   */
  public fun still(
    brand: String,
    itemType: String,
    configuration: ByteArray,
    codestream: ByteArray,
    size: Size,
    nclx: CicpTag? = null,
    profile: ByteArray? = null,
    bitDepth: Int = 8,
    thumbnail: HeifThumbnail? = null,
    alpha: HeifAlphaPlane? = null,
  ): ByteArray {
    require(itemType.encodeToByteArray().size == 4) { "An item type is four bytes, not '$itemType'" }
    val properties =
      listOfNotNull(
        fullBox("ispe", int32(size.width) + int32(size.height)),
        fullBox("pixi", byteArrayOf(CHANNELS.toByte()) + ByteArray(CHANNELS) { bitDepth.toByte() }),
        configuration,
        profile?.let { box("colr", "prof".encodeToByteArray() + it) },
        nclx?.let { box("colr", "nclx".encodeToByteArray() + nclxFields(it)) },
      )
    val associations =
      properties.indices.map { index ->
        val essential = if (index == CONFIGURATION_INDEX) ESSENTIAL else 0
        (essential or (index + 1)).toByte()
      }
    val primary = Item(FIRST_ID + if (thumbnail == null) 0 else 1, itemType, codestream, associations.toByteArray())
    if (alpha == null) return heif(brand, properties, listOf(primary), thumbnail)
    val first = properties.size + 1
    val alphaItem =
      Item(primary.id + 1, alpha.itemType, alpha.codestream, alpha.associations(first, first + 1, first + 2, first + 3))
    return heif(
      brand,
      properties + alpha.properties(alpha.size) + alpha.configuration + alpha.auxC(),
      listOf(primary, alphaItem),
      thumbnail,
      alpha.references(alphaItem.id, primary.id),
    )
  }

  /**
   * A HEIF file whose primary item is a `grid` of [columns] by [rows] copies of one coded tile, cropped to [size], as
   * the bytes of the file.
   *
   * The grid item comes first, after any [thumbnail], with an `ispe` of [size], a three channel `pixi` and a `colr
   * nclx` for [nclx]. Each tile is a hidden item of [itemType] with an `ispe` of [tileSize], the same `pixi` and the
   * essential [configuration], and an `iref` derives the grid from them in rows. Every tile holds its own copy of
   * [tile] in `mdat`.
   *
   * @param brand The four letter major brand, `avif` or `heic`, also listed as compatible beside `mif1` and `miaf`.
   * @param itemType The tiles' four letter type, `av01` or `hvc1`.
   * @param configuration The whole decoder configuration box every tile shares, such as an `av1C` or `hvcC`.
   * @param tile The coded image each tile's data holds.
   * @param tileSize Each tile's width and height.
   * @param columns How many tiles each row of the grid holds.
   * @param rows How many rows of tiles the grid holds.
   * @param size The width and height the assembled tiles are cropped to, all of them by default.
   * @param nclx The code points of the grid item's `colr nclx` box, or null for none.
   * @param bitDepth The bits per channel `pixi` declares.
   * @param thumbnail A thumbnail item written ahead of the grid item, or null for none.
   * @param alpha The alpha plane of each tile in rows, written as a grid of the same layout and size after the tiles,
   * or null for none. The configuration and premultiplication are the first plane's.
   */
  public fun grid(
    brand: String,
    itemType: String,
    configuration: ByteArray,
    tile: ByteArray,
    tileSize: Size,
    columns: Int = 2,
    rows: Int = 2,
    size: Size = Size(tileSize.width * columns, tileSize.height * rows),
    nclx: CicpTag? = null,
    bitDepth: Int = 8,
    thumbnail: HeifThumbnail? = null,
    alpha: List<HeifAlphaPlane>? = null,
  ): ByteArray {
    require(columns in 1..MAX_GRID_SIDE && rows in 1..MAX_GRID_SIDE) { "A grid is 1 to 256 tiles a side" }
    require(alpha == null || alpha.size == columns * rows) { "A grid takes one alpha plane a tile" }
    val width = size.width
    val height = size.height
    val gridData = byteArrayOf(0, 0, (rows - 1).toByte(), (columns - 1).toByte()) + uInt16(width) + uInt16(height)
    val properties =
      listOfNotNull(
        fullBox("ispe", int32(width) + int32(height)),
        fullBox("ispe", int32(tileSize.width) + int32(tileSize.height)),
        fullBox("pixi", byteArrayOf(CHANNELS.toByte()) + ByteArray(CHANNELS) { bitDepth.toByte() }),
        configuration,
        nclx?.let { box("colr", "nclx".encodeToByteArray() + nclxFields(it)) },
      )
    val gridId = FIRST_ID + if (thumbnail == null) 0 else 1
    val gridAssociations = listOfNotNull(1, 3, nclx?.let { 5 }).map { it.toByte() }.toByteArray()
    val tileAssociations = byteArrayOf(2, 3, (ESSENTIAL or 4).toByte())
    val tiles = List(columns * rows) { Item(gridId + 1 + it, itemType, tile, tileAssociations, hidden = true) }
    val dimg = box("dimg", uInt16(gridId) + uInt16(tiles.size) + tiles.joined { uInt16(it.id) })
    val items = listOf(Item(gridId, "grid", gridData, gridAssociations)) + tiles
    if (alpha == null) return heif(brand, properties, items, thumbnail, listOf(dimg))

    val first = properties.size + 1
    val plane = alpha.first()
    val alphaProperties = plane.properties(size) + plane.properties(tileSize) + plane.configuration + plane.auxC()
    val alphaGridId = gridId + tiles.size + 1
    val alphaGridAssociations = byteArrayOf(first.toByte(), (first + 1).toByte(), (first + 5).toByte())
    val alphaGrid = Item(alphaGridId, "grid", gridData, alphaGridAssociations)
    val alphaTileAssociations = plane.associations(first + 2, first + 3, first + 4)
    val alphaTiles =
      alpha.mapIndexed { at, it -> Item(alphaGridId + 1 + at, it.itemType, it.codestream, alphaTileAssociations, true) }
    val alphaDimg = box("dimg", uInt16(alphaGridId) + uInt16(alphaTiles.size) + alphaTiles.joined { uInt16(it.id) })
    return heif(
      brand,
      properties + alphaProperties,
      items + alphaGrid + alphaTiles,
      thumbnail,
      listOf(dimg, alphaDimg) + plane.references(alphaGridId, gridId),
    )
  }

  internal fun box(
    type: String,
    payload: ByteArray,
  ): ByteArray = int32(BOX_HEADER_BYTES + payload.size) + type.encodeToByteArray() + payload

  // A HEIF file of [items], the first of them its primary item, with [thumbnail] ahead of them as the item that takes
  // the first id. Each item's data sits in mdat in item order, and [derivations] join the thumbnail's thmb in iref.
  private fun heif(
    brand: String,
    primaryProperties: List<ByteArray>,
    items: List<Item>,
    thumbnail: HeifThumbnail?,
    derivations: List<ByteArray> = emptyList(),
  ): ByteArray {
    require(brand.encodeToByteArray().size == 4) { "A brand is four bytes, not '$brand'" }
    val primary = items.first()
    val first = primaryProperties.size + 1
    val thumbnailAssociations = byteArrayOf(first.toByte(), (first + 1).toByte(), (ESSENTIAL or (first + 2)).toByte())
    val thumbnailItem = thumbnail?.let { Item(FIRST_ID, it.itemType, it.codestream, thumbnailAssociations) }
    val thumbnailProperties =
      listOfNotNull(thumbnail).flatMap {
        listOf(
          fullBox("ispe", int32(it.size.width) + int32(it.size.height)),
          fullBox("pixi", byteArrayOf(CHANNELS.toByte()) + ByteArray(CHANNELS) { EIGHT_BITS }),
          it.configuration,
        )
      }
    val all = listOfNotNull(thumbnailItem) + items
    val thmb = thumbnailItem?.let { box("thmb", uInt16(it.id) + uInt16(1) + uInt16(primary.id)) }
    val references = listOfNotNull(thmb) + derivations
    val iref = if (references.isEmpty()) ByteArray(0) else fullBox("iref", references.joined { it })
    val ftyp = box("ftyp", brand.encodeToByteArray() + int32(0) + (brand + "mif1" + "miaf").encodeToByteArray())
    val ipma =
      fullBox(
        "ipma",
        int32(all.size) + all.joined { uInt16(it.id) + byteArrayOf(it.associations.size.toByte()) + it.associations },
      )
    val iprp = box("iprp", box("ipco", (primaryProperties + thumbnailProperties).joined { it }) + ipma)
    val iinf = fullBox("iinf", uInt16(all.size) + all.joined { infe(it.id, it.type, it.hidden) })

    fun meta(dataOffset: Int): ByteArray {
      val offsets = all.runningFold(dataOffset) { offset, item -> offset + item.data.size }
      val locations =
        all.withIndex().toList().joined { (index, item) ->
          uInt16(item.id) + uInt16(0) + uInt16(1) + int32(offsets[index]) + int32(item.data.size)
        }
      return fullBox(
        "meta",
        fullBox("hdlr", ByteArray(4) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0)) +
          fullBox("pitm", uInt16(primary.id)) +
          fullBox("iloc", byteArrayOf(OFFSET_AND_LENGTH_SIZES, 0) + uInt16(all.size) + locations) +
          iinf + iref + iprp,
      )
    }

    val dataOffset = ftyp.size + meta(0).size + BOX_HEADER_BYTES
    return ftyp + meta(dataOffset) + box("mdat", all.joined { it.data })
  }

  internal fun fullBox(
    type: String,
    payload: ByteArray,
    version: Int = 0,
    flags: Int = 0,
  ): ByteArray = box(type, byteArrayOf(version.toByte(), 0, 0, flags.toByte()) + payload)

  private fun infe(
    id: Int,
    itemType: String,
    hidden: Boolean,
  ): ByteArray =
    fullBox(
      "infe",
      uInt16(id) + uInt16(0) + itemType.encodeToByteArray() + byteArrayOf(0),
      version = 2,
      flags = if (hidden) 1 else 0,
    )

  private fun nclxFields(cicp: CicpTag): ByteArray =
    uInt16(cicp.primaries) + uInt16(cicp.transfer) + uInt16(cicp.matrix) +
      byteArrayOf(if (cicp.fullRange) FULL_RANGE_FLAG else 0)

  internal fun int32(value: Int): ByteArray =
    byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

  internal fun uInt16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

  // One-based ipco indices for the item's properties, the third of them, its configuration, marked essential.
  private fun HeifAlphaPlane.associations(vararg indices: Int): ByteArray =
    ByteArray(indices.size) { (if (it == 2) ESSENTIAL or indices[it] else indices[it]).toByte() }

  // The ispe of [size] and the one channel pixi the item carries.
  private fun HeifAlphaPlane.properties(size: Size): List<ByteArray> =
    listOf(
      fullBox("ispe", int32(size.width) + int32(size.height)),
      fullBox("pixi", byteArrayOf(1, bitDepth.toByte())),
    )

  private fun HeifAlphaPlane.auxC(): ByteArray {
    val type = if (itemType == "av01") AVIF_ALPHA else HEVC_ALPHA
    return fullBox("auxC", type.encodeToByteArray() + byteArrayOf(0))
  }

  // The auxl from [alpha] to [color], and the prem from [color] to [alpha] when the color is premultiplied.
  private fun HeifAlphaPlane.references(
    alpha: Int,
    color: Int,
  ): List<ByteArray> =
    listOfNotNull(
      box("auxl", uInt16(alpha) + uInt16(1) + uInt16(color)),
      box("prem", uInt16(color) + uInt16(1) + uInt16(alpha)).takeIf { premultiplied },
    )

  private fun <T> List<T>.joined(bytes: (T) -> ByteArray): ByteArray =
    fold(ByteArray(0)) { joined, element -> joined + bytes(element) }

  // One item of the file: its id, type and data, and the one-based ipco indices of its properties with the essential
  // ones flagged.
  private class Item(
    val id: Int,
    val type: String,
    val data: ByteArray,
    val associations: ByteArray,
    val hidden: Boolean = false,
  )

  private const val BOX_HEADER_BYTES = 8
  private const val AVIF_ALPHA = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"
  private const val HEVC_ALPHA = "urn:mpeg:hevc:2015:auxid:1"
  private const val FIRST_ID = 1
  private const val CHANNELS = 3
  internal const val ESSENTIAL = 0x80
  private const val CONFIGURATION_INDEX = 2
  private const val MAX_GRID_SIDE = 256
  private const val EIGHT_BITS: Byte = 8

  // Four byte extent offsets and lengths, and no base offset.
  private const val OFFSET_AND_LENGTH_SIZES: Byte = 0x44
  private const val FULL_RANGE_FLAG: Byte = 0x80.toByte()
}

/**
 * An eight-by-eight AVIF still painted one flat color, as the color boxes it carries and the samples its codestream
 * decodes to.
 *
 * It carries no expected value. The codestream is 4:2:0 unless [configuration] says otherwise and stores the same
 * luma and chroma at every sample, so its color depends on its matrix and range as well as its primaries and transfer.
 *
 * @property name What the description is, for test messages.
 * @property nclx The code points of a `colr nclx` box, or null for none.
 * @property profile The ICC profile of a `colr prof` box, or null for none.
 * @property ycbcr The luma, blue difference and red difference codes every sample decodes to.
 * @param fullCodestream The codestream, as hex, a picture at full range holds. It decodes to [ycbcr].
 * @param limitedCodestream The codestream, as hex, a picture at limited range holds. It decodes to [ycbcr].
 * @param ownCodestream The codestream, as hex, the picture holds whatever [nclx] says, or null to pick by range.
 * @param configuration The `av1C` payload, as hex, matching the codestream's profile and subsampling.
 * @property bitDepth The bits per channel the codestream stores, which `pixi` declares.
 */
@InternalFilmstripApi
public class AvifPicture internal constructor(
  override val name: String,
  override val nclx: CicpTag?,
  override val profile: ByteArray?,
  override val ycbcr: List<Int>,
  private val fullCodestream: String = FULL_CODESTREAM,
  private val limitedCodestream: String = LIMITED_CODESTREAM,
  private val ownCodestream: String? = null,
  private val configuration: String = AV1_CONFIGURATION,
  internal val bitDepth: Int = 8,
) : HeifPicture {
  /**
   * The AV1 codestream the picture's item holds, a sequence header and one frame.
   *
   * Unless the picture holds a codestream of its own, its sequence header carries no color description and its range
   * bit matches [nclx], full when there is no `nclx` box.
   */
  override fun codestream(): ByteArray =
    (ownCodestream ?: if (nclx?.fullRange == false) limitedCodestream else fullCodestream).hex()

  /**
   * The picture as the bytes of an AVIF file, built by [HeifStills.still].
   *
   * @param thumbnail A thumbnail item the file carries ahead of the picture, or null for none.
   */
  override fun bytes(thumbnail: HeifThumbnail?): ByteArray =
    HeifStills.still(
      brand = "avif",
      itemType = "av01",
      configuration = HeifStills.box("av1C", configuration.hex()),
      codestream = codestream(),
      size = AVIF_SIZE,
      nclx = nclx,
      profile = profile,
      bitDepth = bitDepth,
      thumbnail = thumbnail,
    )

  /**
   * The picture as a two-by-two grid of its codestream, with [nclx] on the grid item, built by [HeifStills.grid].
   *
   * It is sixteen pixels a side and leaves out [profile].
   *
   * @param thumbnail A thumbnail item the file carries ahead of the grid, or null for none.
   */
  override fun gridBytes(thumbnail: HeifThumbnail?): ByteArray =
    HeifStills.grid(
      brand = "avif",
      itemType = "av01",
      configuration = HeifStills.box("av1C", configuration.hex()),
      tile = codestream(),
      tileSize = AVIF_SIZE,
      nclx = nclx,
      bitDepth = bitDepth,
      thumbnail = thumbnail,
    )

  /**
   * The picture's coded item at eight pixels a side, for another HEIF still to carry as its thumbnail.
   */
  override fun thumbnail(): HeifThumbnail =
    HeifThumbnail("av01", HeifStills.box("av1C", configuration.hex()), codestream(), AVIF_SIZE)

  override fun toString(): String = name

  internal companion object {
    val AVIF_SIZE = Size(8, 8)

    // The codestreams and configuration come from ffmpeg 6.1.1 with libaom, on Ubuntu 24.04:
    //   ffmpeg -f lavfi -i "color=c=black:s=8x8,format=yuv420p,geq=lum=128:cb=96:cr=176" -frames:v 1 \
    //     -color_range pc -c:v libaom-av1 -aom-params lossless=1 -still-picture 1 -f avif flat.avif
    // with -color_range tv for the limited one. Each is the mdat payload of its file, and the configuration is the
    // file's av1C payload, Main profile 4:2:0 with no config OBUs. Decoding either back with ffmpeg gives 128, 96 and
    // 176 at every sample, and the two differ only in the sequence header's color range bit.
    const val FULL_CODESTREAM = "0a051808bf6c42320c10000002dd90816283ea9354"
    const val LIMITED_CODESTREAM = "0a051808bf6c02320c10000002dd90816283ea9354"
    const val AV1_CONFIGURATION = "81000c00"

    // The codestreams for pictures that carry their own come from libaom 3.15.1's aomenc on macOS, from the same
    // flat frame:
    //   aomenc --limit=1 --lossless=1 --obu --color-primaries=bt709 --transfer-characteristics=srgb \
    //     --matrix-coefficients=bt709 -o bt709.obu flat420.y4m
    //   aomenc --limit=1 --lossless=1 --obu --i444 --profile=1 -o identity.obu flat444.y4m
    // where flat420.y4m is an 8x8 C420jpeg frame and flat444.y4m an 8x8 C444 frame with XCOLORRANGE=FULL. Each is its
    // file without the leading temporal delimiter, and the 4:4:4 configuration is the av1C payload for profile 1 with
    // no subsampling. Decoding either back with ffmpeg 9.0.2 gives 128, 96 and 176 at every sample.
    const val BT709_LIMITED_CODESTREAM = "0a081808bfec80868082320c10000002dd90816283ea9354"
    const val IDENTITY_444_CODESTREAM = "0a053808bfec90320d100000062b9092e03662d8fc33"
    const val IDENTITY_444_CONFIGURATION = "81200000"
  }
}

/**
 * A sixty-four-by-sixty-four HEIC still painted one flat color, as the color boxes it carries, the color its
 * codestream signals and the samples it decodes to.
 *
 * It carries no expected value. The codestream is HEVC Main Still Picture, 4:2:0 and eight-bit, and stores the same
 * luma and chroma at every sample, so its color depends on [nclx]'s matrix and range as well as its primaries and
 * transfer.
 *
 * @property name What the description is, for test messages.
 * @property nclx The code points of a `colr nclx` box, or null for none.
 * @property profile The ICC profile of a `colr prof` box, or null for none.
 * @property ycbcr The luma, blue difference and red difference codes every sample decodes to.
 * @property vui What the sequence parameter set's video usability information signals about range and color.
 * @property slice The coded slice behind its four byte length, as hex. It decodes to [ycbcr] under the parameter sets
 * of every [HevcVui].
 */
@InternalFilmstripApi
public class HeicPicture internal constructor(
  override val name: String,
  override val nclx: CicpTag?,
  override val profile: ByteArray?,
  override val ycbcr: List<Int>,
  public val vui: HevcVui = HevcVui.Unsignalled,
  internal val slice: String = CODESTREAM,
) : HeifPicture {
  /**
   * The HEVC codestream the picture's item holds, one coded slice behind a four byte length.
   */
  override fun codestream(): ByteArray = slice.hex()

  /**
   * The `hvcC` box's payload, carrying the video, sequence and picture parameter sets, its sequence parameter set
   * signalling what [vui] names.
   */
  internal fun configuration(): ByteArray = vui.configuration.hex()

  /**
   * The picture as the bytes of a HEIC file, built by [HeifStills.still].
   *
   * @param thumbnail A thumbnail item the file carries ahead of the picture, or null for none.
   */
  override fun bytes(thumbnail: HeifThumbnail?): ByteArray =
    HeifStills.still(
      brand = "heic",
      itemType = "hvc1",
      configuration = HeifStills.box("hvcC", configuration()),
      codestream = codestream(),
      size = HEIC_SIZE,
      nclx = nclx,
      profile = profile,
      thumbnail = thumbnail,
    )

  /**
   * The picture as a two-by-two grid of its codestream, with [nclx] on the grid item, built by [HeifStills.grid].
   *
   * It is 128 pixels a side and leaves out [profile].
   *
   * @param thumbnail A thumbnail item the file carries ahead of the grid, or null for none.
   */
  override fun gridBytes(thumbnail: HeifThumbnail?): ByteArray =
    HeifStills.grid(
      brand = "heic",
      itemType = "hvc1",
      configuration = HeifStills.box("hvcC", configuration()),
      tile = codestream(),
      tileSize = HEIC_SIZE,
      nclx = nclx,
      thumbnail = thumbnail,
    )

  /**
   * The picture's coded item at sixty-four pixels a side, with the codestream signal [vui] names, for another HEIF
   * still to carry as its thumbnail.
   */
  override fun thumbnail(): HeifThumbnail =
    HeifThumbnail("hvc1", HeifStills.box("hvcC", configuration()), codestream(), HEIC_SIZE)

  override fun toString(): String = name

  internal companion object {
    val HEIC_SIZE = Size(64, 64)

    // The codestream and configurations come from x265 4.3 and ffmpeg 9.0.2 on macOS, from a raw frame of luma 128,
    // blue difference 96 and red difference 176 at every sample:
    //   python3 -c "open('flat.yuv','wb').write(bytes([128])*4096+bytes([96])*1024+bytes([176])*1024)"
    //   x265 --input flat.yuv --input-res 64x64 --fps 1 --frames 1 --profile mainstillpicture --qp 4 --no-info \
    //     -o flat.hevc
    //   ffmpeg -i flat.hevc -c copy -tag:v hvc1 flat.mp4
    // adding --range full for FullRange, --range full --colorprim bt709 --transfer iec61966-2-1 --colormatrix bt709
    // for FullRangeBt709, and --colorprim bt709 --transfer iec61966-2-1 --colormatrix bt709 for LimitedRangeBt709.
    // Each configuration is its mp4's hvcC payload. The codestream is the one coded slice of flat.hevc behind its
    // length, the same in all four. Decoding any of them back with ffmpeg gives 128, 96 and 176 at every sample.
    const val CODESTREAM = "00000015" + "2801af067861867ffff023ff21f9476f01d8453dbf"
  }
}

internal fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
