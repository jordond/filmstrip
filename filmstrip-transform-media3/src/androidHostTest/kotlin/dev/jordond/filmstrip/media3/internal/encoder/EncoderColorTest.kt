package dev.jordond.filmstrip.media3.internal.encoder

import android.media.metrics.LogSessionId
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Codec
import androidx.media3.transformer.ExportException
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media3.internal.toFilmstripError
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * How [EncoderColor] swaps an encoder for one tagged with the color its probe measured, against a fake factory, and
 * what [retagReport] says when it did.
 *
 * Each fake encoder counts its releases and refuses to name itself once released, as a released `MediaCodec` does.
 * The factory records the format each one was made from, and stands in for the one pinned to an encoder's name too.
 */
class EncoderColorTest {
  @Test
  fun `an encoder measured writing bt601 is released once and made again tagged bt601`() {
    val factory = FakeFactory(names = listOf(SOFTWARE, SOFTWARE))
    val color = color(factory, probe(BT601_LIMITED))

    val encoder = color.createForVideoEncoding(sdrFormat(), null)

    factory.made[0].releases shouldBe 1
    factory.made[1].releases shouldBe 0
    encoder shouldBeSameInstanceAs factory.made[1].codec
    factory.formats[1].colorInfo shouldBe BT601_LIMITED
    color.retag?.encoder shouldBe SOFTWARE
    color.retag?.requested shouldBe ColorInfo.SDR_BT709_LIMITED
    color.retag?.written shouldBe BT601_LIMITED
    pinnedTo shouldBe listOf(SOFTWARE)
    logs shouldBe emptyList()
  }

  @Test
  fun `the encoder is configured at the export's frame rate and keeps it through a retag`() {
    val factory = FakeFactory(names = listOf(SOFTWARE, SOFTWARE))
    val color =
      EncoderColor(factory, { factory }, probe(BT601_LIMITED), logs::add, frameRate = EXPORT_RATE)

    color.createForVideoEncoding(sdrFormat().buildUpon().setFrameRate(SOURCE_RATE).build(), null)

    factory.formats.map { it.frameRate } shouldBe listOf(EXPORT_RATE.toFloat(), EXPORT_RATE.toFloat())
  }

