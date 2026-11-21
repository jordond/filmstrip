package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.VideoQuality
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The export menu's rows, checked against the spec they build.
 */
class ExportOptionsTest {
  @Test
  fun `rows nobody touched ask for 1080p at 4 Mbps and leave the rest to filmstrip`() {
    ExportOptions().spec() shouldBe ExportSpec(heightPx = 1080, bitrate = Bitrate.mbps(4))
  }

  @Test
  fun `every preset copied into the rows builds that preset`() {
    ExportOptions.Presets.forEach { (name, preset) ->
      val options = ExportOptions().apply { apply(preset) }

      withClue(name) { options.spec() shouldBe preset }
    }
  }

  @Test
  fun `a quality tier takes the place of the bitrate`() {
    val options = ExportOptions().apply { quality = VideoQuality.Medium }

    options.bitrateMbps.shouldBeNull()
    options.spec() shouldBe ExportSpec(heightPx = 1080, quality = VideoQuality.Medium)
  }

  @Test
  fun `a bitrate takes the place of the quality tier`() {
    val options =
      ExportOptions().apply {
        quality = VideoQuality.Low
        bitrateMbps = 8
      }

    options.quality.shouldBeNull()
    options.spec() shouldBe ExportSpec(heightPx = 1080, bitrate = Bitrate.mbps(8))
  }

  @Test
  fun `the key frame interval and the newer codecs reach the spec`() {
    val options =
      ExportOptions().apply {
        keyFrameSeconds = 2
        videoCodec = VideoCodec.MatchSource
        audioCodec = AudioCodec.Flac
      }

    options.spec() shouldBe
      ExportSpec(
        heightPx = 1080,
        bitrate = Bitrate.mbps(4),
        videoCodec = VideoCodec.MatchSource,
        audioCodec = AudioCodec.Flac,
        keyFrameInterval = 2.seconds,
      )
  }
}
