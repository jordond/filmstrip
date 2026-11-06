@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.effect.WebGlTable
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.render.StepTables
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/**
 * A [Lut] and the table seam under it, in the real browser compositor.
 *
 * Reads come off a preview, which is the export's own lowering and pass with no encoder in the way, and each is held
 * against [CubeLut.at] of the same clip drawn plain, so the fixture's encoder and decoder are in the expected value.
 */
class BrowserLutTest {
  // Cross-talk in every channel, so a read at mid-range tells trilinear from anything simpler.
  private val table =
    CubeLut.parse(
      cubeText(CUBE_POINTS) { red, green, blue ->
        doubleArrayOf(
          red * red * 0.7 + blue * 0.2,
          green * (1.0 - red * 0.4) + blue * blue * 0.1,
          (1.0 - blue) * 0.5 + green * red * 0.4,
        )
      },
    )

  @Test
  fun aStepReadsAnUploadedTable() =
    runTest {
      val clip = flat(Rgb(100, 140, 180))
      val raster = byteArrayOf(10, 20, 30, -1, 40, 50, 60, -1, 70, 80, 90, -1, 100, 110, 120, -1)
      val pass = TableEffect(WebGlTable(2, 2, raster))

      val frame = frameOf(edit(Clip(clip), listOf(pass)), TableResolver)

      frame.pixelAt(0, 0).isNear(Rgb(100, 110, 120), SEAM_CODES) shouldBe true
    }

  @Test
  fun anEqualTableDoesNotUploadTwice() {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()).shouldNotBeNull()
    val tables = StepTables(gl, maxTextureSide = 16)
    val bytes = ByteArray(16)

    val first = tables.textureOf("test/step", WebGlTable(2, 2, bytes))
    val second = tables.textureOf("test/step", WebGlTable(2, 2, bytes.copyOf()))

