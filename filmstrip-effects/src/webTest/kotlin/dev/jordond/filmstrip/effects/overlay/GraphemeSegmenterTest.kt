package dev.jordond.filmstrip.effects.overlay

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The browser's `Intl.Segmenter` as the text wrap reaches it, splitting a word into graphemes.
 */
class GraphemeSegmenterTest {
  @Test
  fun aBaseLetterKeepsItsMarksAndEveryCodeUnitFindsTheGraphemeHoldingIt() {
    val word = "ca\u0301fe\u0301"
    val segments = Segmenter().segment(word)

    val found = word.indices.map { segments.containing(it)?.segment }
    found shouldBe listOf("c", "a\u0301", "a\u0301", "f", "e\u0301", "e\u0301")
  }

  @Test
  fun aSurrogatePairAndAJoinedSequenceAreEachOneGrapheme() {
    val family = "\uD83D\uDC69\u200D\uD83D\uDC67"
    val word = "x$family\uD83D\uDE00"
    val segments = Segmenter().segment(word)

    segments.containing(1)?.segment shouldBe family
    segments.containing(family.length)?.segment shouldBe family
    segments.containing(word.length - 1)?.segment shouldBe "\uD83D\uDE00"
  }

  @Test
  fun aCodeUnitPastTheStringIsInNoGrapheme() {
    Segmenter().segment("ab").containing(2).shouldBeNull()
  }
}
