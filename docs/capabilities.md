# Capabilities

filmstrip is pre-alpha, so here is what works today.

Legend:

| Marker | Meaning                                                              |
| ------ | -------------------------------------------------------------------- |
| ✅     | Implemented.                                                         |
| ⚠️     | Works, with a documented limit. See the footnote.                    |
| ❌     | Not implemented. Refused by name at plan time, never silently wrong. |

Nothing in filmstrip is meant to fail silently. An unsupported effect comes back as
`EffectResolution.Unsupported` with a message, and an unsupported composition comes back as
`Verdict.Incapable` with a list of `ExportError`s.

## Targets

Every published module targets the same list, except the four engine artifacts, each of which only
runs where its engine does, and the three Compose ones, which have no desktop Apple surface in v1.

| Artifact                           | Android | iOS (arm64, sim arm64) | macOS (arm64) | JVM | Browser (js, wasmJs) |
| ---------------------------------- | ------- | ---------------------- | ------------- | --- | -------------------- |
| `filmstrip`                        | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-core`                   | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-effects`                | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-transform`              | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-transform-media3`       | ✅      | ❌                     | ❌            | ❌  | ❌                   |
| `filmstrip-transform-avfoundation` | ❌      | ✅                     | ✅            | ❌  | ❌                   |
| `filmstrip-transform-webcodecs`    | ❌      | ❌                     | ❌            | ❌  | ✅                   |
| `filmstrip-transform-ffmpeg`       | ❌      | ❌                     | ❌            | ✅  | ❌                   |
| `filmstrip-player`                 | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-compose`                | ✅      | ✅                     | ❌            | ✅  | ✅                   |
| `filmstrip-compose-ui`             | ✅      | ✅                     | ❌            | ✅  | ✅                   |
| `filmstrip-test`                   | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-io-filekit`             | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-io-filekit-compose`     | ✅      | ✅                     | ❌            | ✅  | ✅                   |
| `filmstrip-io-okio`                | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-io-kotlinx`             | ✅      | ✅                     | ✅            | ✅  | ✅                   |

Notes:

- `filmstrip-transform-media3`, `-avfoundation` and `-webcodecs` are each named after the engine they
  wrap, and each is single-target for the same reason `-ffmpeg` is: media3-transformer only runs on
  Android, AVFoundation and VideoToolbox only run on Apple, and WebCodecs only runs in a browser.
- `filmstrip-transform-ffmpeg` is JVM-only by design. It shells out to an `ffmpeg` and an `ffprobe`
  that are already on the machine, so it has nothing to offer a target that cannot spawn a process.

## Backends

A backend is registered on the `Filmstrip` builder.

| Backend      | Artifact                           | Registration            | Runs on | Built on                                         |
| ------------ | ---------------------------------- | ----------------------- | ------- | ------------------------------------------------ |
| media3       | `filmstrip-transform-media3`       | `media3Backend()`       | Android | media3-transformer                               |
| AVFoundation | `filmstrip-transform-avfoundation` | `avFoundationBackend()` | Apple   | AVFoundation and VideoToolbox                    |
| WebCodecs    | `filmstrip-transform-webcodecs`    | `webCodecsBackend()`    | browser | WebCodecs, mediabunny                            |
| ffmpeg       | `filmstrip-transform-ffmpeg`       | `ffmpegBackend()`       | JVM     | An `ffmpeg` and `ffprobe` already on the machine |

`filmstrip` registers the right one of these for the current target through `transformBackend()`, and
`playerBackend()` from `filmstrip-player` registers preview and thumbnails. `Filmstrip.create()` does
both. Every one of those calls also registers the built-in effect catalogue, so `builtInEffects()` is
only needed when registering none of them. Registrations go in at the front, so registering a second
engine after the first makes the second one win.

`capabilities()`, `plan()` and `export()` all run for real on every one of them. Per-effect gaps are
listed in the Effects table below.

## Effects

Which built-in effects each render backend can lower. A backend only sees effects whose `RenderApi`
it recognises, and declines the rest so the next resolver gets a look.

| Effect                                  | Android               | Apple             | Browser           | ffmpeg                 |
| --------------------------------------- | --------------------- | ----------------- | ----------------- | ---------------------- |
| `Rotate`                                | ✅                    | ✅                | ❌ [^web-resize]  | ✅                     |
| `Flip`                                  | ✅                    | ✅                | ✅                | ✅                     |
| `Crop` (aspect)                         | ✅                    | ✅                | ✅                | ✅                     |
| `CropRect`                              | ✅                    | ✅                | ✅                | ✅                     |
| `Scale`                                 | ✅ [^android-fit]     | ✅                | ❌ [^web-resize]  | ✅ [^ffmpeg-scale]     |
| `KenBurns`                              | ✅ [^clip-only]       | ✅ [^clip-only]   | ❌ [^pan-pending] | ❌ [^pan-pending]      |
| `Brightness` [^hdr-brightness]          | ✅                    | ✅                | ✅                | ✅                     |
| Colour matrices [^matrix] [^sdr-matrix] | ✅                    | ✅                | ✅                | ✅                     |
| `ImageOverlay` [^hdr-overlay]           | ✅ [^android-overlay] | ✅                | ✅ [^web-image]   | ✅ [^ffmpeg-image]     |
| `TextOverlay` [^hdr-overlay]            | ✅ [^text-raster]     | ✅ [^text-raster] | ✅ [^web-text]    | ❌ [^ffmpeg-text]      |
| Overlay animation                       | ✅ [^media3-anchor]   | ✅                | ✅                | ✅ [^ffmpeg-animation] |

[^matrix]:
    `RgbAdjustment`, `Contrast`, `Saturation`, `HueRotate`, `Sepia`, `Invert` and
    `ColorMatrix`. They lower the same way, so they are one row.

[^clip-only]:
    `KenBurns` is `EffectScope.ClipOnly` and `@ExperimentalFilmstripApi`. The region it
    shows depends on where a frame sits in the clip's span, so a plan refuses it on a track, on the
    composition, and on a clip of a looping track. A `from` or `to` outside the frame, or with no area,
    is refused by name.

[^android-fit]:
    The effect itself only sets a height, and the pipeline applies `Fit` once for the
    whole plan, not once per scale: the plan pins one `Presentation` at the resolved output frame, which
    is also what puts clips of differing sizes on the same frame so they concatenate.

[^web-resize]:
    `Rotate` and `Scale` change the size of the render target rather than adding a pass,
    which makes them pipeline setup. No browser pipeline has landed to set up.

[^ffmpeg-scale]:
    Claimed but contributes no filter node. The size stage is the tail the backend pins
    to the resolved output frame, so the effect that decides that frame emits nothing of its own.
    Claimed rather than declined, because an unclaimed spec is refused by name at plan time.

[^pan-pending]:
    A pan moves the region it shows on every frame. The browser carries one texture
    matrix settled at resolve, and ffmpeg resolves a crop to whole pixels once at plan time so that the
    plan and the export cannot disagree about the frame. Neither has a per-frame form yet.

