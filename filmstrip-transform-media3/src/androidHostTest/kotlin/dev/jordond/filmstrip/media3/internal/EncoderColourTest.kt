package dev.jordond.filmstrip.media3.internal

import android.media.metrics.LogSessionId
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Codec
import androidx.media3.transformer.ExportException
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.media.ColorSpace
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How [EncoderColour] swaps an encoder for one tagged with the colour its probe measured, against a fake factory, and
 * what [retagReport] says when it did.
 *
 * Each fake encoder counts its releases and refuses to name itself once released, as a released `MediaCodec` does.
 * The factory records the format each one was made from, and stands in for the one pinned to an encoder's name too.
 */
class EncoderColourTest {
  @Test
  fun `an encoder measured writing bt601 is released once and made again tagged bt601`() {
    val factory = FakeFactory(names = listOf(SOFTWARE, SOFTWARE))
    val colour = colour(factory, probe(BT601_LIMITED))

    val encoder = colour.createForVideoEncoding(sdrFormat(), null)

    assertEquals(1, factory.made[0].releases)
    assertEquals(0, factory.made[1].releases)
    assertSame(factory.made[1].codec, encoder)
    assertEquals(BT601_LIMITED, factory.formats[1].colorInfo)
    assertEquals(SOFTWARE, colour.retag?.encoder)
    assertEquals(ColorInfo.SDR_BT709_LIMITED, colour.retag?.requested)
    assertEquals(BT601_LIMITED, colour.retag?.written)
    assertEquals(listOf(SOFTWARE), pinnedTo)
    assertEquals(emptyList(), logs)
  }

  @Test
  fun `the probe is asked about the encoder at the size tier it was configured at`() {
    val asked = mutableListOf<EncoderColourRequest>()
    val factory = FakeFactory(names = listOf(SOFTWARE), configuredSize = CONFIGURED_WIDTH to CONFIGURED_HEIGHT)
    val colour =
      colour(factory) { request ->
        asked += request
        WrittenColour.Measured(ColorInfo.SDR_BT709_LIMITED)
      }

    colour.createForVideoEncoding(sdrFormat(), null)

    val request =
      EncoderColourRequest(
        SOFTWARE,
        MimeTypes.VIDEO_H264,
        SizeTier.HD,
        ColorInfo.SDR_BT709_LIMITED,
      )
    assertEquals(listOf(request), asked)
  }

  @Test
  fun `a pinned recreate that comes back as another encoder fails as no encoder with both released`() {
    mockkStatic(SystemClock::class)
    every { SystemClock.elapsedRealtime() } returns 0L
    try {
      val factory = FakeFactory(names = listOf(SOFTWARE, OTHER))
      val colour = colour(factory, probe(BT601_LIMITED))

      val failure = assertFailsWith<ExportException> { colour.createForVideoEncoding(sdrFormat(), null) }

      assertEquals(listOf(1, 1), factory.made.map { it.releases })
      assertEquals(listOf(SOFTWARE), pinnedTo)
      assertEquals(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, failure.errorCode)
      assertEquals(OTHER, failure.codecInfo?.name)
      val error = assertIs<ExportError.NoEncoder>(failure.toExportError(VideoCodec.H264))
      assertTrue(error.message.contains(OTHER), error.message)
      assertNull(colour.retag)
    } finally {
      unmockkAll()
    }
  }

  @Test
  fun `a probe that throws keeps the request and says why`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val colour = colour(factory) { error("no codec") }

    val encoder = colour.createForVideoEncoding(sdrFormat(), null)