  @Test
  fun `an encoder made with no export rate keeps the rate it was asked for`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))

    color(factory, probe(ColorInfo.SDR_BT709_LIMITED))
      .createForVideoEncoding(sdrFormat().buildUpon().setFrameRate(SOURCE_RATE).build(), null)

    factory.formats.single().frameRate shouldBe SOURCE_RATE
  }

  @Test
  fun `the probe is asked about the encoder at the size tier it was configured at`() {
    val asked = mutableListOf<EncoderColorRequest>()
    val factory = FakeFactory(names = listOf(SOFTWARE), configuredSize = CONFIGURED_WIDTH to CONFIGURED_HEIGHT)
    val color =
      color(factory) { request ->
        asked += request
        WrittenColor.Measured(ColorInfo.SDR_BT709_LIMITED)
      }

    color.createForVideoEncoding(sdrFormat(), null)

    val request =
      EncoderColorRequest(
        SOFTWARE,
        MimeTypes.VIDEO_H264,
        SizeTier.HD,
        ColorInfo.SDR_BT709_LIMITED,
      )
    asked shouldBe listOf(request)
  }

  @Test
  fun `a pinned recreate that comes back as another encoder fails as no encoder with both released`() {
    mockkStatic(SystemClock::class)
    every { SystemClock.elapsedRealtime() } returns 0L
    try {
      val factory = FakeFactory(names = listOf(SOFTWARE, OTHER))
      val color = color(factory, probe(BT601_LIMITED))

      val failure = assertFailsWith<ExportException> { color.createForVideoEncoding(sdrFormat(), null) }

      factory.made.map { it.releases } shouldBe listOf(1, 1)
      pinnedTo shouldBe listOf(SOFTWARE)
      failure.errorCode shouldBe ExportException.ERROR_CODE_ENCODER_INIT_FAILED
      failure.codecInfo?.name shouldBe OTHER
      val error =
        failure
          .toFilmstripError(
            VideoCodec.H264,
            SOURCES,
            SINK,
          ).shouldBeInstanceOf<FilmstripError.NoEncoder>()
      withClue(error.message) { error.message.contains(OTHER) shouldBe true }
      color.retag.shouldBeNull()
    } finally {
      unmockkAll()
    }
  }

  @Test
  fun `a probe that throws keeps the request and says why`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val color = color(factory) { error("no codec") }

    val encoder = color.createForVideoEncoding(sdrFormat(), null)

    encoder shouldBeSameInstanceAs factory.made.single().codec
    factory.made.single().releases shouldBe 0
    color.retag.shouldBeNull()
    withClue(logs.toString()) { logs.single().contains("no codec") shouldBe true }
  }

  @Test
  fun `an interrupted probe keeps the request and the interrupt`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val color = color(factory) { throw InterruptedException() }

    val encoder =
      try {
        color.createForVideoEncoding(sdrFormat(), null)
      } finally {
        Thread.interrupted() shouldBe true
      }

    encoder shouldBeSameInstanceAs factory.made.single().codec
    factory.made.single().releases shouldBe 0
    logs.size shouldBe 1
  }

  @Test
  fun `an encoder measured following the request is kept`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val color = color(factory, probe(ColorInfo.SDR_BT709_LIMITED))

    val encoder = color.createForVideoEncoding(sdrFormat(), null)

    encoder shouldBeSameInstanceAs factory.made.single().codec
    factory.made.single().releases shouldBe 0
    color.retag.shouldBeNull()
  }

  @Test
  fun `an encoder the probe cannot tell about is kept and says why`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val color = color(factory) { WrittenColor.Unclear(UNCLEAR) }

    val encoder = color.createForVideoEncoding(sdrFormat(), null)

    encoder shouldBeSameInstanceAs factory.made.single().codec
    color.retag.shouldBeNull()
    withClue(logs.toString()) { (logs.single().contains(SOFTWARE) && logs.single().contains(UNCLEAR)) shouldBe true }
  }

  @Test
  fun `only an sdr bt709 limited range request is measured`() {
    val asked = mutableListOf<ColorInfo>()
    val color =
      color(FakeFactory(names = List(2) { SOFTWARE })) { request ->
        asked += request.requested
        WrittenColor.Measured(BT601_LIMITED)
      }
    val fullRange =
      ColorInfo.SDR_BT709_LIMITED
        .buildUpon()
        .setColorRange(C.COLOR_RANGE_FULL)
        .build()

    color.createForVideoEncoding(sdrFormat(fullRange), null)
    color.createForVideoEncoding(sdrFormat(null), null)

    asked shouldBe emptyList()
    color.retag.shouldBeNull()
  }

  @Test
  fun `an sdr bt601 request is made and measured as sdr bt709 at limited range`() {
    val factory = FakeFactory(names = listOf(SOFTWARE))
    val asked = mutableListOf<ColorInfo>()
    val color =
      color(factory) { request ->
        asked += request.requested
        WrittenColor.Measured(ColorInfo.SDR_BT709_LIMITED)
      }

    color.createForVideoEncoding(sdrFormat(BT601_LIMITED), null)

    factory.formats.single().colorInfo shouldBe ColorInfo.SDR_BT709_LIMITED
    asked shouldBe listOf(ColorInfo.SDR_BT709_LIMITED)
    color.retag.shouldBeNull()
  }

  @Test
  fun `a recreate that throws is passed on with the first encoder released`() {
    val factory = FakeFactory(names = listOf(SOFTWARE), failSecond = true)
    val color = color(factory, probe(BT601_LIMITED))

    assertFailsWith<IllegalStateException> { color.createForVideoEncoding(sdrFormat(), null) }

    factory.made.single().releases shouldBe 1
    color.retag.shouldBeNull()
  }

  @Test
  fun `a software aac encoder that drops its tail is padded by the units it writes`() {
    val cases =
      listOf(
        Triple(SOFTWARE_AAC, Build.VERSION_CODES.R, 0L),
        Triple(SOFTWARE_AAC, Build.VERSION_CODES.TIRAMISU, 0L),
        Triple(SOFTWARE_AAC, Build.VERSION_CODES.VANILLA_ICE_CREAM, 0L),
        Triple(OMX_AAC, Build.VERSION_CODES.P, 1L),
        Triple(OMX_AAC, Build.VERSION_CODES.Q, 1L),
      )
    cases.forEach { (name, sdkInt, tailUnits) ->
      val factory = FakeFactory(names = listOf(name))
      val color = audioColor(factory, sdkInt)

      withClue("$name on API $sdkInt") {
        color.createForAudioEncoding(aacFormat(), null).shouldBeInstanceOf<AacTailPad>().tailUnits shouldBe tailUnits
      }
    }
  }

  @Test
  fun `any other audio encoder or a flushing api level passes through`() {
    val cases =
      listOf(
        OTHER_AAC to Build.VERSION_CODES.R,
        OTHER_AAC to Build.VERSION_CODES.Q,
      )
    cases.forEach { (name, sdkInt) ->
      val factory = FakeFactory(names = listOf(name))
      val color = audioColor(factory, sdkInt)

      withClue("$name on API $sdkInt") {
        color.createForAudioEncoding(aacFormat(), null) shouldBeSameInstanceAs factory.made.single().codec
      }
    }
  }

  @Test
  fun `each software aac encoder names the priming it writes at every api level`() {
    val cases =
      listOf(
        SOFTWARE_AAC to Build.VERSION_CODES.R,
        SOFTWARE_AAC to Build.VERSION_CODES.VANILLA_ICE_CREAM,
        SOFTWARE_AAC to Build.VERSION_CODES.BAKLAVA,
        OMX_AAC to Build.VERSION_CODES.O,
        OMX_AAC to Build.VERSION_CODES.Q,
      )
    cases.forEach { (name, sdkInt) ->
      val factory = FakeFactory(names = listOf(name))
      val color = audioColor(factory, sdkInt)

      withClue("$name on API $sdkInt") {
        color.createForAudioEncoding(aacFormat(), null).outputFormat?.encoderDelay shouldBe AAC_PRIMING_FRAMES.toInt()
      }
    }
  }

  @Test
  fun `another aac encoder keeps the delay its output names`() {
    val factory = FakeFactory(names = listOf(OTHER_AAC))
    val color = audioColor(factory, Build.VERSION_CODES.Q)

    color.createForAudioEncoding(aacFormat(), null).outputFormat?.encoderDelay shouldBe MEDIA3_AAC_DELAY
  }

  @Test
  fun `a retag reports the encoder and both colors`() {
    val adjustment = retagReport(ExportPath.Transcode, Retag(SOFTWARE, ColorInfo.SDR_BT709_LIMITED, BT601_LIMITED))

    adjustment?.kind shouldBe AdjustmentKind.ColorSpaceChanged
    adjustment?.requested.orEmpty().startsWith(ColorSpace.Bt709.name) shouldBe true
    adjustment?.resolved.orEmpty().startsWith(ColorSpace.Bt601.name) shouldBe true
    adjustment?.message.orEmpty().contains(SOFTWARE) shouldBe true
    adjustment?.message.orEmpty().contains("BT.470 BG") shouldBe true
  }

  @Test
  fun `a range only retag names no primaries`() {
    val fullRange =
      ColorInfo.SDR_BT709_LIMITED
        .buildUpon()
        .setColorRange(C.COLOR_RANGE_FULL)
        .build()

    val adjustment = retagReport(ExportPath.Transcode, Retag(SOFTWARE, ColorInfo.SDR_BT709_LIMITED, fullRange))

    adjustment?.resolved.orEmpty().contains("full") shouldBe true
    adjustment?.message.orEmpty().contains("BT.470 BG") shouldBe false
  }

  @Test
  fun `a copy or no retag reports nothing`() {
    val retag = Retag(SOFTWARE, ColorInfo.SDR_BT709_LIMITED, BT601_LIMITED)

    retagReport(ExportPath.Copy, retag).shouldBeNull()
    retagReport(ExportPath.Transcode, null).shouldBeNull()
    retagReport(ExportPath.TrimOptimized, null).shouldBeNull()
  }

  private val logs = mutableListOf<String>()
  private val pinnedTo = mutableListOf<String>()

  private fun color(
    factory: FakeFactory,
    probe: EncoderColorProbe,
  ) = EncoderColor(factory, { encoder -> factory.also { pinnedTo += encoder } }, probe, logs::add)

  private fun audioColor(
    factory: FakeFactory,
    sdkInt: Int,
  ) = EncoderColor(factory, { factory }, probe(ColorInfo.SDR_BT709_LIMITED), logs::add, sdkInt)

  private fun probe(answer: ColorInfo) = EncoderColorProbe { WrittenColor.Measured(answer) }

  private fun aacFormat(): Format =
    Format
      .Builder()
      .setSampleMimeType(MimeTypes.AUDIO_AAC)
      .setSampleRate(SAMPLE_RATE)
      .setChannelCount(2)
      .build()

  private fun sdrFormat(color: ColorInfo? = ColorInfo.SDR_BT709_LIMITED): Format =
    Format
      .Builder()
      .setSampleMimeType(MimeTypes.VIDEO_H264)
      .setWidth(WIDTH)
      .setHeight(HEIGHT)
      .setColorInfo(color)
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
          "getOutputFormat" -> configured.buildUpon().setEncoderDelay(MEDIA3_AAC_DELAY).build()
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
    ): Codec = Made(names[made.size], format).also { made += it }.codec

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
    const val SOURCES = "/sdcard/clip.mp4"
    const val SINK = "/sdcard/out.mp4"
    const val SOFTWARE = "c2.android.avc.encoder"
    const val SOURCE_RATE = 30f
    const val EXPORT_RATE = 25
    const val OTHER = "c2.vendor.avc.encoder"
    const val SOFTWARE_AAC = "c2.android.aac.encoder"
    const val OTHER_AAC = "c2.vendor.aac.encoder"
    const val OMX_AAC = "OMX.google.aac.encoder"

    // The encoder delay media3 names on the software AAC encoder's output.
    const val MEDIA3_AAC_DELAY = 1_600
    const val SAMPLE_RATE = 48_000
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
