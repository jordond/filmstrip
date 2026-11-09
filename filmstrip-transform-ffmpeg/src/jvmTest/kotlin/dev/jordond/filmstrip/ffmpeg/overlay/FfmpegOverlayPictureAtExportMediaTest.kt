package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.MINIMUM_LIBAVFORMAT_MAJOR
import dev.jordond.filmstrip.ffmpeg.internal.process.parseVersion
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_COLOR
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.assertReads
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.isNear
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.ffmpeg.support.writtenFrames
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * An overlay picture named by path is read when the export lowers, and that read is what the export draws.
 *
 * The engine reports the command it is about to spawn after lowering and before ffmpeg opens anything, so a listener on
 * that event changes the file in exactly the window a graph opening the caller's path would read it in.
 */
class FfmpegOverlayPictureAtExportMediaTest {
  private val fixture = FfmpegExportFixture()
  private val root: File = createTempDirectory("filmstrip-picture-at-export").toFile()
  private val picture = File(root, "logo.png")
  private val overlay = ImageOverlay(ImageSource.of(picture.absolutePath), Corner.TopStart)

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `draws the picture as it is when the export starts rather than when it was planned`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      picture.writeBytes(pngOf(OVERLAY_COLOR, OVERLAY_SIZE))
      val engine = engineWith {}
      val plan = plannedOn(engine)

      picture.writeBytes(pngOf(SWAPPED_COLOR, SWAPPED_SIZE))

