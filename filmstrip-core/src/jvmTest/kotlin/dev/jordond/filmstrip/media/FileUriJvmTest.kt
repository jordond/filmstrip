package dev.jordond.filmstrip.media

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The one reading of a `file:` URL every JVM consumer shares: the ffmpeg backend's source, overlay
 * and sink paths, the JVM overlay header read, and the still writer.
 */
class FileUriJvmTest {
  // A picker hands back a URL, not a path, and every consumer opens the name on disk. Anything that
  // reached one still encoded would name a file whose own name holds the percent signs.
  @Test
  fun `decodes an encoded space`() {
    filePathOf("file:///clips/my%20holiday.mp4") shouldBe "/clips/my holiday.mp4"
  }

  // One round of decoding, not two: %2520 is an encoded "%20", so a second pass would turn the
  // percent sign the caller asked for into a space.
  @Test
  fun `decodes exactly once`() {
    filePathOf("file:///clips/100%2520.mp4") shouldBe "/clips/100%20.mp4"
  }

  @Test
  fun `decodes a percent sign`() {
    filePathOf("file:///clips/100%25.mp4") shouldBe "/clips/100%.mp4"
  }

  @Test
  fun `decodes non-ascii as utf-8`() {
    filePathOf("file:///clips/caf%C3%A9.mp4") shouldBe "/clips/café.mp4"
  }

  // The single-slash form is what java.net.URI writes, and the triple-slash form is what a picker
  // hands over. They name one file.
  @Test
  fun `reads both file url forms as the same path`() {
    filePathOf("file:/clips/one.mp4") shouldBe "/clips/one.mp4"
    filePathOf("file:///clips/one.mp4") shouldBe "/clips/one.mp4"
  }

  @Test
  fun `reads the scheme without regard to case`() {
    filePathOf("FILE:///clips/one.mp4") shouldBe "/clips/one.mp4"
  }

  @Test
  fun `refuses a bare path`() {
    filePathOf("/clips/one.mp4") shouldBe null
    filePathOf("/clips/my holiday.mp4") shouldBe null
  }

  @Test
  fun `refuses a scheme this platform cannot open`() {
    filePathOf("http://example.com/clips/one.mp4") shouldBe null
    filePathOf("content://media/external/video/1") shouldBe null
  }

  // file://host/one.mp4 names a file on another machine. Reading the path off it alone would answer
  // /clips/one.mp4, a local file nobody asked for and quite possibly a different one.
  @Test
  fun `refuses a file url naming another host`() {
    filePathOf("file://host/clips/one.mp4") shouldBe null
  }

  // A literal space is not a URI, and a caller holding a path rather than a URL has the path arms to
  // hand it to.
  @Test
  fun `refuses a file url it cannot parse`() {
    filePathOf("file:///clips/my holiday.mp4") shouldBe null
  }

  @Test
  fun `refuses a file url that names no path`() {
    filePathOf("file:clips/one.mp4") shouldBe null
  }
}
