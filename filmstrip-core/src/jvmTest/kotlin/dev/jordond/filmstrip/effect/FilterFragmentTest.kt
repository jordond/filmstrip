package dev.jordond.filmstrip.effect

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * What a fragment is equal by, and what it holds when a resolver names only the older fields.
 */
class FilterFragmentTest {
  private val merge = FilterNode("overlay")
  private val crop = FilterCrop(width = "64", height = "32", x = "8", y = "4")
  private val post = listOf(FilterNode("format", "pix_fmts" to "yuv420p10le"))

  @Test
  fun `a fragment built without the new fields has no crop and no post`() {
    val fragment = FilterFragment(chain = listOf(FilterNode("null")), merge = merge)

    fragment.crop.shouldBeNull()
    fragment.post.isEmpty().shouldBeTrue()
  }

  @Test
  fun `the crop counts toward equality`() {
    FilterFragment(merge = merge, crop = crop) shouldNotBe FilterFragment(merge = merge)
    FilterFragment(merge = merge, crop = crop) shouldBe FilterFragment(merge = merge, crop = crop)
  }

  @Test
  fun `the post chain counts toward equality`() {
    FilterFragment(merge = merge, post = post) shouldNotBe FilterFragment(merge = merge)
    FilterFragment(merge = merge, post = post) shouldBe FilterFragment(merge = merge, post = post)
  }
}
