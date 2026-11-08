package dev.jordond.filmstrip.avfoundation.internal.composition

/**
 * The pixel format an SDR clip's frame is converted into on its way to BT.709.
 *
 * This is BGRA on macOS and eight-bit YCbCr on iOS, each the format whose read lands closest to what AVFoundation makes
 * of the same clip under a composition naming BT.709. The simulator reads the same codes and macOS reads within two,
 * until a BT.2020 colour falls far outside BT.709. There macOS's own conversion pulls the colour in where this one
 * clips it, up to 20 codes apart, and a compositor cannot reach that conversion.
 */
internal expect val sdrTransferFormat: UInt