[^hdr-overlay]:
    On an export that keeps an HDR grade, an opaque overlay texel lands at the light a solid
    fill of its colour lands at, so overlay white is fill white on PQ and HLG alike. Soft edges,
    partial opacity and fades blend with the frame in linear display light. Apple draws both overlays
    that way in Core Image. The browser maps the raster to linear light before uploading it and blends
    it in the compositor. ffmpeg draws the image that way and no text at all. It decodes the picture
    through its own ffmpeg, carries it onto the grade's signal and blends it on the part of the frame
    the overlay covers, on the same float planes and tables a colour matrix on a grade runs on, so it
    writes the exact code values the blend works out. media3 meets this on PQ and falls short on HLG. Every backend draws a picture in the colour its file
    describes, as [Overlay picture colour](#overlay-picture-colour) sets out, so on SDR a tagged
    picture clips at BT.709 and on an HDR grade it keeps its gamut inside BT.2020.

[^android-overlay]:
    On an HDR grade the overlay's texels are rewritten so that media3's own read of them
    lands where the other backends put them, and a PQ grade resolves as SDR does. media3 1.11.1 holds
    an HLG frame as scene light and blends the overlay there, so every overlay on an HLG grade comes
    back `Degraded` with a cause of `DegradationReason.ApproximateAlgorithm` and this message: "On an
    HLG grade media3 blends an overlay into the frame in scene light rather than display light. Opaque
    texels land where a fill of their colour does, and soft edges and partial opacity land a little
    darker than the same blend on the other backends." A picture Android's decoder lands short of its
    file carries a cause for each limit ahead of that one, and the
    [overlay picture limits](#how-a-limit-shows) list each one. Each cause is reported as its own
    `EffectApproximated` adjustment. A run of overlays shares one `OverlayEffect` here, so
    N overlays cost one GL pass. A preview lowers overlays the way its export does. An overlay rules
    out a copy, so on a device with no HDR encoder an HDR composition carrying one tone maps to SDR on
    export, and its preview tone maps the same way, even on an HDR panel. media3 uploads each overlay
    as one texture, so a picture or text longer on a side than the device's OpenGL ES
    `GL_MAX_TEXTURE_SIZE` is refused by name. `RenderCapabilities.maxTextureSize` is that side, probed
    from the device and never below OpenGL ES 3.0's 2048.

[^text-raster]:
    Text is rasterised at resolve against the frame an export writes and only resampled
    after that, so a preview and its export break lines on the same words. A device that reports no
    `RenderFeature.TextRendering` refuses by name, and so does text and a style that leave nothing to
    draw. A plate's margin is a share of the cap height on every backend, so a plate on Android comes
    out smaller than it did when its margin was a share of the font size. Every backend stores the
    plate's straight colour at sixteen bits a component, so a translucent plate and the soft edges of
    its glyphs keep their colour on an HDR frame. Apple draws the whole raster at sixteen bits. Android
    and the browser draw how much of each texel the glyphs cover and lay the plate and text colours
    from their straight values, so there a colour font's glyphs, such as an emoji, keep their own colour
    at the eight bits the platform draws them in, and a glyph texel within three premultiplied
    eight-bit steps of the text colour takes the text colour exactly. Text is refused by name before
    its raster is made when the raster, the floats an HDR frame draws it from and what is held on the
    way would pass 1 GiB. Android and the browser count that at 40 bytes a texel, and Apple counts its
    raster, its floats and the band of rows it draws through. Apple also refuses text in a face whose
    line stands taller than 10,000,000 pixels, the tallest line Core Text lays out. Android also refuses
    a raster past half of the app's Java heap, so how much text fits there depends on the device.
    Android and the browser refuse a raster longer on a side than the texture it uploads to holds,
    OpenGL ES's `GL_MAX_TEXTURE_SIZE` on Android and WebGL's `MAX_TEXTURE_SIZE` in the browser. Text
    that fits all of those but that the platform cannot allocate is refused saying so on Android, Apple
    and js. On wasmJs only the canvas read is refused that way, since wasm traps on an array it cannot
    allocate and a trap cannot be caught.

[^web-image]:
    The image is decoded before planning, so one the browser cannot decode is refused by
    name at plan time. An `ImageSource.Path` is refused by name as well, since a page cannot read a file
    path, so pass the image as bytes or a URI. Chrome decodes no HEIC, so a HEIC overlay is refused
    there.

[^ffmpeg-image]:
    An AVIF or HEIC keeps the alpha its file carries, a grid one included, and a limited range
    alpha is expanded to full range. Only ffmpeg straightens a picture whose file records its colour
    as premultiplied with an `iref prem`. Apple and Android take the alpha their platform decoders give.
    ffmpeg 6.1 lists no stream for an AVIF's alpha item, so there the overlay draws opaque and comes
    back `Degraded` with a cause of `DegradationReason.AlphaDiscarded`. ffmpeg 6.1 opens no HEIC at all.

[^web-text]:
    Text is rasterised at resolve against the frame an export writes, as on Android and
    Apple. Lines break at spaces and at every `\n`, and a word wider than a whole line is split
    between graphemes. Core Text and `StaticLayout` follow the Unicode line breaking rules, which also
    break after a hyphen and between CJK characters, so such a caption can wrap on different words here
    than on Android or Apple. A preview still breaks exactly where its own export does. Text longer
    on a side than the page's WebGL `MAX_TEXTURE_SIZE` is refused by name, and
    `RenderCapabilities.maxTextureSize` is that side, read from the page and never below WebGL2's
    2048.

[^hdr-brightness]:
    The factor is a multiply on an encoded SDR signal. On an export that keeps an HDR
    grade it is read as the display light ratio an SDR display would have produced, so a frame looks the
    same graded or not. Each backend applies that in the domain it holds the frame in: ffmpeg and Core
    Image scale display light, and media3 scales scene light on HLG because it runs only the inverse
    OETF there. A factor above `1f` matches an SDR export through the midtones and diverges in the
    highlights, where SDR clips at white and HDR keeps going until the transfer function's own peak.

[^sdr-matrix]:
    Every colour effect other than `Brightness` is a 3x3 matrix and an offset on the
    encoded SDR signal, and a run of them is folded into one matrix in shared code before any backend
    clamps, so a channel pushed past white and pulled back again comes out the same everywhere. A clip's
    run and the composition's are two runs, not one, and every backend clamps between them, the way it
    would if the clip had been written out and read back. On an export that keeps an HDR grade the frame
    is linear light, so each backend reads that light as the signal an SDR display at reference white
    (203 nits) would have been fed, runs the matrix there, and takes the result back to light, floored
    at black and clamped where the transfer function runs out rather than at white. A matrix with no
    offset reads the same however bright the picture is, so only `Contrast`, `Invert` and a hand-written
    offset are anchored to that white: a contrast pivots on about 44 nits, and a full inversion turns
    anything brighter than 203 nits black. Android lowers the SDR matrix onto media3's `RgbMatrix` and a
    kept grade onto a GL pass of its own, Apple onto `CIColorMatrix` inside the same tone curve pair
    `Brightness` uses on SDR and between a pair of `CIGammaAdjust` filters in linear BT.2020 on a grade,
    since Core Image's working space carries sRGB primaries and would floor a saturated BT.2020 channel,
    the browser onto one `mat4` uniform, wrapped on a grade in the same GLSL body media3's pass runs,
    and ffmpeg onto `lutrgb` when the matrix is per-channel and onto a two-point `lut3d` cube otherwise,
    which reproduces an affine map exactly and clamps once at the end. The per-channel table rounds to
    the nearest code value in its own expression. The cube cannot, and `lut3d` keeps the code value
    below the one it interpolates, so on SDR a matrix that mixes channels can land one code value under
    the other three backends on ffmpeg. On a grade ffmpeg works on the grade's own ten-bit Y'CbCr. It
    carries luma, Cb and Cr onto float planes one plane at a time, turns them into the signal with a
    `lut1d` table and a `colorchannelmixer`, takes the signal to the SDR signal and back through
    `lut1d` tables that hold the transfer function, and writes the codes with a second mixer and a
    table that rounds each one to the nearest whole code. A per-channel matrix is folded into the
    transfer table, a mix runs on `colorchannelmixer`, and only a mix with an offset runs as a
    two-point `lut3d`, since the mixer has no offset of its own. Every step in between runs in float,
    so a grade lands on the exact code values the matrix and transfer work out. That costs a float copy
    of the frame and seven to nine table and mixer passes over it, and each table travels as a sidecar
    file of up to 65,536 entries per channel. PQ agrees across
    backends to the code value. HLG does not quite: Core Image's opto-optical transfer keeps chroma but
    is not the per-channel one media3 and ffmpeg apply, so an offset or a mix on a saturated HLG colour
    lands a few percent apart between Apple and the other two, and grey agrees everywhere.

[^ffmpeg-text]:
    Refused for one of two reasons, and the message says which. Either the ffmpeg build
    has no `drawtext` filter, which needs `--enable-libfreetype` and `--enable-libharfbuzz` and the
    common prebuilt packages leave out, or it has one and `drawtext` breaks lines only on a literal
    newline, so `TextStyle.maxWidth` cannot be honoured. Text layout has to be exact, so it is refused
    rather than rendered differently.

[^media3-anchor]:
    Both frame anchors are held in `-1f..1f`, which is the range media3 documents on
    `StaticOverlaySettings.Builder` and which implementing `OverlaySettings` directly walks past rather
    than lifts. An overlay animated off the frame stops at the edge here and keeps going on Apple and
    ffmpeg.

[^ffmpeg-animation]:
    The animation is sampled once per output frame of the run and written into a
    `sendcmd` sidecar, so the alpha, the size and the position are driven mid-stream rather than
    settled once. Each of them is written as the intervals it holds one value over, which sends a
    command only where that value moves and still covers every frame of the run, so a branch opened
    part way through reads what it is holding there. It needs a frame rate to sample onto and a
    closed run to sample across, and refuses by name without either. It also reads the picture's size,
    to size the drawn picture off its own pixels. The size comes from the picture's decode when the
    resolve already decoded it, and otherwise from the backend's own ffmpeg, which decodes one frame of
    it and reads the size that frame comes out at, so a picture animates wherever the ffmpeg build
    decodes it, WebP and HEIC included. ffmpeg draws no `TextOverlay` at all,
    so this is `ImageOverlay` alone.

### Overlay picture colour

An `ImageOverlay` picture is drawn in the colour its file describes. Every backend reads the
picture's stored samples with no colour conversion of its own, and filmstrip turns them into colour
from the description it reads out of the file, so one file lands on one colour on every backend, in
a preview as in its export.

| Format              | Descriptions read, the first one understood wins                |
| ------------------- | --------------------------------------------------------------- |
| PNG                 | `cICP`, then `iCCP`, then `sRGB`, then `cHRM` and `gAMA`        |
| HEIF, HEIC and AVIF | an ICC profile in `colr`, then `colr nclx`                      |
| JPEG, WebP and TIFF | an embedded ICC profile                                         |
| JPEG XL             | the description the backend's decoder reads from the codestream |

A description filmstrip does not understand, or one for another number of channels than the picture
stores, gives way to the next one in the list. So does CICP whose primaries or transfer is 2, for
unspecified, and a PNG `cICP` at narrow range, since filmstrip does not expand its samples. A PNG
`gAMA` of 45455 with BT.709 chromaticities or no `cHRM` at all is sRGB, a `gAMA` alone takes sRGB's
primaries, and a `cHRM` alone takes sRGB's curve. A HEIF file reads its primary item's own `colr`
before a tile's. An ICC profile is read when it is a matrix and curve RGB profile, a LUT profile or
a grey one. CICP is read for primaries 1, 4 to 12 and 22, and for transfers 1, 4 to 8, 11 and 13 to
18, at full or limited range in HEIF, HEIC and AVIF. Any other CICP code point ends the search and
the picture draws untagged. A JPEG XL draws only where there is a decoder for it, Apple's ImageIO and
an ffmpeg built with libjxl. Android and Chrome decode none, so it is refused there.

An untagged picture, or one tagged sRGB, draws its stored codes on SDR, and on an HDR grade lands at
the light a fill of the same colour lands at. A tagged picture lands where its description puts
it:

- **SDR** converts it to BT.709 and clips each channel to the edge of BT.709, as a colour management
  module does for a colour SDR video has no code for. A Display P3 red comes out as BT.709's own red.
- **An HDR grade** puts the picture's white at reference white (203 nits) and keeps its gamut inside
  BT.2020, so a Display P3 or Adobe RGB picture keeps the colours SDR clips. Only a colour outside
  BT.2020 itself clips.
- **A PQ or HLG picture** keeps its light on an HDR grade, a PQ one at the nits it stores and an HLG
  one at the light its curve gives each texel. On SDR that light is read against reference white
  and clipped.
- **A sixteen-bit picture** keeps all sixteen bits through the conversion, on every backend that
  decodes it at that depth.

#### AVIF and HEIC

An AVIF or HEIC stores YCbCr. Its `nclx` box records the matrix and range the samples are coded
with, and its codestream can record them again. Several platform decoders read the codestream rather
than the box, or pick a default of their own when the codestream says nothing. So before any decoder
sees the picture, filmstrip rewrites the codestream of the primary item and the tiles it is built
from to state what `nclx` records, and the picture decodes the way the box says everywhere. A
thumbnail or an alpha item keeps its own, and a HEIF image sequence, which carries a `moov` box, is
left as it is.

An AVIF or HEIC with no `nclx` lands by its codestream's own signal, AV1's colour config or HEVC's
VUI. A codestream that leaves its matrix out or names 2 for unspecified reads as BT.601, and a range
it leaves out reads as limited. The rewrite states that, so no decoder picks its own. A picture with
no `nclx` whose codestream filmstrip cannot read is left to the platform decoder's own reading.

AV1 and HEVC allow the identity matrix only on 4:4:4 samples, so a picture naming it on samples not
known to be 4:4:4 is refused on every backend with the same reason: "The picture names the identity
matrix on samples not known to be 4:4:4, the only ones AV1 and HEVC allow it on."

#### The YCbCr matrix

Each ITU-T H.273 matrix coefficients code point has one reading in filmstrip, and every backend
lands a picture by it. A backend lands a code point one of three ways. Its decoder reads a matrix
with the same weights, and the picture is restated to it. Its decoder hands back the stored samples
untouched, and filmstrip turns them into colour itself. Or neither works, and the picture lands
through the nearest matrix the decoder does read.

| Matrix                         | Read as                                                        | ffmpeg      | Apple                                                  | Android                                                                                        | Browser                                                                 |
| ------------------------------ | -------------------------------------------------------------- | ----------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| 0, identity                    | green, blue and red as stored, on 4:4:4 samples only           | exact       | exact at 8 and 10 bits, nearest at 12                  | unsupported, no API level reads it                                                             | exact                                                                   |
| 1, BT.709                      | Kr 0.2126, Kb 0.0722                                           | exact       | exact                                                  | exact from API 33 at full range and from API 34 at limited, 35 for a ten-bit AVIF, else nearest | exact at full range, Chrome's drift at limited                          |
| 2, unspecified                 | as 6                                                           | exact       | exact                                                  | exact                                                                                          | exact                                                                   |
| 4, FCC                         | Kr 0.30, Kb 0.11                                               | exact       | exact on 4:4:4 at 8 and 10 bits, else nearest          | exact in an AVIF from API 36, else nearest                                                     | exact                                                                   |
| 5 and 6, BT.601                | Kr 0.299, Kb 0.114                                             | exact       | exact                                                  | exact                                                                                          | exact                                                                   |
| 7, SMPTE 240M                  | Kr 0.212, Kb 0.087                                             | exact       | as 4                                                   | as 4                                                                                           | exact                                                                   |
| 8, YCgCo                       | YCgCo                                                          | exact       | as 4                                                   | exact in an eight-bit full range AVIF from API 36, else unsupported                           | exact at full range, at limited exact on eight-bit 4:4:4, else unsupported |
| 9, BT.2020                     | Kr 0.2627, Kb 0.0593                                           | exact       | exact                                                  | exact from API 33, else nearest                                                                | exact at full range, Chrome's drift at limited                          |
| 10, BT.2020 constant luminance | H.273's constant luminance inverse, through the transfer's OETF | exact       | as 4                                                   | nearest                                                                                        | exact on eight-bit 4:4:4, else through 9                                |
| 12, derived from primaries     | Kr and Kb from the primaries, as a named matrix when one matches | exact       | as the named matrix, else as 4                         | as the named matrix, else nearest                                                              | as the named matrix, else exact                                         |
| 13, derived constant luminance | as 10, with Kr and Kb from the primaries                       | exact       | as 4                                                   | nearest                                                                                        | exact on eight-bit 4:4:4, else through 1 or 9                           |
| 3, 11, 14, and 15 and up       | refused                                                        | unsupported | unsupported                                            | unsupported                                                                                    | unsupported                                                             |

11 is YDzDx and 14 is ICtCp, which filmstrip does not read, and 3 and every code point past 14 are
reserved. 12 and 13 are refused on primaries filmstrip has no chromaticities for, and 10 and 13 are
refused beside the PQ or HLG transfer. ffmpeg needs the sample depth to turn stored samples into
colour, so a picture whose matrix swscale has no name for, in a file that does not state its depth,
is unsupported there. A constant luminance picture taking the nearest matrix lands through the
non-constant one with the same weights, and on the logarithmic transfers 9 and 10 that lands past
the bound. On the browser 13 lands through 1 or 9 instead, through 1 on Display P3 primaries.

A nearest landing is measured by encoding every red, green and blue at 17 evenly spaced levels with
the picture's own matrix and decoding them through the other one, at the picture's range or at the
range the decoder moves it to. Both are read as sRGB. When the mean CIE 1976 colour difference is 12
or less the overlay is drawn and comes back `Degraded` with `DegradationReason.ColorSpaceConverted`.
Past 12 it is unsupported. Either message names the decoder and the matrix it lands through, and ends
the same way on every backend, like "a mean colour difference of 6.8 dE76, with one channel up to
41.2 8-bit codes out", the worst channel measured over 33 levels.

#### How a limit shows

A limit of a platform's decoder or encoder that still lets the overlay draw comes back as
`EffectResolution.Degraded`. Its `causes` list every way the picture falls short, each a
`Degradation` with a `DegradationReason` and a message naming the platform, and the plan reports
each cause as its own `EffectApproximated` adjustment. On Android the decoder's limits come first and
the HLG blend last. On the browser the matrix comes before the eight-bit decode. A limit that keeps the picture from landing as its file records, past the bound
or at all, comes back as `EffectResolution.Unsupported` with the reason. A limit that moves a colour
by a few codes is listed here with its size and is not reported.

| Backend | Limit                                                                                                                                                                    | How it shows                                                                                                         |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------- |
| Android | No HEIC decoder before API 28 and no AVIF decoder before API 31                                                                                                          | Unsupported, naming the formats each API level decodes                                                               |
| Android | The matrices each API level reads, in the table above. HEIC was measured on API 30, 31, 33, 34, 35 and 36 and AVIF on 31, 33, 34, 35 and 36. API 28 and 29 are taken to read as 30 does and 32 as 31 does | `Degraded`, `ColorSpaceConverted`, through the nearest matrix, or unsupported past the bound                        |
| Android | Before API 33 the AVIF decoder reads every AVIF at limited range                                                                                                         | A full range AVIF is `Degraded`, `ColorSpaceConverted`, and its landing is measured with the range moved as well     |
| Android | On API 33 the AVIF decoder hands back no frame for a ten-bit AVIF                                                                                                        | Unsupported                                                                                                          |
| Android | The AVIF decoder draws a twelve-bit AVIF only from API 36, and then at eight bits a sample. API 31, 33 and 34 hand back no frame and API 35 the wrong colours              | Unsupported on API 31 to 35, `Degraded` with `PrecisionReduced` from API 36. No AVIF decodes below API 31          |
| Android | The HEIC decoder hands back no frame for a twelve-bit HEIC, measured on API 30, 31 and 33 to 36                                                                          | Unsupported                                                                                                          |
| Android | API 24 and 25 have no half float bitmap, so a sixteen-bit PNG is cut to eight bits                                                                                        | `Degraded`, `PrecisionReduced`                                                                                       |
| Android | A sixteen-bit picture decoded into a colour space other than extended sRGB or its linear form                                                                            | Unsupported                                                                                                          |
| Apple   | ImageIO turns a HEIF's YCbCr into whole codes at the depth it decodes to, eight bits for an eight-bit picture, ten for ten-bit and sixteen for twelve-bit | On macOS an eight-bit sample sits up to 1.39 codes from its H.273 reading on the HEIC fixtures, ImageIO's own drift |
| Apple   | An AVIF decode, and a HEIC decode on the iOS simulator, reads full range chroma over the 254 codes CoreVideo spans rather than 255 | Up to a third of an eight-bit code from its H.273 reading on the fixtures, and most of a code at the chroma extremes |
| Apple   | The matrices ImageIO reads, in the table above                                                                                                                           | `Degraded`, `ColorSpaceConverted`, through the nearest matrix, or unsupported past the bound                        |
| Apple   | ImageIO hands out no samples for a twelve-bit HEIC at limited range or a grey HEIC with alpha                                                                            | Unsupported                                                                                                          |
| Apple   | The iOS simulator decodes a limited range AVIF or HEIC by moving each code to full range, rounded down                                                                   | On the simulator only                                                                                                |
| Apple   | An iPhone's HEIF decode                                                                                                                                                  | Unmeasured                                                                                                           |
| Apple   | A HEIF coded with H.264 goes through Core Image's own decode                                                                                                             | Unmeasured                                                                                                           |
| Apple   | VideoToolbox's lossy encode rings into the middle of a small overlay                                                                                                     | Measured up to 4.8% of the chroma step between the overlay and the frame behind it                                   |
| ffmpeg  | swscale converts YCbCr deeper than eight bits to sixteen-bit RGB at 255/256 of full scale at limited range and 1023/1024 at full                                          | Up to four ten-bit codes low. A picture filmstrip turns into colour from its stored samples has no shortfall         |
| ffmpeg  | ffmpeg 6.1 has no HEIF demuxer                                                                                                                                           | A HEIC is unsupported                                                                                                |
| ffmpeg  | ffmpeg 6.1 lists no stream for an AVIF's alpha item                                                                                                                      | `Degraded`, `AlphaDiscarded`, drawn opaque                                                                           |
| ffmpeg  | An AVIF or HEIC the build has no decoder for                                                                                                                             | Unsupported                                                                                                          |
| Browser | Chrome decodes no HEIC                                                                                                                                                   | Unsupported at plan time                                                                                             |
| Browser | The matrices Chrome reads, in the table above                                                                                                                            | `Degraded`, `ColorSpaceConverted`, through the nearest matrix, or unsupported past the bound                        |
| Browser | Chrome turns a limited range BT.709 or BT.2020 AVIF into RGB up to six eight-bit codes from its H.273 reading, on colours inside the gamut. That holds for 12 on those primaries too | Drawn as Chrome decodes it                                                                                           |
| Browser | `createImageBitmap` decodes to eight bits a channel. Only a PNG is read at its full depth, through filmstrip's own PNG reader                                              | A ten or twelve-bit AVIF is `Degraded`, `PrecisionReduced`, drawn from its eight-bit decode                          |
| Browser | Safari                                                                                                                                                                   | Unmeasured                                                                                                           |
| Browser | A PNG's inflated rows, its sixteen-bit samples and the eight-bit bitmap drawn from it must fit in 1 GiB, and its longest side in the page's WebGL `MAX_TEXTURE_SIZE`. A 48 megapixel PNG fits the 1 GiB at eight or sixteen bits, and draws only on a page whose texture holds its longest side | Unsupported past either, the reason naming the picture's size and the 1 GiB or the texture side |

ffmpeg was measured on 6.1.1 and 9.0.2, and keeps a twelve-bit AVIF, and a twelve-bit HEIC where its
build opens HEIF, deeper than eight bits. Chrome was measured headless.

Apple decodes a HEIC, HEIF, HEIF sequence or AVIF once, through ImageIO, when the overlay is read,
and draws those samples on every frame, so it reads the same codes every time. The decode is held
for as long as the overlay is, four bytes a texel for an eight-bit picture, so a 48 megapixel still
holds 192 MB. Every read and draw counts what it holds at once against one 1 GiB ceiling, the decode
it works from, what it holds on the way and what it makes. Copying the samples out of ImageIO counts
the decode beside the copy, and for a grey picture the RGBA written from it too, and a picture past
the ceiling is unsupported. Drawing a tagged picture at its colour also makes premultiplied floats,
sixteen bytes a texel, a band of rows at a time. A picture whose codes, floats and band would count
past 1 GiB, or whose floats cannot be allocated, is refused only when a draw has to convert it. The
export then fails with `ExportError.UnsupportedEffect` carrying the reason, stopping at the next
sample the writer moves after the frame that drew it. A preview stops there instead, with
`PlaybackStatus.Error` and `PlaybackEvent.Failed` carrying `PlaybackError.UnsupportedFormat` with
the same reason, and draws that frame and each one after it without its effects. An untagged picture on an SDR frame, or one
whose colour comes out at its own eight-bit codes, holds no floats and draws at any size. A picture
filmstrip turns into colour from its stored samples is held to the same 1 GiB when it is read, and
is unsupported past it. A 48 megapixel picture counts 768 MB read that way. Drawn at its colour an
eight-bit one counts 1,010 MB and draws, and a sixteen-bit or evaluated one, eight bytes a texel of
codes, counts 1,202 MB and is refused. On an Apple M4 Max under macOS 26.6.2, in a debug build, drawing an eight-bit one tagged
Display P3 peaked at 1,252 MB on an SDR frame, its decode included, and each image took 31 to 35 s
to make.

Some descriptions are not read at all, and a picture carrying only one of them draws untagged: TIFF's
WhitePoint, PrimaryChromaticities and TransferFunction tags, a JPEG's Exif ColorSpace, a BMP V4 or V5
colour space, and gain maps. A CMYK or Lab picture draws untagged as well.

A picture's orientation is not read the same way everywhere yet. ffmpeg turns a picture by its EXIF
orientation. The browser turns a JPEG by it and draws a PNG's `eXIf` as stored. Apple and Android
draw every picture as stored, whatever its orientation says.

### Overlay animation

`ImageOverlay` and `TextOverlay` both take an `animation`, and `OverlayEffect` declares it, so an
overlay of your own carries one without being rewritten. An `OverlayAnimation` is asked once per
output frame for an `OverlayFrame`, which holds an opacity, an `OverlayOffset` and a scale. The
`OverlayTime` it reads carries `elapsed`, `remaining` and the frame's own `composition` time.

`fadeIn`, `fadeOut` and `fade` ramp the opacity, `slideIn` and `slideOut` travel the offset, and
`plus` runs two animations over the same frame, multiplying the opacities and the scales and adding
the offsets. Every built-in ramp is linear. Anything else is a lambda, since `OverlayAnimation` is a
`fun interface`.

`elapsed` and `remaining` are counted from the run the overlay is drawn over, which is
`visibleDuring` clipped to the scope the effect was added at, or that scope itself when no window was
named. An overlay carrying no window is drawn on every frame it is handed, so a backend whose own
timestamp lands on either bound of the scope cannot drop a frame nothing asked to hide. A run with no
end reads `remaining` as infinite, and a ramp measured off an infinite remainder never begins, so a
`fadeOut` holds full opacity and a `slideOut` holds its authored position.

The offset is a fraction of the frame the overlay is placed against, positive x towards the end edge
and positive y down, the same origin every other normalised measurement here uses. An offset large
enough to carry the overlay off the frame is allowed, and the part that leaves is not drawn. The
scale multiplies the drawn size about the point the overlay is anchored by, so a corner watermark
keeps its margin and grows inward. On a `TextOverlay` it resamples the raster: the glyphs are laid
out once at resolve so that a preview and its export break lines on the same words, and an animation
never re-lays them.

An animation is compared, since two overlays that draw different pictures must not share a cached
frame, so a caller holds one instance and hands the same one back rather than rebuilding it. The
built-in helpers compare by value and survive a rebuild. A freshly written lambda compares by
identity and misses the thumbnail cache every time the composition is rebuilt.

Each backend drives it its own way. Apple samples inside the Core Image step and scales the alpha
row of a colour matrix alone, since the filter unpremultiplies and premultiplies around it and
scaling the colour rows as well draws the overlay at the square of the alpha it was given. media3
rewrites one `OverlaySettings` for the frame being drawn rather than holding a prebuilt one. ffmpeg
samples onto the output frame grid at resolve and drives named `colorchannelmixer`, `scale` and
`overlay` nodes from its sidecar, and loops the overlay image's own branch at the output rate for as
long as the branch it merges onto, so a watermark is drawn for the whole of its run rather than on
its first frame alone. The window is gated on that same branch, by zeroing the alpha outside the
run, so it is read against the clock the sidecar's commands are written against rather than against
the clip's. An overlay added to a clip merges onto that clip's own branch, ahead of the tail that
resamples it, so on a clip whose own rate differs from the output's the window's edges land within
one output frame of where the shared sampler puts them. The browser samples the overlay on every
frame it draws, at the composition time its draw call already carries, and draws it as one textured
quad at the rect and opacity that sample answers.

### Parity

`parityOf(specId)` says how closely a preview matches its export, and every backend keeps its own
table.

| Backend | Exact                                             | Approximate   |
| ------- | ------------------------------------------------- | ------------- |
| Android | everything it lowers except `TextOverlay`         | `TextOverlay` |
| Apple   | everything it lowers except `TextOverlay`         | `TextOverlay` |
| ffmpeg  | everything it lowers except `Scale`               | `Scale`       |
| Browser | everything it lowers                              | none          |

`TextOverlay`'s approximation is glyph antialiasing only: line breaks, metrics and measured extent
are exact on both platforms. The browser's preview draws the same raster its export draws, so its
text is exact. `Scale` on ffmpeg goes through swscale's bicubic kernel, which is not
the one a preview resamples with. Anything with no entry answers null from `parityOf`, and the plan
reads a null as `Exact`.

An overlay animation is outside what these tables report, since the spec `parityOf` is asked about is
the same one animated or not. On ffmpeg the opacity, offset and scale it is drawn with, and the
window `visibleDuring` names, match the export on a scrubbed preview as well as on one read from the
top. An overlay image seeked into delivers nothing at all, so the overlay's own branch is carried to
the scrub with `-itsoffset` and the move is taken back off by a `setpts` before the merge. Ahead of
that `setpts` the branch reads the composition times the sidecar's commands are written against, and
the window is gated there rather than on the merge, which reads the clip's own clock instead.

## Codecs

What each backend encodes.

| Codec                   | Android | Apple | Browser       | ffmpeg                        |
| ----------------------- | ------- | ----- | ------------- | ----------------------------- |
| H.264                   | ✅      | ✅    | ✅            | ✅ libx264, h264_videotoolbox |
| HEVC                    | ✅      | ✅    | ✅            | ✅ hevc_videotoolbox, libx265 |
| VP9                     | ❌      | ❌    | ✅            | ✅ libvpx-vp9 [^ffmpeg-vp9]   |
| AV1, VP8                | ❌      | ❌    | ❌            | ❌                            |
| AAC                     | ✅      | ✅    | ⚠️ [^web-aac] | ✅                            |
| ALAC                    | ❌      | ❌    | ❌            | ✅                            |
| Opus, MP3, FLAC, Vorbis | ❌      | ❌    | ❌            | ❌                            |

`AudioCodec` and `VideoCodec` declare more than any backend writes. A codec the device has no
encoder for falls back to the next rung and reports a `CodecFallback`, or refuses under
`ExportSpec.strict`. `VideoCodec.Auto` walks H.264 then HEVC on Android, Apple and ffmpeg, and
H.264, VP9 then HEVC in the browser.

Below iOS 17.4 and on the iOS simulator an Apple `VideoEncoderCapability.isHardwareAccelerated` is
null, since VideoToolbox does not say there whether an encoder is hardware.

An Android encoder can write another matrix than it is asked for. Android's own software H.264 and
HEVC encoders were measured writing BT.601 at limited range when asked for SDR BT.709 at limited
range: `OMX.google.h264.encoder` on API 30 to 32, and `c2.android.avc.encoder` and
`c2.android.hevc.encoder` on API 31 and 32. The c2 encoders write BT.709 on API 33 and 36. Those
encoders ship in an updatable Mainline module, so the export does not go by name or API level.
The first SDR export through an encoder in a process encodes one green frame through it and reads
the codes back. Frame sizes are measured in three tiers, up to 720x576, up to 1920x1080 and above,
each at the largest size in the tier the encoder takes, and the answer is kept per tier for that
process. A portrait frame is measured in the tier of the landscape frame it turns into. media3
picks the encoder while the export runs, so the plan cannot see it. An encoder measured writing
another matrix or range is made again by name with that one, so its colours decode as authored,
and the export reports a `ColorSpaceChanged` on `ExportStatus.Success`.
Android's `MediaFormat` names the primaries and the matrix together, so a file tagged BT.601
records BT.470 BG primaries as well, and a colour-managed player can show greens a shade apart. A
`colr` box stating BT.709 primaries beside a BT.601 matrix reads back through Android's own
`MediaExtractor` as colour standard 86, outside `MediaFormat`'s constants, and filmstrip's `probe`
reads that as `ColorSpace.Unknown`, so the file keeps BT.470 BG. `ExportSpec.strict` cannot refuse
it, since the plan said `Capable`. An encoder whose codes the probe cannot read, or that encodes no
size in the tier, keeps what media3 asked for, and that answer is kept too. A measurement that runs
past five seconds keeps the request until the next export measures again, and so does a probe that
throws, as it does when a hardware encoder has no instance free beside the one media3 already holds.
The reason is logged under the `filmstrip-media3` tag. An encoder made again by name that fails,
or comes back as another encoder, fails the export rather than writing a tag its codes do not
match.

[^ffmpeg-vp9]:
    Reported when the build carries libvpx, and reachable only by naming
    `VideoCodec.Vp9`. ffmpeg's `Auto` ladder leaves VP9 out on purpose.

[^web-aac]:
    Chrome hands AAC encoding to the platform rather than carrying an encoder of its own,
    so it is there on macOS and Windows and absent on Linux. `capabilities()` probes for it instead of
    assuming it, and a browser that lists no audio encoder resolves the plan's audio codec to `None`,
    so the export writes video on its own. Decoding is built in everywhere, which is why a stream copy
    carries a source's AAC across on a browser that can write none.

### HDR

`HdrMode` is resolved once up front, and both preview and export use that decision. An SDR source
resolves to "keep" whatever `HdrMode` asked for: there is no grade to map, and claiming otherwise
would cost the stream copy, since a platform told to tone-map has to decode every frame to do it.

Keeping a grade needs a 10-bit profile, so the export is pinned to HEVC Main 10 on Android, Apple and
ffmpeg, and to VP9 Profile 2 in the browser, which is the only HDR profile any browser encoder there
was measured to take. Asking for another codec as well reports a `CodecFallback`. Android reports
Main 10 support from the encoder list, Apple opens a probe session, ffmpeg encodes one frame at
`main10`, and the browser asks `VideoEncoder.isConfigSupported` and also checks it can render float.

Tone mapping down to SDR is always available on Android and Apple. ffmpeg needs `zscale`
(`--enable-libzimg`) or `libplacebo` and says which is missing. The browser cannot tone map at all,
because its single WebGL pass has no tone-map stage, so an HDR source whose grade it cannot keep is
refused rather than written into an SDR file.

The browser's HDR encode is software only, so it runs on the CPU however capable the machine is, and
so does the decode: the only decoder that hands out a readable ten-bit frame there is the software
one. Whether a source's grade can be kept is therefore a question about the source rather than about
the device, asked once per clip at plan time. An HEVC Main 10 source, which is what an iPhone
writes, has no software decoder in any browser measured, so its grade survives only through a stream
copy of an untouched clip. Anything that has to be re-encoded is refused by name. [^web-hdr]

[^web-hdr]:
    The browser reads a kept grade as linear display light with reference white at one, and
    it runs HLG's opto-optical transfer per channel on the way in, which is the reading media3 and
    ffmpeg apply and which puts the three of them on the same code value. It holds the frame at half
    float and packs the result straight into the bytes of a ten-bit frame, so nothing goes through an
    eight-bit surface between the decoder and the encoder. A preview still draws an eight-bit picture:
    the composited light is clipped at reference white and put through the SDR display curve, which is
    what the browser's own upload of an HDR frame shows today.

## Compositions

What each export backend accepts. Extra tracks, looping and gain are covered under
[Audio overlays](#audio-overlays).

| Feature                               | Android          | Apple            | Browser          | ffmpeg            |
| ------------------------------------- | ---------------- | ---------------- | ---------------- | ----------------- |
| One video track, clips end to end     | ✅               | ✅               | ✅               | ✅                |
| Clip trim                             | ✅               | ✅               | ✅               | ✅                |
| Per-clip and per-track effects        | ✅               | ✅               | ✅               | ✅                |
| Composition-level effects             | ✅               | ✅               | ✅               | ✅                |
| `Fill.Solid` behind bars and gaps     | ✅               | ✅               | ✅               | ✅                |
| `Fill.Blurred`                        | ✅               | ✅               | ✅               | ⚠️ [^ffmpeg-blur] |
| `AudioSpec.Keep`, `Mute`, `Volume`    | ✅               | ✅               | ✅               | ✅                |
| `AudioSpec.Remove`, `AudioCodec.None` | ✅               | ✅               | ✅               | ✅                |
| `AudioSpec.AudioOnly`                 | ✅ [^audio-only] | ✅ [^audio-only] | ✅ [^audio-only] | ✅ [^audio-only]  |

On every backend, thumbnails and frames read back inside a gap included, a `Fill.Solid` is painted
after every composition effect and before every composition overlay, so it keeps its own colour
wherever no overlay covers it and an overlay placed in a bar or a gap draws there.

`ExportPath` declares `TrimOptimized`, but no backend resolves to it today: a plan is `Transmux`
when the whole thing can be stream-copied and `Transcode` otherwise, and a snapped trim is a
`Transmux` like any other copy.

A copy needs more than a reachable cut. `ExportSpec.targetHeight`, `bitrate`, a named codec, any
effect, a second clip, a non-unity gain and a fade each take an export off the copy path on their
own, and `ExportSpec.Upload` trips three of them at once. `ExportPlan.copyBlockedBy` lists every term that
applied, so a caller can see which field to drop rather than guess why a three second export took
forty.

[^ffmpeg-blur]:
    Needs `gblur`, and needs `colorchannelmixer` as well when `Fill.Blurred.dim`
    actually darkens the background. A build missing either refuses by name and says which filter is
    absent.

[^audio-only]:
    The output has no video track, and `OutputFormat` has no way to say that: it reports
    the video codec the plan resolved, which then goes unwritten. Over a `TrackContent.Video` track it
    is refused, because nothing would be left to write.

## Audio overlays

A composition can carry tracks besides the primary one, and they play alongside it rather than after
it. Every backend draws video from the primary track alone, so an extra track is audio: a music bed, a
voice over, a sound effect.

| Feature                                    | Android           | Apple | Browser | ffmpeg              |
| ------------------------------------------ | ----------------- | ----- | ------- | ------------------- |
| Extra audio-only track                     | ✅                | ✅    | ✅      | ✅                  |
| `startAt` on an audio track                | ✅                | ✅    | ✅      | ✅                  |
| Looping track holding one clip             | ⚠️ [^media3-tail] | ✅    | ✅      | ✅                  |
| Looping track holding several clips        | ⚠️ [^media3-tail] | ✅    | ✅      | ✅                  |
| Looping track carrying video               | ⚠️ [^media3-tail] | ✅    | ✅      | ✅ [^ffmpeg-split]  |
| `AudioLevel` per clip and per track        | ✅                | ✅    | ✅      | ✅                  |
| `AudioLevel.Envelope`, `fadeIn`, `fadeOut` | ✅                | ✅    | ✅      | ✅ [^ffmpeg-volume] |
| Second track carrying video                | ❌                | ❌    | ❌      | ❌                  |

### Tracks

`track(content) { ... }` adds a track alongside the primary one, and `addTrack(track)` adds one that
was already built. `EditComposition.withTracks` swaps the whole list on an existing edit. The first
track is the primary one. It sets the output frame, and it is the only track any backend draws video
from. A composition with no `clip(...)` of its own takes its first `track` as the primary, so that
one has to carry video.

`track()` defaults to `TrackContent.AudioAndVideo`, so an overlay has to name `TrackContent.Audio`.
Any track after the first whose content is something else is refused at plan time, the default
included, because drawing a second picture needs a compositor no backend has yet. `plan()` answers
`Verdict.Incapable` holding one `ExportError.InvalidComposition` that says so, and
`export(composition, spec, to)` emits `ExportStatus.Failure` carrying the same error and writes
nothing.

`startAt(duration)` holds a track off until that far into the composition, and the track is silent
until then. media3 lays a gap at the head of the sequence, AVFoundation inserts an empty time range,
the browser starts the clip's source node late, and ffmpeg writes an `adelay`. The composition runs as
long as its longest non-looping track, offset included, so a bed that runs past the picture sets the
length rather than being cut to it. The picture holds the primary's last frame to the end, effects
included, on ffmpeg, Apple and the browser.[^media3-video-tail] A negative start on any track is
refused with `ExportError.InvalidComposition`, since a track cannot begin before the composition
does.

### Looping

`looping()` repeats a track from the top until the longest non-looping track ends. A looping track
never decides the duration, so a composition where every track loops has nothing to bound it and is
refused with `ExportError.InvalidComposition`. A clip-only effect on any of its clips is refused by
name.

The planner lays every pass once, from where the track starts until the composition ends, and cuts
the last pass where it runs past. Every backend lowers that list rather than looping on its own:
media3 lays the passes into its sequence, AVFoundation inserts each one and stops where the plan
does, and ffmpeg opens one input per clip and splits it into one branch per pass. The browser
schedules each pass and walks all of them when it draws, so a looping track carrying video keeps its
picture the whole way. A track may lay at most 2000 passes, and one asking for more is refused with
`ExportError.InvalidComposition` naming the track. The ceiling is read per track rather than per
composition, and it bounds a track laid once by its clip count the same way it bounds a looping one
by its repeats.

A looping track's audio scope is its whole run. A track `fadeIn` plays once at the start, a track
`fadeOut` ramps down at the composition's end, and an `EnvelopeAnchor.End` point lands there too. A
clip's own fade or envelope is read against the clip, so it replays on every pass, and a cut last
pass stops on whatever its clip curve reads there. All four backends agree, each measured on a
trimmed clip with an offset, on two clips told apart by their levels, and on a looping video primary.
ffmpeg and AVFoundation were read back from the written file, media3 from its device suite on an
emulator, and the browser from its offline mix and its render plan, since the test browser has no
encoder.

[^media3-tail]:
    The written file runs a little past the plan's duration, with silence in the tail. The
    device suite allows one output frame plus one AAC access unit at the file's sample rate: the flush
    rounds up to a whole output frame, and a clip starting partway through an access unit costs another,
    since millisecond clipping opens on the unit before the start. On the emulator that measured 27 to
    48 ms. Every gain reading lands where the plan folded it.

[^media3-video-tail]:
    On media3 the video track ends with the primary and only the audio runs on
    through the rest of the bed, since media3 has no way to re-emit a decoded frame across that
    stretch on export ([androidx/media#921](https://github.com/androidx/media/issues/921)).

[^ffmpeg-split]:
    One input per clip, split into one branch per pass. A split clones every frame onto
    all its outputs out of one buffer and concat pulls from the branch it is on and no other, so up to
    one pass of decoded frames sits in the graph per branch still waiting. The cost scales with the pass
    count, so a long looping track at a high resolution is where it shows.

[^ffmpeg-volume]:
    `volume` reads its expression through ffmpeg's own parser, which refuses a nest of
    about a hundred `if`s, and a curve folded from an envelope and a fade runs to hundreds of segments.
    A long curve is split across a chain of `volume` nodes carrying 48 segments each, reading one
    everywhere outside their own run. The nodes multiply, so the chain lands on the gain a single node
    would have rather than on an approximation of it.

### Gain

`AudioLevel` is set on a clip or a track and `AudioSpec` on the composition. `Inherit`, the default,
leaves the enclosing scope alone, `Mute` contributes silence without changing timing, and `Volume`
scales by a constant. Every scope's level is folded into one curve per clip before a backend sees it,
so a mute at any scope silences everything below it, and a level on two scopes multiplies rather than
one replacing the other.

`AudioLevel.Envelope` is a piecewise-linear gain curve. Each `EnvelopePoint` carries an `at`, a
`gain` and a `from`, where `from` is `EnvelopeAnchor.Start` or `EnvelopeAnchor.End`. An end-anchored
point is placed once the plan settles how long the scope runs, so a fade out can be written before
the clip's length is known. The gain ramps linearly between neighbouring points and holds flat
before the first and after the last. A clip's envelope is read against its trimmed length, and a
track's against its whole run of clips, measured from where the track starts. A looping track runs
until the composition ends, so that is where its end-anchored points land, not at the end of a pass.

The plan refuses an envelope with a point outside its scope, a negative gain, or points that fall out
of time order once the end-anchored ones are placed, each with `ExportError.InvalidComposition`.

`fadeIn(duration)` and `fadeOut(duration)` exist on both the clip builder and the track builder, and
are kept on `Clip` and `Track` as durations of their own rather than folded into the level. A clip's
fades are measured against its trim, so retrimming the clip moves them with it. A track's fade in
starts where the track does and its fade out ends where its last clip does, or at the composition's
end when the track loops. The plan turns the pair into a curve peaking at one and multiplies it into
the level, the same product a clip level and a track level already go through. So a fade rises to
whatever `audio(...)` set rather than to one, `AudioLevel.Volume(0.5f)` with a fade in ramps from
silence to half, fading a `Mute` leaves it muted, and where the two calls are written makes no
difference. On an `Envelope` written by hand the fade scales the curve the envelope describes,
leaving its points as they were, so a two second envelope falling from one to a half with a one
second fade in reads 0.75 a second in rather than one.

Fades have refusals of their own, each `ExportError.InvalidComposition` with a message naming the
fade rather than the envelope: a negative fade, a fade longer than its scope, and a `fadeIn` and
`fadeOut` that together run longer than their scope, since the two ramps would cross.

Fades on different scopes compose the same way levels do. A clip fade under a track fade multiplies,
so a clip that fades in at the top of a track that also fades in comes up along the product of the two
ramps rather than along either one.

Every backend ramps the gain rather than stepping it. media3 runs a `GainProcessor` over a
`GainProvider` that reads the curve at each frame. AVFoundation writes one
`setVolumeRampFromStartVolume:toEndVolume:timeRange:` per segment onto the audio mix. The browser
writes `linearRampToValueAtTime` automation onto the gain node each clip plays through. ffmpeg reads
a `volume` expression once per frame, with `asetnsamples=n=64` in front of it so the frame the
expression steps at is 1.3 ms at 48 kHz rather than the 21 ms a default 1024-sample frame gives.

## Stills

A photo goes on the timeline as `MediaSource.Image(image, duration)`, or through the `image(...)`
builder on a composition or a track. Both are `@ExperimentalFilmstripApi`. The image itself is an
`ImageSource`, which is a path, a URI or bytes.

| Feature                       | Android | Apple             | Browser         | ffmpeg          |
| ----------------------------- | ------- | ----------------- | --------------- | --------------- |
| A still clip on the timeline  | ✅      | ✅ [^apple-still] | ❌ [^no-stills] | ❌ [^no-stills] |
| Encode a frame to PNG or JPEG | ✅      | ✅                | ✅              | ✅              |
| Encode a frame to WebP        | ✅      | ❌                | ⚠️ [^web-webp]  | ❌              |

A still has no cadence of its own, so a plan that settles on no frame rate refuses it by name. Where
the composition's first frame is a still and no `targetHeight` was asked for, the output frame is
held to 3840x2160, since a photo's own bounds are a sensor's rather than an encoder's.

Decoding a still is the platform's own decoder in every case: `BitmapFactory` on Android, ImageIO on
Apple, the JDK's `ImageIO` on the JVM, `createImageBitmap` in a browser. Whatever those read,
filmstrip reads. A browser cannot open an `ImageSource.Path`, because a path names a filesystem no
browser has.

[^apple-still]:
    AVFoundation discards a trailing empty range and gives a track holding nothing but
    empty ranges no duration, so each still is cut from a small generated movie of black frames and the
    picture is drawn over it by the Core Image chain. The seed pixels are never seen.

[^no-stills]:
    Both backends open a clip by decoding a video track, and a photo has none. Refused by
    name wherever it appears, an audio track included.

[^web-webp]:
    A browser that will not encode WebP falls back to PNG rather than failing, so filmstrip
    compares the blob's own media type against the one it asked for and refuses by name when they
    disagree. The JDK ships no WebP writer, and Apple's ImageIO reads WebP on systems where it will not
    write it, so both refuse by name too.

## Preview and thumbnails

`filmstrip-player` carries a real engine on all four platforms, and each one lowers the composition
through the same engine its exports go through, so the compositing is the same graph rather than a
second implementation. Without the artifact, `preview()` returns a player that reports
`PlaybackError.BackendMissing` instead of throwing. An ffmpeg preview process that exits with a
failure reports `PlaybackError.Underlying` and `PlaybackEvent.Failed`, carrying its exit status and
stderr, rather than `Ended`.

| Feature                  | Android           | Apple | Browser         | ffmpeg             |
| ------------------------ | ----------------- | ----- | --------------- | ------------------ |
| Live composition preview | ✅                | ✅    | ⚠️ [^web-ramp]  | ✅                 |
| `FrameReadback`          | ✅                | ✅    | ⚠️ [^web-gl]    | ✅                 |
| `FrameStepping`          | ✅                | ✅    | ⚠️ [^web-gl]    | ✅                 |
| `LiveParameterRedraw`    | ✅                | ✅    | ⚠️ [^web-gl]    | ❌ [^ffmpeg-live]  |
| `AudioMonitoring`        | ⚠️ [^media3-vol]  | ❌    | ⚠️ [^web-audio] | ❌ [^ffmpeg-audio] |
| `HdrPreview`             | ⚠️ [^hdr-display] | ❌    | ❌              | ❌                 |
| `PlaybackSpeed`          | ❌                | ❌    | ❌              | ❌                 |
| `SeekAccuracy.Nearest`   | ❌ [^media3-seek] | ✅    | ✅              | ❌ [^ffmpeg-seek]  |

`PreviewFidelity` reports what a preview cannot show whatever the backend. `EncoderArtifacts` is
`NotPreviewable` everywhere, because the preview never opens an encoder. `Smoothness` is
`Approximate` everywhere, because a late frame is dropped. `HdrAppearance` is `NotPreviewable`
everywhere: on Android and Apple the display decides it, the browser composites in standard range,
and the ffmpeg pump converts a grade straight to RGB.

A loop range wraps only while playback runs, on Android and Apple alike. A seek or a frame step
past the range's end while paused stays where it lands. On Android the playhead reports where a
seek is going for as long as it is in flight, rather than the frame media3 last read.

Thumbnails go through `FrameRenderer.frame` and `frames`, or `ThumbnailRequest` directly.
`ThumbnailRequest.precise` chooses an exact seek over the nearest sync sample. Android honours it
with media3's `SeekParameters`, Apple with a zero seek tolerance, and the browser and ffmpeg decode
forward from a sync sample either way, so every frame there is the exact one. A photo on Android is
drawn rather than extracted, and is always exact.

On a Mac with no hardware colour converter, a virtual machine for one, an Apple preview frame or
thumbnail of an SD clip with no colour tags can differ in colour from its export. VideoToolbox
guesses SMPTE-C primaries and a BT.601 matrix for such a clip, and AVFoundation converts it to
Rec.709 before filmstrip sees a frame. Without the converter, the `AVAssetReader` an export reads
through converts the gamut, while the `AVAssetImageGenerator` behind readback and thumbnails only
swaps the matrix. This is AVFoundation's limit. A source already tagged Rec.709 needs no conversion
and is not affected.

[^web-ramp]:
    The picture is the compositor the encoder takes its frames from, so it is exact. The
    audio is not: the preview samples a clip's gain curve where the slice opens and holds it flat
    across, so a fade cannot be auditioned yet.

[^web-gl]:
    Reported only when the page can obtain a WebGL2 context. Readback is the whole display
    path in a browser, so a page that cannot get one has none of the three rather than some of them.

[^media3-vol]:
    Reported when `CompositionPlayer` makes `COMMAND_SET_VOLUME` available, which is
    asked of the player rather than assumed.

[^web-audio]:
    Reported wherever the Web Audio API is there. A browser refusing to start the graph
    without a user gesture is a transport occasion rather than a missing capability, and the engine puts
    `playWhenReady` back to false when it happens.

[^ffmpeg-audio]:
    Nothing here opens an audio device. The preview pump is video only, and
    `setVolume` does nothing, so a host reads the feature rather than wondering why the slider is silent.

[^ffmpeg-live]:
    A parameter is filter graph text, so changing one is a new graph and a new process
    rather than a value swapped under a running render.

[^hdr-display]: Asked of the display, which is what decides whether a grade survives the last step.

[^media3-seek]:
    `CompositionPlayer` takes no seek tolerance of any kind, so a relaxed seek is
    clamped to `Exact` and costs what an exact one costs.

[^ffmpeg-seek]:
    A relaxed seek debounces before it spawns a process rather than landing on a
    different frame. That is what bounds the cost of a scrub here.

## Sources and sinks

|                       | Android             | Apple             | Browser                          | ffmpeg             |
| --------------------- | ------------------- | ----------------- | -------------------------------- | ------------------ |
| `MediaSource.Path`    | ✅                  | ✅                | ❌                               | ✅                 |
| `MediaSource.Uri`     | ✅                  | ✅                | ✅                               | ⚠️ `file://` only  |
| `MediaSource.Bytes`   | ✅ [^android-bytes] | ❌ [^apple-bytes] | ✅                               | ❌ [^ffmpeg-bytes] |
| `MediaSink.Path`      | ✅                  | ✅                | downloads a file                 | ✅                 |
| `MediaSink.Uri`       | ✅ [^android-uri]   | ⚠️ `file://` only | hands back a `blob:` URL [^blob] | ⚠️ `file://` only  |
| `MediaSink.Temporary` | ✅                  | ✅                | downloads under a generated name | ✅                 |

[^android-bytes]:
    Written to the app's cache under a name taken from the bytes themselves, so the
    probe and the export that follows it read one file rather than writing two. Needs a
    `FilmstripContext`, and is refused by name without one.

[^apple-bytes]:
    In-memory sources need writing to a temporary file first, which neither `probe` nor
    the lowering does here.

[^ffmpeg-bytes]: In-memory bytes have to be written somewhere first. The backend reads files.

[^blob]:
    The URL belongs to the caller, who has to pass it to `URL.revokeObjectURL` when they are
    done. filmstrip never revokes it.

[^android-uri]:
    media3 writes to a path and nothing else, so a `content://` destination is written
    to the cache and then streamed through the app's `ContentResolver`. A `file://` one is written
    straight to its path.

`probe` reads metadata with `MediaMetadataRetriever` on Android and `AVURLAsset` on Apple, both from
`filmstrip-core` alone. The browser and the JVM have no read-only framework to open a container
with, so `probe` there answers only for a still until `webCodecsBackend()` or `ffmpegBackend()` is
registered, and refuses by name until it is.

Three optional adapter modules build these same arms out of a third-party file type, and none of
them adds an arm of its own: `filmstrip-io-filekit` turns a picked `PlatformFile` into whichever arm
that target's backend reads and turns a sink back into a `PlatformFile`, `filmstrip-io-okio` and
`filmstrip-io-kotlinx` turn a `Path` into `Path` and a stream into `Bytes`. Whatever the table above
says about an arm is what the adapter gets you, so a `Path` from either io module is still refused
in the browser and a stream is still the only thing that reads there. `filmstrip-io-filekit-compose`
wraps FileKit's Compose launchers so a pick, a save location and a share sheet arrive already on
those arms. A pick in a browser mints an object URL that lives until the page unloads, and
`MediaSource.release` on the source frees it early.
