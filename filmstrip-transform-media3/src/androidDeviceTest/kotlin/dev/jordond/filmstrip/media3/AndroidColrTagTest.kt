package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build.VERSION.SDK_INT
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test

/**
 * What Android reads back from a file whose `colr` box states BT.709 primaries beside the BT.601 matrix.
 *
 * `MediaFormat` has no color standard for that pair. `MediaExtractor` reports it as [BT709_PRIMARIES_BT601_MATRIX],
 * outside every `MediaFormat` constant, and filmstrip's probe reads that as [ColorSpace.Unknown]. The file is an
 * export whose `colr` box is rewritten in place.
 *
 * Measured on API 31, 32 and 36, and skipped on any other level. Skipped when the fixtures are absent, as in
 * [AndroidExportTest].
 */
class AndroidColrTagTest {
  private val probe = Media3ExportProbe()

  @Test
  fun bt709PrimariesBesideTheBt601MatrixReadAsNoNamedStandard() =
    runTest(timeout = TIMEOUT) {
      assumeTrue("color standard $BT709_PRIMARIES_BT601_MATRIX was not measured on API $SDK_INT", SDK_INT in MEASURED)
      val exported = probe.export(EditComposition(listOf(Track(listOf(Clip(probe.fixture()))))))
      val patched = File(exported.parentFile, "colr-bt709-bt601.mp4")
      exported.copyTo(patched, overwrite = true)

      patchColr(patched, primaries = BT709, matrix = BT601)

      colorStandardOf(patched) shouldBe BT709_PRIMARIES_BT601_MATRIX
      probe.infoOf(MediaSource.of(patched.path)).video?.colorSpace shouldBe ColorSpace.Unknown
    }

  // Rewrites the primaries and matrix of the first nclx colr box in place, keeping its transfer and range.
  private fun patchColr(
    file: File,
    primaries: Int,
    matrix: Int,
  ) {
    val bytes = file.readBytes()
    val box =
      withClue("$file carries no nclx colr box") {
        (0..bytes.size - NCLX_BODY)
          .firstOrNull {
            bytes.startsWith(COLR, it) && bytes.startsWith(NCLX, it + 4)
          }.shouldNotBeNull()
      }
    RandomAccessFile(file, "rw").use { out ->
      out.seek((box + PRIMARIES_OFFSET).toLong())
      out.writeShort(primaries)
      out.seek((box + MATRIX_OFFSET).toLong())
      out.writeShort(matrix)
    }
  }

  private fun ByteArray.startsWith(
    tag: ByteArray,
    at: Int,
  ): Boolean = tag.indices.all { this[at + it] == tag[it] }

  private fun colorStandardOf(video: File): Int {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(video.path)
      val format =
        (0 until extractor.trackCount)
          .map(extractor::getTrackFormat)
          .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
      return format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
    } finally {
      extractor.release()
    }
  }

  private companion object {
    const val BT709 = 1
    const val BT601 = 6
    const val BT709_PRIMARIES_BT601_MATRIX = 86
    val MEASURED = setOf(31, 32, 36)

    // Counted from the colr type: the nclx tag, then primaries, transfer and matrix of two bytes each and a range byte.
    val COLR = "colr".toByteArray()
    val NCLX = "nclx".toByteArray()
    const val PRIMARIES_OFFSET = 8
    const val MATRIX_OFFSET = 12
    const val NCLX_BODY = 15
  }
}
