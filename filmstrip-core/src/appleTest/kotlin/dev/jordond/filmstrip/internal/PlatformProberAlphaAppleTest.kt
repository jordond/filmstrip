package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test

/**
 * What AVFoundation's format description says about a ProRes 4444 track written with an alpha channel.
 */
class PlatformProberAlphaAppleTest {
  @Test
  fun `a prores 4444 clip written with alpha reads as alpha`() =
    runTest {
      val probed = PlatformProber().probe(MediaSource.Bytes(Base64.decode(PRORES_4444_WITH_ALPHA)))

      probed
        .shouldBeInstanceOf<ProbeResult.Success>()
        .info.video!!
        .hasAlpha shouldBe true
    }

  private companion object {
    // One 16 by 16 frame of 0xC82828 at half alpha, written by ffmpeg's prores_ks as yuva444p12le.
    const val PRORES_4444_WITH_ALPHA =
      "AAAAFGZ0eXBxdCAgAAACAHF0ICAAAAAId2lkZQAAAd9tZGF0AAAB12ljcGYAlAABTGF2YwAQABDAAAICAgIAAwQEBAQEBAQEBAQE" +
        "BAQEBAQEBAQEBAQEBAQEBAQEBAQFBAQEBAQEBQUEBAQEBAUFBgQEBAQFBQYHBAQEBAUGBwcEBAQEBAQEBAQEBAQEBAQEBAQEBAQE" +
        "BAQEBAQEBAQEBQQEBAQEBAUFBAQEBAQFBQYEBAQEBQUGBwQEBAQFBgcHQAAAATsAATABMUABAAMACgAEAkvjBfOMCrS2AI9LYAEe" +
        "CMC/sF+v9+v9+v9+v9+v9+v9+v9+C/36/36/36/36/36/36/36/hfr/fr/fr/fr/fr/fr/fr/fgv9+v9+v9+v9+v9+v9+v9+v4X6" +
        "/36/36/36/36/36/36/34L/fr/fr/fr/fr/fr/fr/fr+F+v9+v9+v9+v9+v9+v9+v9+C/36/36/36/36/36/36/36/hfr/fr/fr/" +
        "fr/fr/fr/fr/fgv9+v9+v9+v9+v9+v9+v9+v4X6/36/36/36/36/36/36/34L/fr/fr/fr/fr/fr/fr/fr+F+v9+v9+v9+v9+v9+" +
        "v9+v9+C/36/36/36/36/36/36/36/hfr/fr/fr/fr/fr/fr/fr/fgv9+v9+v9+v9+v9+v9+v9+v8AAACwW1vb3YAAABsbXZoZAAA" +
        "AAAAAAAAAAAAAAAAA+gAAAAiAAEAAAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAItdHJhawAAAFx0a2hkAAAAAwAAAAAAAAAAAAAAAQAAAAAAAAAiAAAAAAAAAAAAAAAA" +
        "AAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAQAAAAEAAAAAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABAAAA" +
        "IgAAAAAAAQAAAAABpW1kaWEAAAAgbWRoZAAAAAAAAAAAAAAAAAAAPAAAAAIAf/8AAAAAAC1oZGxyAAAAAG1obHJ2aWRlAAAAAAAA" +
        "AAAAAAAADFZpZGVvSGFuZGxlcgAAAVBtaW5mAAAAFHZtaGQAAAABAAAAAAAAAAAAAAAsaGRscgAAAABkaGxydXJsIAAAAAAAAAAA" +
        "AAAAAAtEYXRhSGFuZGxlcgAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAORzdGJsAAAAgHN0c2QAAAAAAAAA" +
        "AQAAAHBhcDRoAAAAAAAAAAEAAAAARkZNUAAAAgAAAAIAABAAEABIAAAASAAAAAAAAAABFkxhdmM2My4xLjEwMiBwcm9yZXNfa3MA" +
        "AAAAAAAAAAAAIP//AAAACmZpZWwBAAAAABBwYXNwAAAAAQAAAAEAAAAYc3R0cwAAAAAAAAABAAAAAQAAAgAAAAAcc3RzYwAAAAAA" +
        "AAABAAAAAQAAAAEAAAABAAAAFHN0c3oAAAAAAAAB1wAAAAEAAAAUc3RjbwAAAAAAAAABAAAAJAAAACB1ZHRhAAAAGKlzd3IADFXE" +
        "TGF2ZjYzLjEuMTAy"
  }
}