    (first === second) shouldBe true
  }

  @Test
  fun aTableReadBeforeIsFoundWithoutItsBytes() {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()).shouldNotBeNull()
    val tables = StepTables(gl, maxTextureSide = 16)
    val bytes = ByteArray(16)
    val first = tables.textureOf("test/step", WebGlTable(2, 2, bytes))
    val equal = WebGlTable(2, 2, bytes.copyOf())
    tables.textureOf("test/step", equal)

    // An equality check would now tell these apart, and an instance found by identity never runs one.
    equal.bytes[0] = 1

    (tables.textureOf("test/step", equal) === first) shouldBe true
  }

  @Test
  fun forgettingDeletesATextureAndTheNextReadUploadsAgain() {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()).shouldNotBeNull()
    val tables = StepTables(gl, maxTextureSide = 16)
    val table = WebGlTable(2, 2, ByteArray(16))
    val first = tables.textureOf("test/step", table)
    gl.isTexture(first) shouldBe true

    tables.forget()

    gl.isTexture(first) shouldBe false
    val second = tables.textureOf("test/step", table)
    (second === first) shouldBe false
    gl.isTexture(second) shouldBe true
  }

  @Test
  fun evictionDeletesTheLeastRecentlyRead() {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()).shouldNotBeNull()
    val tables = StepTables(gl, maxTextureSide = 16, maxResidentBytes = 32)
    val first = WebGlTable(2, 2, ByteArray(16) { 1 })
    val second = WebGlTable(2, 2, ByteArray(16) { 2 })
    val third = WebGlTable(2, 2, ByteArray(16) { 3 })

    val firstTexture = tables.textureOf("test/step", first)
    val secondTexture = tables.textureOf("test/step", second)
    tables.evictPastBound()
    gl.isTexture(firstTexture) shouldBe true
    gl.isTexture(secondTexture) shouldBe true

    tables.textureOf("test/step", first)
    val thirdTexture = tables.textureOf("test/step", third)
    tables.evictPastBound()

    gl.isTexture(secondTexture) shouldBe false
    gl.isTexture(firstTexture) shouldBe true
    gl.isTexture(thirdTexture) shouldBe true
    (tables.textureOf("test/step", second) === secondTexture) shouldBe false
  }

  @Test
  fun aTablePastTheBoundStaysForItsOwnDraw() {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()).shouldNotBeNull()
    val tables = StepTables(gl, maxTextureSide = 16, maxResidentBytes = 8)

    val texture = tables.textureOf("test/step", WebGlTable(2, 2, ByteArray(16)))
    tables.evictPastBound()

    gl.isTexture(texture) shouldBe true
  }

  @Test
  fun aSwapForgetsTheOldTableAndTheNextFrameUploadsAgain() =
    runTest {
      val clip = flat(GREY)
      val composition = edit(Clip(clip), listOf(Lut(table)))
      val replaced = edit(Clip(clip), listOf(Lut(table, 0.5f)))
      val preview = previewOf(composition)
      try {
        preview.frameAt(READ_TIME).shouldNotBeNull()
        val before = preview.tableTextures.single()

        withClue("the swap was refused") { preview.updateParameters(resolvedOf(replaced), replaced) shouldBe true }

        preview.tableTextures shouldBe emptyList()
        preview.frameAt(READ_TIME).shouldNotBeNull()
        (preview.tableTextures.single() === before) shouldBe false
      } finally {
        preview.release()
      }
    }

  @Test
  fun aTablePastTheContextsLimitIsRefusedByName() {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()).shouldNotBeNull()
    val limit = 4

    StepTables(gl, limit).textureOf("test/step", WebGlTable(limit, limit, ByteArray(limit * limit * RGBA)))
    assertFailsWith<BrowserExportFailure> {
      StepTables(gl, limit).textureOf("test/step", WebGlTable(limit + 1, 1, ByteArray((limit + 1) * RGBA)))
    }.message.shouldNotBeNull() shouldContain "test/step needs a 5x1 texture"
  }

  @Test
  fun aLutReadsLikeTheTableAtMidRange() =
    runTest {
      val clip = ramp()
      val plain = frameOf(edit(Clip(clip)))
      val frame = frameOf(edit(Clip(clip), listOf(Lut(table))))

      withClue("the table has to move this ramp") { worstCodes(plain, plain, table) shouldBeGreaterThan EFFECT_CODES }
      worstCodes(frame, plain, table) shouldBeLessThan DRAWN_CODES
    }

  @Test
  fun intensityReadsLikeTheBlendedTable() =
    runTest {
      val clip = ramp()
      val half = Lut(table, 0.5f)
      val plain = frameOf(edit(Clip(clip)))
      val frame = frameOf(edit(Clip(clip), listOf(half)))

      worstCodes(frame, plain, table.blended(0.5f)) shouldBeLessThan DRAWN_CODES
      worstCodes(frame, plain, table) shouldBeGreaterThan EFFECT_CODES
    }

  @Test
  fun aPreviewHeightReadsTheSameTable() =
    runTest {
      val clip = ramp()
      val small = ExportSpec(videoCodec = VideoCodec.H264, heightPx = HEIGHT / 2)
      val plain = frameOf(edit(Clip(clip)), spec = small)
      val frame = frameOf(edit(Clip(clip), listOf(Lut(table))), spec = small)

      frame.size.height shouldBe HEIGHT / 2
      worstCodes(frame, plain, table) shouldBeLessThan DRAWN_CODES
    }

  @Test
  fun aSecondFrameDrawsTheSamePicture() =
    runTest {
      val clip = ramp()
      val composition = edit(Clip(clip), listOf(Lut(table)))
      val preview = previewOf(composition)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val second = preview.frameAt(READ_TIME).shouldNotBeNull()
        for (x in listOf(WIDTH / 4, WIDTH / 2, WIDTH * 3 / 4)) {
          first.pixelAt(x, HEIGHT / 2).isNear(second.pixelAt(x, HEIGHT / 2), 1) shouldBe true
        }
      } finally {
        preview.release()
      }
    }

  @Test
  fun aCompositionLutReachesTheBars() =
    runTest {
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val fill = Fill.Solid(SLATE_ARGB)
      val clip = flat(GREY)

      val frame = frameOf(edit(Clip(clip), listOf(square, Lut(table)), fill))
      val picture = frameOf(edit(Clip(clip), listOf(square), fill))

      val bar = frame.size.width / BAR_READ_OF_WIDTH
      val y = frame.size.height / 2
      val shift = abs(frame.pixelAt(bar, y).red - picture.pixelAt(bar, y).red).toDouble()
      withClue("the table has to change the bar, it moved it $shift") { shift shouldBeGreaterThan EFFECT_CODES }
      worstCodes(frame, picture, table, rows = listOf(y), columns = listOf(bar)) shouldBeLessThan DRAWN_CODES
    }

  @Test
  fun aClipLutLeavesACanvasBarClearOverTheFill() =
    runTest {
      val square = MediaSource.Bytes(makeClip(SQUARE, SQUARE, FRAMES, FRAME_RATE) { _, _ -> GREY })
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)
      val fill = Fill.Solid(SLATE_ARGB)

      val frame = frameOf(edit(Clip(square, effects = listOf(wide, Lut(table))), fill = fill))
      val plain = frameOf(edit(Clip(square, effects = listOf(wide)), fill = fill))

      val y = frame.size.height / 2
      frame.pixelAt(2, y).isNear(plain.pixelAt(2, y), DRAWN_CODES.toInt()) shouldBe true
      val middle = frame.size.width / 2
      worstCodes(frame, plain, table, rows = listOf(y), columns = listOf(middle)) shouldBeLessThan DRAWN_CODES
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest {
      val clip = flat(GREY)
      val overlay = swatch()

      val frame = frameOf(edit(Clip(clip), listOf(Lut(table), overlay)))
      val clean = frameOf(edit(Clip(clip), listOf(overlay)))

      val swatchRead = frame.at(SWATCH_U, SWATCH_V)
      withClue("swatch reads ${swatchRead.red} ${swatchRead.green} ${swatchRead.blue}") {
        swatchRead.isNear(clean.at(SWATCH_U, SWATCH_V), SWATCH_CODES) shouldBe true
      }
      val out = frame.pixelAt(WIDTH / 2, HEIGHT / 2)
      abs(out.red - clean.pixelAt(WIDTH / 2, HEIGHT / 2).red).toDouble() shouldBeGreaterThan EFFECT_CODES
    }

  @Test
  fun aClipLutReadsAToneMappedFrame() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest
      val clip = makeHdrClip(HdrTransfer.Pq, WIDTH, HEIGHT, FRAMES, FRAME_RATE) { HDR_NITS }
      val spec = ExportSpec(videoCodec = VideoCodec.H264, hdr = HdrMode.ToneMapToSdr)

      val plain = frameOf(edit(Clip(MediaSource.Bytes(clip))), spec = spec)
      val frame = frameOf(edit(Clip(MediaSource.Bytes(clip), effects = listOf(Lut(table)))), spec = spec)

      worstCodes(frame, plain, table) shouldBeLessThan DRAWN_CODES
    }

  // The largest gap between the frame and the table of the plain picture, in eight bit codes.
  private fun worstCodes(
    frame: PreviewFrame,
    plain: PreviewFrame,
    table: CubeLut,
    rows: List<Int> = READ_FRACTIONS.map { (it * frame.size.height).toInt() },
    columns: List<Int> = READ_FRACTIONS.map { (it * frame.size.width).toInt() },
  ): Double {
    var worst = 0.0
    rows.forEach { y ->
      columns.forEach { x ->
        val source = plain.pixelAt(x, y)
        val want =
          table.at(source.red / FULL.toFloat(), source.green / FULL.toFloat(), source.blue / FULL.toFloat())
        val read = frame.pixelAt(x, y)
        worst = max(worst, abs(read.red - want[0] * FULL))
        worst = max(worst, abs(read.green - want[1] * FULL))
        worst = max(worst, abs(read.blue - want[2] * FULL))
      }
    }
    return worst
  }

  private suspend fun ramp(): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, y ->
        Rgb(60 + 120 * x / WIDTH, 170 - 80 * y / HEIGHT, 90 + 50 * x / WIDTH)
      },
    )

  private suspend fun flat(colour: Rgb): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> colour })

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private fun edit(
    clip: Clip,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(BLACK_ARGB),
  ) = EditComposition(
    tracks = listOf(Track(listOf(clip))),
    effects = effects,
    fill = fill,
    audio = AudioSpec.Remove,
  )

  private suspend fun frameOf(
    composition: EditComposition,
    resolver: EffectResolver = FilterEffectResolver(),
    spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264),
  ): PreviewFrame {
    val preview = previewOf(composition, resolver, spec)
    try {
      return preview.frameAt(READ_TIME).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  private suspend fun previewOf(
    composition: EditComposition,
    resolver: EffectResolver = FilterEffectResolver(),
    spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264),
  ) = resolvedOf(composition, resolver, spec).toBrowserPreview(composition)

  private suspend fun resolvedOf(
    composition: EditComposition,
    resolver: EffectResolver = FilterEffectResolver(),
    spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264),
  ) = webCodecsExportEngine(
    components =
      ComponentRegistry
        .Builder()
        .addEffectResolver(BuiltInEffectResolver())
        .addEffectResolver(resolver)
        .build(),
    prober = BrowserProber(),
  ).resolve(composition, spec)
    .shouldBeInstanceOf<ResolveResult.Resolved>()
    .composition

  private fun PreviewFrame.pixelAt(
    x: Int,
    y: Int,
  ): Rgb = at((x + 0.5) / size.width, (y + 0.5) / size.height)
}