      val output = exported(engine, plan)
      val size = plan.output.size
      val probe = centreOf(overlay.placedOn(size, SWAPPED_SIZE).rectOn(size), size)
      assertReads(writtenFrames(output.path, probe = probe), channelsOf(SWAPPED_COLOR), "the picture at export start")
    }

  @Test
  fun `draws the bytes it lowered when the picture is replaced before ffmpeg opens it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      picture.writeBytes(pngOf(OVERLAY_COLOR, OVERLAY_SIZE))
      val engine = engineWith { picture.writeBytes(pngOf(SWAPPED_COLOR, SWAPPED_SIZE)) }
      val plan = plannedOn(engine)

      val output = exported(engine, plan)
      val size = plan.output.size
      val probe = centreOf(overlay.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
      assertReads(writtenFrames(output.path, probe = probe), channelsOf(OVERLAY_COLOR), "the picture it lowered")
    }

  @Test
  fun `draws the bytes it lowered when the picture is deleted before ffmpeg opens it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      picture.writeBytes(pngOf(OVERLAY_COLOR, OVERLAY_SIZE))
      val engine = engineWith { picture.delete() }
      val plan = plannedOn(engine)

      val output = exported(engine, plan)
      val size = plan.output.size
      val probe = centreOf(overlay.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
      withClue("the listener never ran") { picture.exists() shouldBe false }
      assertReads(writtenFrames(output.path, probe = probe), channelsOf(OVERLAY_COLOR), "the picture it lowered")
    }

  // A TGA has no signature, so ffmpeg names its decoder from the file's extension, and bytes written under the scratch
  // file's name would never decode. The render would spin rather than fail.
  @Test
  fun `draws a picture with no signature from its decode and survives its deletion`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val tga = File(root, "logo.tga").apply { writeBytes(tgaOf(OVERLAY_COLOR, OVERLAY_SIZE)) }
      val badge = ImageOverlay(ImageSource.of(tga.absolutePath), Corner.TopStart)
      val engine = engineWith { tga.delete() }
      val plan = plannedOn(engine, badge)

      val output = exported(engine, plan)
      val size = plan.output.size
      val probe = centreOf(badge.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
      assertReads(writtenFrames(output.path, probe = probe), channelsOf(OVERLAY_COLOR), "the TGA it decoded")
    }

  @Test
  fun `a picture deleted before the export starts named by a file uri is refused naming it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      picture.writeBytes(pngOf(OVERLAY_COLOR, OVERLAY_SIZE))
      val uri = ImageSource.ofUri(picture.toURI().toString())
      val engine = engineWith {}
      val plan = plannedOn(engine, ImageOverlay(uri, Corner.TopStart))

      picture.delete()

      val failure = engine.export(plan, MediaSink.of(File(root, "out.mp4").path)).toList().last()
      failure.shouldBeInstanceOf<ExportStatus.Failure>().error.message shouldContain picture.name
    }

  // The graph opens these from a scratch file named for a PNG, so each must be found from its own bytes.
  @Test
  fun `every format ffmpeg probes by content draws from a scratch file named for a PNG`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val png = File(root, "source.png").apply { writeBytes(pngOf(OVERLAY_COLOR, OVERLAY_SIZE)) }
      for (extension in listOf("jpg", "webp", "tif", "bmp", "ppm", "qoi")) {
        // Not every build writes every format, the Homebrew one has no WebP encoder.
        val file = encodedAs(png, extension) ?: continue
        val badge = ImageOverlay(ImageSource.ofBytes(file.readBytes()), Corner.TopStart)
        val engine = engineWith {}
        val plan = plannedOn(engine, badge)

        val output = exported(engine, plan)
        val size = plan.output.size
        val probe = centreOf(badge.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
        withClue(extension) {
          assertReads(writtenFrames(output.path, probe = probe), channelsOf(OVERLAY_COLOR), extension, LOSSY_TOLERANCE)
        }
      }
    }

  // Each of these sits on the content probe list, so its bytes reach the graph under the scratch file's PNG name.
  @Test
  fun `a JPEG 2000 on the content probe list draws through the real path`() = drawsThroughTheRealPath("jp2")

  @Test
  fun `an OpenEXR on the content probe list draws through the real path`() = drawsThroughTheRealPath("exr")

  @Test
  fun `a Radiance HDR on the content probe list draws through the real path`() = drawsThroughTheRealPath("hdr")

  @Test
  fun `a DPX on the content probe list draws through the real path`() = drawsThroughTheRealPath("dpx")

  @Test
  fun `an SGI on the content probe list draws through the real path`() = drawsThroughTheRealPath("sgi")

  @Test
  fun `a Sun raster on the content probe list draws through the real path`() = drawsThroughTheRealPath("ras")

  // An XBM holds one bit a texel, so its picture is white, which it stores.
  @Test
  fun `an XBM on the content probe list draws through the real path`() = drawsThroughTheRealPath("xbm", WHITE)

  private fun drawsThroughTheRealPath(
    extension: String,
    color: Int = OVERLAY_COLOR,
  ) = runTest(timeout = TIMEOUT) {
    if (!fixture.available()) return@runTest
    val png = File(root, "source.png").apply { writeBytes(pngOf(color, OVERLAY_SIZE)) }
    val file = checkNotNull(encodedAs(png, extension)) { "this ffmpeg writes no $extension" }
    val badge = ImageOverlay(ImageSource.ofBytes(file.readBytes()), Corner.TopStart)
    val engine = engineWith {}
    val plan = plannedOn(engine, badge)

    val output = exported(engine, plan)
    val size = plan.output.size
    val probe = centreOf(badge.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
    assertReads(writtenFrames(output.path, probe = probe), channelsOf(color), extension, LOSSY_TOLERANCE)
  }

  // Each sample sits nearly halfway between two eight-bit codes, where an eight-bit read of it lands on another code
  // than ffmpeg's own sixteen-bit conversion does.
  @Test
  fun `a sixteen bit PAM reads where a sixteen bit PNG of the same samples does`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      for (code in listOf(40, 127, 200)) {
        val sample = code * SIXTEEN_BIT_STEP + SIXTEEN_BIT_STEP / 2
        val texel = listOf(sample, sample, sample, SIXTEEN_BIT_MAX)
        val pam = File(root, "deep.pam").apply { writeBytes(pamOf(OVERLAY_SIZE, texel)) }
        val png = File(root, "deep.png").apply { writeBytes(OverlayPixels.sixteenBitPngOf(OVERLAY_SIZE, texel)) }

        withClue("sample $sample") { firstFrameOf(pam) shouldBe firstFrameOf(png) }
      }
    }

  // ffmpeg 9.x's OpenEXR encoder takes premultiplied alpha, so the alpha mode negotiation premultiplies the PNG's codes
  // as they are written and the decoder tags them premultiplied. They read back straight once the overlay negotiates
  // straight alpha. Both draws run the same path, so they land within rounding of each other, and the round trip
  // through premultiplied codes moves a channel near zero by up to 4 on 9.0.2.
  @Test
  fun `a half transparent OpenEXR draws where the PNG it was written from does`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val png = File(root, "half.png").apply { writeBytes(pngOf(HALF_TRANSPARENT, OVERLAY_SIZE)) }
      val exr = checkNotNull(encodedAs(png, "exr")) { "this ffmpeg writes no exr" }

      val drawn = firstFrameOf(ImageSource.ofBytes(exr.readBytes()))
      val expected = firstFrameOf(ImageSource.of(png.path))
      withClue("exr read $drawn, the PNG reads $expected") { drawn.isNear(expected, SAME_PATH_TOLERANCE) shouldBe true }
    }

  // ffmpeg 8.0's OpenEXR decoder tags no alpha, so premultiplied codes reach a straight blend as they are stored and
  // draw where a PNG holding those codes does. 9.x tags them and the straight blend divides them out.
  @Test
  fun `an OpenEXR holding premultiplied codes draws as stored on ffmpeg 8 and divided out past it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val stored = premultiplied(HALF_TRANSPARENT)
      val exr = premultipliedExrOf(HALF_TRANSPARENT, OVERLAY_SIZE)

      val drawn = firstFrameOf(ImageSource.ofBytes(exr))
      val expectedColor = if (hostTagsExrAlpha) HALF_TRANSPARENT else stored
      val png = File(root, "expected.png").apply { writeBytes(pngOf(expectedColor, OVERLAY_SIZE)) }
      val expected = firstFrameOf(png)
      withClue("ffmpeg ${hostVersion.printed} read $drawn, expected $expected") {
        drawn.isNear(expected, SAME_PATH_TOLERANCE) shouldBe true
      }
    }

  // Whether the host's OpenEXR decoder tags its frames premultiplied, which it does from ffmpeg 9.0.
  private val hostTagsExrAlpha: Boolean
    get() =
      hostVersion.major?.let { it >= ALPHA_TAG_MAJOR }
        ?: ((hostVersion.libavformatMajor ?: 0) > MINIMUM_LIBAVFORMAT_MAJOR)

  private val hostVersion by lazy {
    val process = ProcessBuilder(hostFfmpeg, "-version").redirectErrorStream(true).start()
    parseVersion(process.inputStream.bufferedReader().readText()).also { process.waitFor() }
  }

  // [argb] with its colour multiplied through by its alpha, rounded to the nearest code.
  private fun premultiplied(argb: Int): Int {
    val alpha = argb ushr 24
    val (red, green, blue) = channelsOf(argb).toList().map { (it * alpha + CODE_MAX / 2) / CODE_MAX }
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }

  // An uncompressed scanline OpenEXR of [size] filled flat with [argb], its colour stored premultiplied as 32-bit
  // floats on the same 0 to 1 scale ffmpeg reads a code onto.
  private fun premultipliedExrOf(
    argb: Int,
    size: Size,
  ): ByteArray {
    val alpha = (argb ushr 24) / CODE_MAX.toFloat()
    val (red, green, blue) = channelsOf(argb).toList().map { it / CODE_MAX.toFloat() * alpha }
    val lineBytes = size.width * EXR_CHANNELS.size * Float.SIZE_BYTES
    val header = ByteArrayOutputStream()
    val out = DataOutputStream(header)

    fun int(value: Int) = out.writeInt(Integer.reverseBytes(value))

    fun float(value: Float) = int(value.toRawBits())

    fun attribute(
      name: String,
      type: String,
      size: Int,
    ) {
      out.writeBytes("$name\u0000$type\u0000")
      int(size)
    }
    int(EXR_MAGIC)
    int(EXR_VERSION)
    attribute("channels", "chlist", EXR_CHANNELS.size * (2 + EXR_CHANNEL_BYTES) + 1)
    for (channel in EXR_CHANNELS) {
      out.writeBytes("$channel\u0000")
      int(EXR_FLOAT)
      int(0)
      int(1)
      int(1)
    }
    out.writeByte(0)
    attribute("compression", "compression", 1)
    out.writeByte(0)
    for (window in listOf("dataWindow", "displayWindow")) {
      attribute(window, "box2i", EXR_BOX_BYTES)
      listOf(0, 0, size.width - 1, size.height - 1).forEach(::int)
    }
    attribute("lineOrder", "lineOrder", 1)
    out.writeByte(0)
    attribute("pixelAspectRatio", "float", Float.SIZE_BYTES)
    float(1f)
    attribute("screenWindowCenter", "v2f", 2 * Float.SIZE_BYTES)
    float(0f)
    float(0f)
    attribute("screenWindowWidth", "float", Float.SIZE_BYTES)
    float(1f)
    out.writeByte(0)
    val tableEnd = header.size() + size.height * Long.SIZE_BYTES
    repeat(size.height) { line ->
      out.writeLong(java.lang.Long.reverseBytes((tableEnd + line * (2 * Int.SIZE_BYTES + lineBytes)).toLong()))
    }
    // Channels are stored in name order, A, B, G then R.
    val texel = listOf(alpha, blue, green, red)
    repeat(size.height) { line ->
      int(line)
      int(lineBytes)
      texel.forEach { value -> repeat(size.width) { float(value) } }
    }
    return header.toByteArray()
  }

  private suspend fun firstFrameOf(picture: File): Triple<Int, Int, Int> = firstFrameOf(ImageSource.of(picture.path))

  private suspend fun firstFrameOf(source: ImageSource): Triple<Int, Int, Int> {
    val badge = ImageOverlay(source, Corner.TopStart)
    val engine = engineWith {}
    val plan = plannedOn(engine, badge)
    val output = exported(engine, plan)
    val size = plan.output.size
    val probe = centreOf(badge.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
    return writtenFrames(output.path, probe = probe).first().let { Triple(it.red, it.green, it.blue) }
  }

  private fun pamOf(
    size: Size,
    texel: List<Int>,
  ): ByteArray {
    val header =
      "P7\nWIDTH ${size.width}\nHEIGHT ${size.height}\nDEPTH 4\nMAXVAL 65535\nTUPLTYPE RGB_ALPHA\nENDHDR\n"
    val bytes = texel.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }
    return header.encodeToByteArray() + List(size.width * size.height) { bytes }.flatten().toByteArray()
  }

  @Test
  fun `an animated GIF draws its first frame on every frame of the export`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val gif =
        File(root, "animated.gif").apply {
          writeBytes(OverlayPixels.twoFrameGifOf(OVERLAY_SIZE, OVERLAY_COLOR, SWAPPED_COLOR))
        }
      val badge = ImageOverlay(ImageSource.of(gif.path), Corner.TopStart)
      val engine = engineWith {}
      val plan = plannedOn(engine, badge)

      val output = exported(engine, plan)
      val size = plan.output.size
      val probe = centreOf(badge.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
      assertReads(writtenFrames(output.path, probe = probe), channelsOf(OVERLAY_COLOR), "the GIF")
    }

  private fun encodedAs(
    png: File,
    extension: String,
  ): File? {
    val file = File(root, "encoded.$extension")
    val command =
      listOf(
        hostFfmpeg,
        "-y",
        "-nostdin",
        "-loglevel",
        "error",
        "-i",
        png.path,
        "-strict",
        "experimental",
        "-frames:v",
        "1",
        "-update",
        "1",
        file.path,
      )
    val process =
      ProcessBuilder(
        command,
      ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    process.waitFor()
    return file.takeIf { process.exitValue() == 0 && it.length() > 0 }
  }

  private fun tgaOf(
    argb: Int,
    size: Size,
  ): ByteArray {
    val (red, green, blue) = channelsOf(argb)
    val header = ByteArray(TGA_HEADER)
    header[2] = TGA_TRUE_COLOR
    header[12] = size.width.toByte()
    header[14] = size.height.toByte()
    header[16] = TGA_DEPTH
    header[17] = TGA_TOP_LEFT
    val texel = byteArrayOf(blue.toByte(), green.toByte(), red.toByte())
    return header + ByteArray(size.width * size.height * texel.size) { texel[it % texel.size] }
  }

  private fun engineWith(beforeSpawn: () -> Unit): FfmpegExportEngine {
    val listener =
      DiagnosticListener { event ->
        if (event.name == DiagnosticEvent.INVOCATION) beforeSpawn()
      }
    val components =
      ComponentRegistry
        .Builder()
        .addEffectResolver(BuiltInEffectResolver())
        .addDiagnosticListener(listener)
        .build()
    return FfmpegExportEngine(components, FfmpegRuntime.unshared(FfmpegConfig()))
  }

  private suspend fun plannedOn(
    engine: FfmpegExportEngine,
    badge: ImageOverlay = overlay,
  ): ExportPlan {
    val composition =
      compositionOf {
        clip(MediaSource.of(fixture.landscape.absolutePath))
        effects { add(badge) }
      }
    return when (val verdict = engine.plan(composition, ExportSpec())) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
    }
  }

  private suspend fun exported(
    engine: FfmpegExportEngine,
    plan: ExportPlan,
  ): File {
    val output = File(root, "out.mp4")
    val statuses = engine.export(plan, MediaSink.of(output.path)).toList()
    statuses.last().shouldBeInstanceOf<ExportStatus.Success>()
    return output
  }

  private companion object {
    const val SWAPPED_COLOR = 0xFF20C040.toInt()
    val SWAPPED_SIZE = Size(128, 32)
    const val LOSSY_TOLERANCE = 8
    const val WHITE = 0xFFFFFFFF.toInt()
    const val HALF_TRANSPARENT = 0x80E67814.toInt()
    const val SAME_PATH_TOLERANCE = 4
    const val CODE_MAX = 255
    const val ALPHA_TAG_MAJOR = 9
    val EXR_CHANNELS = listOf("A", "B", "G", "R")
    const val EXR_MAGIC = 20000630
    const val EXR_VERSION = 2
    const val EXR_FLOAT = 2
    const val EXR_CHANNEL_BYTES = 16
    const val EXR_BOX_BYTES = 16
    const val SIXTEEN_BIT_STEP = 257
    const val SIXTEEN_BIT_MAX = 65535
    const val TGA_HEADER = 18
    const val TGA_TRUE_COLOR: Byte = 2
    const val TGA_DEPTH: Byte = 24
    const val TGA_TOP_LEFT: Byte = 0x20
  }
}
