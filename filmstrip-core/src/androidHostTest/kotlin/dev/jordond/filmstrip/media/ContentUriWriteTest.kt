package dev.jordond.filmstrip.media

import android.content.ContentResolver
import android.net.Uri
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test

/**
 * How a write to a content URI reports a provider that gives no stream, fails, or refuses the caller.
 */
class ContentUriWriteTest {
  private val uri = mockk<Uri>()
  private val resolver = mockk<ContentResolver>()

  @Test
  fun `the bytes reach the provider's stream and it is closed once the write returns`() {
    val stream = ClosingStream()
    every { resolver.openOutputStream(uri) } returns stream

    resolver.writeContentUri(uri) { it.write(BYTES) }.shouldBeNull()

    stream.toByteArray().toList() shouldBe BYTES.toList()
    withClue("the stream was left open") { stream.closed.shouldBeTrue() }
  }

  @Test
  fun `a provider that gives back no stream is unwritable`() {
    every { resolver.openOutputStream(uri) } returns null

    val error = resolver.writeContentUri(uri) { error("nothing to write to") }.shouldNotBeNull()

    error.sink shouldBe uri.toString()
    error.message shouldBe "The content provider gave back no stream to write to."
  }

  @Test
  fun `a failure opening or writing carries its own message and the stream still closes`() {
    every { resolver.openOutputStream(uri) } throws IOException("the provider is gone")
    resolver.writeContentUri(uri) { }?.message shouldBe "the provider is gone"

    val stream = ClosingStream()
    every { resolver.openOutputStream(uri) } returns stream
    val error = resolver.writeContentUri(uri) { throw IOException("the disk filled up") }.shouldNotBeNull()

    error.sink shouldBe uri.toString()
    error.message shouldBe "the disk filled up"
    withClue("a failed write left the stream open") { stream.closed.shouldBeTrue() }
  }

  @Test
  fun `a failure with no message of its own says the destination could not be written`() {
    every { resolver.openOutputStream(uri) } throws IOException()

    resolver.writeContentUri(uri) { }?.message shouldBe "The destination could not be written."
  }

  @Test
  fun `a provider that refuses the caller is unwritable in its own words or ours`() {
    every { resolver.openOutputStream(uri) } throws SecurityException("no grant for this uri")
    resolver.writeContentUri(uri) { }?.message shouldBe "no grant for this uri"

    every { resolver.openOutputStream(uri) } throws SecurityException()
    val error = resolver.writeContentUri(uri) { }.shouldNotBeNull()

    error.sink shouldBe uri.toString()
    error.message shouldBe "Writing the destination was denied."
  }

  private class ClosingStream : ByteArrayOutputStream() {
    var closed = false
      private set

    override fun close() {
      closed = true
      super.close()
    }
  }

  private companion object {
    val BYTES = ByteArray(16) { (it * 3).toByte() }
  }
}