private class TableEffect(
  val table: WebGlTable,
) : EffectSpec {
  override val id: String get() = "test.table"
  override val stage: EffectStage get() = EffectStage.Composite
  override val beneathOverlays: Boolean get() = true
}

private val TableResolver =
  EffectResolver { spec, _, _ ->
    (spec as? TableEffect)?.let {
      val step =
        WebGlStep(
          name = "draw",
          fragmentSource = TABLE_FRAGMENT,
          reads = mapOf("uFrame" to WebGlStep.INPUT),
          textures = mapOf("uTable" to it.table),
        ) { _, _, _ -> emptyMap() }
      EffectResolution.Resolved(PlatformEffect(WebGlPass("test.table", listOf(step))))
    }
  }

// Writes the table's far corner, which shows row order and that nothing filtered or flipped it.
private val TABLE_FRAGMENT =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D uFrame;
  uniform sampler2D uTable;
  void main() {
    vec4 c = texture(uFrame, vUv);
    fragColor = vec4(texelFetch(uTable, ivec2(1, 1), 0).rgb, c.a);
  }
  """.trimIndent()

private fun cubeText(
  size: Int,
  entry: (red: Double, green: Double, blue: Double) -> DoubleArray,
): String =
  buildString {
    append("LUT_3D_SIZE ").append(size).append('\n')
    val last = (size - 1).toDouble()
    for (blue in 0 until size) {
      for (green in 0 until size) {
        for (red in 0 until size) {
          val row = entry(red / last, green / last, blue / last)
          append(row[0])
            .append(' ')
            .append(row[1])
            .append(' ')
            .append(row[2])
            .append('\n')
        }
      }
    }
  }

private const val CUBE_POINTS = 5
private const val FULL = 255.0
private const val RGBA = 4
private const val WIDTH = 1280
private const val HEIGHT = 720
private const val SQUARE = 480
private const val FRAMES = 12
private const val FRAME_RATE = 30
private val READ_TIME: Duration = outputSlotTime(6L, FRAME_RATE)
private val READ_FRACTIONS = listOf(0.2, 0.35, 0.5, 0.65, 0.8)

// The table moves a read well past the codec noise this guards, and the draw's own error sits under it.
private const val EFFECT_CODES = 12.0
private const val DRAWN_CODES = 4.0
private const val SEAM_CODES = 8
private const val BAR_READ_OF_WIDTH = 16
private const val SLATE_ARGB = 0xFF405060.toInt()
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
private const val SWATCH_U = 0.1
private const val SWATCH_V = 0.1
private const val SWATCH_CODES = 2
private val HDR_NITS = floatArrayOf(120f, 180f, 75f)
private val GREY = Rgb(120, 130, 110)
