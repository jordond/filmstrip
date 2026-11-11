package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.MimeTypes
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media3.internal.encoder.AAC_UNIT_FRAMES
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CopyTrackEndsTest {
  private fun slot(index: Long): Long = outputSlotTime(index, RATE).inWholeMicroseconds

  private val unit = AAC_UNIT_FRAMES * 1_000_000 / SAMPLE_RATE

  @Test
  fun `a lone frame ends on the grid`() {
    copiedVideoEndOf(listOf(slot(0)), slot(1)) shouldBe slot(1)
  }

  @Test
  fun `frames stopping short of the grid end on it`() {
    (2L..4L).forEach { frames ->
      copiedVideoEndOf((0 until frames).map(::slot), slot(frames)) shouldBe slot(frames)
    }
  }

  @Test
  fun `a reference written past the grid leaves the end alone`() {
    copiedVideoEndOf(listOf(slot(0), slot(4), slot(2), slot(1)), slot(2)).shouldBeNull()
  }

  @Test
  fun `a frame on the grid end leaves the end alone`() {
    copiedVideoEndOf(listOf(slot(0), slot(1)), slot(1)).shouldBeNull()
  }

  @Test
  fun `no frames leave the end alone`() {
    copiedVideoEndOf(emptyList(), slot(1)).shouldBeNull()
  }

  @Test
  fun `only priming is silent`() {
    copiedAudioEndOf(listOf(-unit), unit) shouldBe CopiedAudioEnd.Silent
    copiedAudioEndOf(emptyList(), unit) shouldBe CopiedAudioEnd.Silent
  }

  @Test
  fun `priming and one unit end one unit after zero`() {
    copiedAudioEndOf(listOf(-unit, 0), loneUnit = null) shouldBe CopiedAudioEnd.At(unit)
  }

  @Test
  fun `several units end one unit after the last`() {
    copiedAudioEndOf(listOf(-unit, 0, unit, 2 * unit), loneUnit = null) shouldBe CopiedAudioEnd.At(3 * unit)
  }

  @Test
  fun `a lone unit lasts the length its codec fixes`() {
    copiedAudioEndOf(listOf(0), unit) shouldBe CopiedAudioEnd.At(unit)
  }

  @Test
  fun `a lone unit of no fixed length is left unstamped`() {
    copiedAudioEndOf(listOf(0), loneUnit = null) shouldBe CopiedAudioEnd.Unstamped
  }

  @Test
  fun `only AAC fixes the length of a unit`() {
    fixedAudioUnitOf(MimeTypes.AUDIO_AAC, SAMPLE_RATE) shouldBe unit
    fixedAudioUnitOf(MimeTypes.AUDIO_AAC, 0).shouldBeNull()
    fixedAudioUnitOf(MimeTypes.AUDIO_OPUS, SAMPLE_RATE).shouldBeNull()
    fixedAudioUnitOf(null, SAMPLE_RATE).shouldBeNull()
  }

  private companion object {
    const val RATE = 30
    const val SAMPLE_RATE = 48_000
  }
}
