package dev.jordond.filmstrip.effect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a fragment is equal by, and what it holds when a resolver names only the older fields.
 */
class FilterFragmentTest {
  private val merge = FilterNode("overlay")
  private val crop = FilterNode("crop", "w" to "64", "h" to "32", "x" to "8", "y" to "4")
  private val post = listOf(FilterNode("format", "pix_fmts" to "yuv420p10le"))

  @Test
  fun `a fragment built without the new fields has no crop and no post`() {
    val fragment = FilterFragment(chain = listOf(FilterNode("null")), merge = merge)

    assertNull(fragment.crop)
    assertTrue(fragment.post.isEmpty())
  }

  @Test
  fun `the crop counts toward equality`() {
    assertNotEquals(FilterFragment(merge = merge), FilterFragment(merge = merge, crop = crop))
    assertEquals(FilterFragment(merge = merge, crop = crop), FilterFragment(merge = merge, crop = crop))
  }

  @Test
  fun `the post chain counts toward equality`() {
    assertNotEquals(FilterFragment(merge = merge), FilterFragment(merge = merge, post = post))
    assertEquals(FilterFragment(merge = merge, post = post), FilterFragment(merge = merge, post = post))
  }
}
