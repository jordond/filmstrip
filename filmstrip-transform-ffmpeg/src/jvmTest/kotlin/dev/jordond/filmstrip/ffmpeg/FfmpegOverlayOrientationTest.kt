package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.orientedSamples
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.test.OrientedPictures
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Overlay pictures tagged with each of the eight orientations, drawn on the ffmpeg backend the way core reads the tag.
 *
 * ffmpeg opens every overlay picture with `-noautorotate`, so no build turns one by itself. The graph lays an untagged
 * picture it opens as stored out through nodes at the head of its branch, and a picture read in this process is laid
 * out through [orientedSamples]. Every expected texel comes from [orientedSamples] over the stored picture, read in the
 * middle of each quadrant so all eight orientations read differently.
 */
class FfmpegOverlayOrientationTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `the nodes an SDR graph opens a tagged picture through lay it out the way core does`() {
    if (!fixture.available()) return
    for (format in FORMATS) {
      val stored = rgbaOf(pictureOf(format, null), emptyList())
      for (orientation in PictureOrientation.entries) {
        val tagged = pictureOf(format, orientation)
        val shown = rgbaOf(tagged, orientationHead(tagged).map(::rendered))
        assertContentEquals(orientedSamples(orientation, stored, BIG), shown, "$format $orientation")
      }
    }
  }

  @Test
  fun `the decoder reads every tagged picture as stored whatever this ffmpeg reads tags from`() {
    if (!fixture.available()) return
    for (format in FORMATS) {
      val stored = decodedArgb(pictureOf(format, null))
      for (orientation in PictureOrientation.entries) {
        val tagged = pictureOf(format, orientation)
        assertContentEquals(stored, decodedArgb(tagged), "$format $orientation decoded turned")
        val measured = decoder.measure(ImageSource.ofBytes(tagged))
        assertEquals(BIG, assertIs<OverlayPictureRead.Read<Size>>(measured).value, "$format $orientation measure")
      }
    }
  }

  @Test
  fun `an SDR graph opens every tagged picture as it is and turns only a tagged one`() {
    if (!fixture.available()) return
    for (format in FORMATS) {
      assertEquals(emptyList(), orientationHead(pictureOf(format, null)), "$format untagged")
      assertEquals(emptyList(), orientationHead(pictureOf(format, PictureOrientation.Normal)), "$format tagged 1")
      for (orientation in PictureOrientation.entries - PictureOrientation.Normal) {
        assertTrue(orientationHead(pictureOf(format, orientation)).isNotEmpty(), "$format $orientation")
      }
    }
  }

  // The nodes ahead of the scale on the branch an SDR graph opens [picture] on, held to open the picture as it is.
  private fun orientationHead(picture: ByteArray): List<FilterNode> {
    val spec = ImageOverlay(ImageSource.ofBytes(picture), Corner.TopStart)
    val input =
      resolved(spec, sdrAttributes(), decoder)
        .effect.fragment.auxInputs
        .single()
    assertSame(spec.image, input.image, "the graph does not open the picture itself")
    return input.chain.takeWhile { !it.name.startsWith("scale") }
  }

  @Test
  fun `an HDR grade draws every orientation from the decoder and from the JDK`() {
    if (!fixture.available()) return
    for (reader in listOf<OverlayPictureDecoder?>(decoder, null)) {
      val stored = decodedArgb(pictureOf(Format.Png, null))
      for (orientation in PictureOrientation.entries) {
        val spec = ImageOverlay(ImageSource.ofBytes(pictureOf(Format.Png, orientation)), Corner.TopStart)
        val attributes = HdrRender.attributes(HdrTransfer.Pq, 2.seconds)
        val bytes =
          assertIs<ImageSource.Bytes>(
            resolved(spec, attributes, reader)
              .effect.fragment.auxInputs[0]
              .image,
          )
        val graded = ImageIO.read(ByteArrayInputStream(bytes.bytes))
        val shown = orientation.orientedSize(BIG)
        assertEquals(shown, Size(graded.width, graded.height), "$orientation through ${reader ?: "the JDK"}")
        val expected = orientedSamples(orientation, stored, BIG)
        for ((x, y) in quadrantMiddles(shown)) {
          val light = pictureLight(null, intArrayOf(expected[y * shown.width + x])).copyOf(3)
          val signal = HdrTransfer.Pq.signalFromNits(light).map { (it.coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt() }
          val read =
            graded.raster
              .getPixel(x, y, IntArray(4))
              .copyOf(3)
              .toList()
          assertEquals(signal, read, "$orientation at $x, $y through ${reader ?: "the JDK"}")
        }
      }
    }
  }

  @Test
  fun `an SDR export draws every orientation of a PNG and a JPEG the way core lays it out`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      for (format in FORMATS) {
        val stored = decodedArgb(pictureOf(format, null))
        for (batch in PictureOrientation.entries.chunked(CORNERS.size)) {
          val overlays = batch.zip(CORNERS) { it, corner -> cornerOverlay(pictureOf(format, it), corner) }
          val output = File.createTempFile("filmstrip-orientation", ".mp4").also { it.delete() }
          val composition =
            EditComposition(
              tracks = listOf(Track(listOf(Clip(MediaSource.of(fixture.landscape.absolutePath))))),
              effects = overlays,
            )
          val plan = fixture.plannedRun(composition, ExportSpec())
          fixture.exported(plan, output)
          val frame = plan.output.size
          val span = TimeRange.of(Duration.ZERO, plan.duration)
          batch.zip(overlays).forEach { (orientation, overlay) ->
            assertDrawn(stored, orientation, overlay, frame, span, "$format export") { x, y ->
              regionAverage(output.absolutePath, x - PROBE_SIDE / 2, y - PROBE_SIDE / 2, PROBE_SIDE, Duration.ZERO)
                .toList()
                .map { it.roundToInt() }
            }
          }
          output.delete()
        }
      }
    }

  @Test
  fun `a preview frame draws every orientation of a PNG the way core lays it out`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val stored = decodedArgb(pictureOf(Format.Png, null))
      for (batch in PictureOrientation.entries.chunked(CORNERS.size)) {
        val overlays = batch.zip(CORNERS) { it, corner -> cornerOverlay(pictureOf(Format.Png, it), corner) }
        val composition =
          EditComposition(
            tracks = listOf(Track(listOf(Clip(MediaSource.of(fixture.landscape.absolutePath))))),
            effects = overlays,
          )
        val components = ComponentRegistry.Builder().add(BuiltInEffectResolver()).build()
        val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))
        val opened = assertIs<PreviewStreamResult.Opened>(engine.openPreview(composition, ExportSpec()))
        val stream = assertIs<FfmpegPreviewStream>(opened.stream)
        val pixels =
          try {
            assertNotNull(stream.next(), "the preview delivered no frame")
          } finally {
            stream.close()
          }
        val size = stream.size
        batch.zip(overlays).forEach { (orientation, overlay) ->
          assertDrawn(stored, orientation, overlay, size, TimeRange.of(Duration.ZERO, 1.seconds), "preview") { x, y ->
            val at = (y * size.width + x) * RGBA
            List(3) { pixels[at + it].toInt() and 0xFF }
          }
        }
      }
    }

  // Reads the middle of each quadrant of [overlay] through [read] and holds it against [stored] laid out by
  // [orientation], within the overlay drift.
  private fun assertDrawn(
    stored: IntArray,
    orientation: PictureOrientation,
    overlay: ImageOverlay,
    frame: Size,
    span: TimeRange,
    what: String,
    read: (Int, Int) -> List<Int>,
  ) {
    val shown = orientation.orientedSize(BIG)
    val expected = pictureSrgbCodes(null, orientedSamples(orientation, stored, BIG))
    val rect = drawnRect(overlay, frame, span, Duration.ZERO, shown)
    for ((x, y) in quadrantMiddles(shown)) {
      val px = ((rect.left + rect.width * (x + 0.5f) / shown.width) * frame.width).roundToInt()
      val py = ((rect.top + rect.height * (y + 0.5f) / shown.height) * frame.height).roundToInt()
      val got = read(px, py)
      assertNear(Triple(got[0], got[1], got[2]), channelsOf(expected[y * shown.width + x]), "$what $orientation")
    }
  }

  // [picture] in [corner], small enough that a portrait one clears the overlay in the corner below it.
  private fun cornerOverlay(
    picture: ByteArray,
    corner: Corner,
  ): ImageOverlay = ImageOverlay(ImageSource.ofBytes(picture), corner, scale = CORNER_SCALE)

  private fun resolved(
    spec: ImageOverlay,
    attributes: Attributes,
    reader: OverlayPictureDecoder?,
  ): EffectResolution.Resolved =
    assertIs<EffectResolution.Resolved>(
      (reader?.let(::BuiltInEffectResolver) ?: BuiltInEffectResolver()).resolve(spec, CAPABILITIES, attributes),
    )

  private fun sdrAttributes(): Attributes =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = RATE,
      span = TimeRange.of(Duration.ZERO, 2.seconds),
    )

  private fun decodedArgb(bytes: ByteArray): IntArray {
    val read = assertIs<OverlayPictureRead.Read<*>>(decoder.decode(ImageSource.ofBytes(bytes)))
    val decoded = assertIs<DecodedPicture>(read.value)
    assertEquals(BIG, decoded.size)
    return assertIs<StoredSamples.Argb>(decoded.samples).argb
  }

  private enum class Format { Png, Jpeg }

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
    val FORMATS = Format.entries
    val CORNERS = listOf(Corner.TopStart, Corner.TopEnd, Corner.BottomStart, Corner.BottomEnd)
    val FRAME = Size(640, 360)
    const val RATE = 30f
    const val RGBA = 4
    const val CORNER_SCALE = 0.12f

    // OrientedPictures' picture with each texel drawn as a block of this side, so a scaled overlay keeps a flat
    // middle in every texel.
    const val BLOCK = 8
    val BIG = Size(OrientedPictures.SIZE.width * BLOCK, OrientedPictures.SIZE.height * BLOCK)

    private val pictures = mutableMapOf<Pair<Format, PictureOrientation?>, ByteArray>()

    // The picture as [format], tagged with [orientation] or untagged for null.
    fun pictureOf(
      format: Format,
      orientation: PictureOrientation?,
    ): ByteArray =
      pictures.getOrPut(format to orientation) {
        val image = BufferedImage(BIG.width, BIG.height, BufferedImage.TYPE_INT_RGB)
        val texels = OrientedPictures.samples()
        for (y in 0 until BIG.height) {
          for (x in 0 until BIG.width) image.setRGB(x, y, texels[y / BLOCK * OrientedPictures.SIZE.width + x / BLOCK])
        }
        val encoded =
          ByteArrayOutputStream()
            .also { assertTrue(ImageIO.write(image, if (format == Format.Png) "png" else "jpg", it)) }
            .toByteArray()
        when {
          orientation == null -> encoded
          format == Format.Png -> OrientedPictures.taggedPng(encoded, orientation.exif)
          else -> OrientedPictures.taggedJpeg(encoded, orientation.exif)
        }
      }

    // A node as ffmpeg's filter syntax spells it, unescaped, which every node here needs.
    fun rendered(node: FilterNode): String =
      listOf(node.name, node.arguments.joinToString(":") { "${it.key}=${it.value}" })
        .filter { it.isNotEmpty() }
        .joinToString("=")

    // The middle texel of each quadrant of a picture shown at [shown], whose block has a flat middle.
    fun quadrantMiddles(shown: Size): List<Pair<Int, Int>> {
      val xs = listOf(shown.width / 4, shown.width * 3 / 4)
      val ys = listOf(shown.height / 4, shown.height * 3 / 4)
      return ys.flatMap { y -> xs.map { x -> x to y } }
    }

    // [bytes] opened by ffmpeg with -noautorotate and run through [filters], as packed ARGB.
    fun rgbaOf(
      bytes: ByteArray,
      filters: List<String>,
    ): IntArray {
      val input = File.createTempFile("filmstrip-orientation", ".img").apply { writeBytes(bytes) }
      try {
        val graph = (filters + "format=rgba").joinToString(",")
        val command =
          listOf("ffmpeg", "-nostdin", "-loglevel", "error", "-noautorotate", "-i", input.absolutePath) +
            listOf("-frames:v", "1", "-vf", graph, "-f", "rawvideo", "-")
        val process = ProcessBuilder(command).start()
        val raw = process.inputStream.readAllBytes()
        assertEquals(0, process.waitFor(), process.errorStream.readAllBytes().decodeToString())
        return IntArray(raw.size / RGBA) { texel ->
          val at = texel * RGBA
          val channel = { offset: Int -> raw[at + offset].toInt() and 0xFF }
          (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
      } finally {
        input.delete()
      }
    }
  }
}