    assertSame(factory.made.single().codec, encoder)
    assertEquals(0, factory.made.single().releases)
    assertNull(colour.retag)
    assertTrue(logs.single().contains("no codec"), logs.toString())
  }

  @Test
  fun `an interrupted probe keeps the request and the interrupt`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val colour = colour(factory) { throw InterruptedException() }

    val encoder =
      try {
        colour.createForVideoEncoding(sdrFormat(), null)
      } finally {
        assertTrue(Thread.interrupted())
      }

    assertSame(factory.made.single().codec, encoder)
    assertEquals(0, factory.made.single().releases)
    assertEquals(1, logs.size)
  }

  @Test
  fun `an encoder measured following the request is kept`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val colour = colour(factory, probe(ColorInfo.SDR_BT709_LIMITED))

    val encoder = colour.createForVideoEncoding(sdrFormat(), null)

    assertSame(factory.made.single().codec, encoder)
    assertEquals(0, factory.made.single().releases)
    assertNull(colour.retag)
  }

  @Test
  fun `an encoder the probe cannot tell about is kept and says why`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val colour = colour(factory) { WrittenColour.Unclear(UNCLEAR) }

    val encoder = colour.createForVideoEncoding(sdrFormat(), null)

    assertSame(factory.made.single().codec, encoder)
    assertNull(colour.retag)
    assertTrue(logs.single().contains(SOFTWARE) && logs.single().contains(UNCLEAR), logs.toString())
  }

  @Test
  fun `only an sdr bt709 limited range request is measured`() {
    val asked = mutableListOf<ColorInfo>()
    val colour =
      colour(FakeFactory(names = List(3) { SOFTWARE })) { request ->
        asked += request.requested
        WrittenColour.Measured(BT601_LIMITED)
      }
    val fullRange =
      ColorInfo.SDR_BT709_LIMITED
        .buildUpon()
        .setColorRange(C.COLOR_RANGE_FULL)
        .build()

    colour.createForVideoEncoding(sdrFormat(fullRange), null)
    colour.createForVideoEncoding(sdrFormat(BT601_LIMITED), null)
    colour.createForVideoEncoding(sdrFormat(null), null)

    assertEquals(emptyList(), asked)
    assertNull(colour.retag)
  }

  @Test
  fun `a recreate that throws is passed on with the first encoder released`() {
    val factory = FakeFactory(names = listOf(SOFTWARE), failSecond = true)
    val colour = colour(factory, probe(BT601_LIMITED))

    assertFailsWith<IllegalStateException> { colour.createForVideoEncoding(sdrFormat(), null) }

    assertEquals(1, factory.made.single().releases)
    assertNull(colour.retag)
  }

  @Test
  fun `a retag reports the encoder and both colours`() {
    val adjustment = retagReport(ExportPath.Transcode, Retag(SOFTWARE, ColorInfo.SDR_BT709_LIMITED, BT601_LIMITED))

    assertEquals(AdjustmentKind.ColorSpaceChanged, adjustment?.kind)
    assertTrue(adjustment?.requested.orEmpty().startsWith(ColorSpace.Bt709.name))
    assertTrue(adjustment?.resolved.orEmpty().startsWith(ColorSpace.Bt601.name))
    assertTrue(adjustment?.message.orEmpty().contains(SOFTWARE))
    assertTrue(adjustment?.message.orEmpty().contains("BT.470 BG"))
  }

  @Test
  fun `a range only retag names no primaries`() {
    val fullRange =
      ColorInfo.SDR_BT709_LIMITED
        .buildUpon()
        .setColorRange(C.COLOR_RANGE_FULL)
        .build()

    val adjustment = retagReport(ExportPath.Transcode, Retag(SOFTWARE, ColorInfo.SDR_BT709_LIMITED, fullRange))

    assertTrue(adjustment?.resolved.orEmpty().contains("full"))
    assertFalse(adjustment?.message.orEmpty().contains("BT.470 BG"))
  }

  @Test
  fun `a transmux or no retag reports nothing`() {
    val retag = Retag(SOFTWARE, ColorInfo.SDR_BT709_LIMITED, BT601_LIMITED)

    assertNull(retagReport(ExportPath.Transmux, retag))
    assertNull(retagReport(ExportPath.Transcode, null))
    assertNull(retagReport(ExportPath.TrimOptimized, null))
  }

  private val logs = mutableListOf<String>()
  private val pinnedTo = mutableListOf<String>()

  private fun colour(
    factory: FakeFactory,
    probe: EncoderColourProbe,
  ) = EncoderColour(factory, { encoder -> factory.also { pinnedTo += encoder } }, probe, logs::add)

  private fun probe(answer: ColorInfo) = EncoderColourProbe { WrittenColour.Measured(answer) }

  private fun sdrFormat(colour: ColorInfo? = ColorInfo.SDR_BT709_LIMITED): Format =
    Format
      .Builder()
      .setSampleMimeType(MimeTypes.VIDEO_H264)
      .setWidth(WIDTH)
      .setHeight(HEIGHT)
      .setColorInfo(colour)
      .build()

  private class Made(
    name: String,
    configured: Format,
  ) {
    var releases = 0

    val codec: Codec =
      Proxy.newProxyInstance(Codec::class.java.classLoader, arrayOf(Codec::class.java)) { proxy, method, args ->
        when (method.name) {
          "getName" -> name.also { check(releases == 0) { "$name was asked its name after release" } }
          "toString" -> name
          "getConfigurationFormat" -> configured
          "release" -> releases++.let { null }
          "hashCode" -> System.identityHashCode(proxy)
          "equals" -> proxy === args?.firstOrNull()
          else -> throw UnsupportedOperationException(method.name)
        }
      } as Codec
  }

  private class FakeFactory(
    private val names: List<String>,
    private val failSecond: Boolean = false,
    private val configuredSize: Pair<Int, Int>? = null,
  ) : Codec.EncoderFactory {
    val made = mutableListOf<Made>()
    val formats = mutableListOf<Format>()

    override fun createForAudioEncoding(
      format: Format,
      logSessionId: LogSessionId?,
    ): Codec = throw UnsupportedOperationException()

    override fun createForVideoEncoding(
      format: Format,
      logSessionId: LogSessionId?,
    ): Codec {
      check(!(failSecond && made.isNotEmpty())) { "no encoder for $format" }
      formats += format
      val configured =
        configuredSize?.let { (width, height) ->
          format
            .buildUpon()
            .setWidth(width)
            .setHeight(height)
            .build()
        }
          ?: format
      return Made(names[made.size], configured).also { made += it }.codec
    }
  }

  private companion object {
    const val SOFTWARE = "c2.android.avc.encoder"
    const val OTHER = "c2.vendor.avc.encoder"
    const val UNCLEAR = "it wrote green as nothing near"
    const val CONFIGURED_WIDTH = 1280
    const val CONFIGURED_HEIGHT = 720
    const val WIDTH = 640
    const val HEIGHT = 360

    val BT601_LIMITED: ColorInfo =
      ColorInfo.SDR_BT709_LIMITED
        .buildUpon()
        .setColorSpace(C.COLOR_SPACE_BT601)
        .build()
  }
}
