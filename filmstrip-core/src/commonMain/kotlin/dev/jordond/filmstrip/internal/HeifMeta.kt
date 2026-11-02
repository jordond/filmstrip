package dev.jordond.filmstrip.internal

// Finds the boxes of an AVIF or HEIC's first meta box that its item readers and rewrites start from, so each of them
// reads the same pitm, ipco and ipma boxes.

/**
 * The boxes of a file's first `meta` box that an item reader or rewrite reads, the first of each kind and every `ipma`
 * of its first `iprp` up to [MAX_LISTED_BOXES].
 *
 * Only the first `iprp` is read, its first `ipco` and its own `ipma` boxes, the way libheif reads them and the way
 * [heifListing] lists an item's properties. A later `iprp` is passed over.
 *
 * @property meta The `meta` box itself.
 * @property pitm The `pitm` box, or null without one.
 * @property iinf The `iinf` box, or null without one.
 * @property ipco The first `ipco` of the first `iprp`, or null when that `iprp` holds none.
 * @property ipmas Every `ipma` the first `iprp` holds, in file order, the first [MAX_LISTED_BOXES] of them.
 * @property pastCap Whether the first `iprp` holds more `ipma` boxes than [ipmas] lists.
 * @property iloc The `iloc` box, or null without one.
 * @property idat The `idat` box, or null without one.
 * @property iref The `iref` box, or null without one.
 */
internal class MetaParts(
  private val file: ByteView,
  private val lenient: Boolean,
  val meta: Box,
  val pitm: Box?,
  val iinf: Box?,
  val ipco: Box?,
  val ipmas: List<Box>,
  val pastCap: Boolean,
  val iloc: Box?,
  val idat: Box?,
  val iref: Box?,
) {
  /**
   * The primary item id [pitm] names, read on first use, or null without a [pitm]. Reading it throws
   * [MalformedPicture] when [pitm] is too short to hold one.
   */
  val primary: Long? by lazy {
    pitm?.let { box -> file.within(box.end).let { it.itemId(box.start + 4, it.u8(box.start)) } }
  }

  /**
   * The item locations [iloc] lists, read on first use, or null without an [iloc]. Reading them throws
   * [MalformedPicture] when [iloc] does not parse.
   */
  val locations: ItemLocations? by lazy { iloc?.let { file.itemLocations(it) } }

  /**
   * The boxes [ipco] holds, read on first use, the first [MAX_PROPERTY_INDEX] of them, as many as an `ipma` entry can
   * index. Empty without an [ipco]. They are walked the way [metaParts] walked, so a lenient walk stops at the first
   * box that does not parse and a strict one throws [MalformedPicture] there.
   */
  val properties: List<Box> by lazy {
    val box = ipco ?: return@lazy emptyList()
    val view = file.within(box.end)
    val walk = if (lenient) view.parsing(box.start, box.end) else view.boxes(box.start, box.end)
    walk.take(MAX_PROPERTY_INDEX).toList()
  }
}

/**
 * The parts of the file's first `meta` box, or null when it has none. The first `ipco` of its first `iprp` is taken,
 * and every `ipma` that `iprp` holds.
 *
 * Throws [MalformedPicture] when a box it walks does not parse. With [lenient] set, each walk stops at the first box
 * that does not parse instead, and the parts are those found ahead of it.
 */
internal fun ByteView.metaParts(lenient: Boolean = false): MetaParts? {
  fun ByteView.walk(
    start: Int,
    end: Int,
  ): Sequence<Box> = if (lenient) parsing(start, end) else boxes(start, end)

  val meta = walk(0, end).firstOrNull { it.type == META } ?: return null
  val heif = within(meta.end)
  var pitm: Box? = null
  var iinf: Box? = null
  var ipco: Box? = null
  var iloc: Box? = null
  var idat: Box? = null
  var iref: Box? = null
  val ipmas = mutableListOf<Box>()
  var pastCap = false
  var firstIprp = true
  for (box in heif.walk(meta.start + 4, meta.end)) {
    when (box.type) {
      PITM -> {
        pitm = pitm ?: box
      }
      IINF -> {
        iinf = iinf ?: box
      }
      ILOC -> {
        iloc = iloc ?: box
      }
      IDAT -> {
        idat = idat ?: box
      }
      IREF -> {
        iref = iref ?: box
      }
      IPRP -> {
        if (!firstIprp) continue
        firstIprp = false
        for (child in heif.within(box.end).walk(box.start, box.end)) {
          when {
            child.type == IPCO -> ipco = ipco ?: child
            child.type != IPMA -> Unit
            ipmas.size < MAX_LISTED_BOXES -> ipmas += child
            else -> pastCap = true
          }
        }
      }
    }
  }
  return MetaParts(this, lenient, meta, pitm, iinf, ipco, ipmas, pastCap, iloc, idat, iref)
}

// The boxes from start up to end, stopping at the first that does not parse.
internal fun ByteView.parsing(
  start: Int,
  end: Int,
): Sequence<Box> =
  sequence {
    val walk = boxes(start, end).iterator()
    while (true) yield(deferring { if (walk.hasNext()) walk.next() else null } ?: break)
  }

/**
 * The most `ipma` boxes [metaParts] lists, and the most top-level boxes the `irot` and `imir` drop walks. Past either,
 * a file the drop would read is refused.
 */
internal const val MAX_LISTED_BOXES = 1024

/**
 * The highest one-based `ipco` index an `ipma` association can hold, its 15-bit wide form. Property readers take no
 * more `ipco` boxes than this.
 */
internal const val MAX_PROPERTY_INDEX = 0x7FFF

private val PITM = fourcc("pitm")
private val IINF = fourcc("iinf")
private val IREF = fourcc("iref")
private val IPMA = fourcc("ipma")
