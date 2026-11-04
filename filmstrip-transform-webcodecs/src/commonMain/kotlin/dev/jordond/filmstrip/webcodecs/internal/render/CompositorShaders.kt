@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.BT2020_CB_SCALE
import dev.jordond.filmstrip.color.BT2020_CR_SCALE
import dev.jordond.filmstrip.color.BT2020_LUMA_B
import dev.jordond.filmstrip.color.BT2020_LUMA_G
import dev.jordond.filmstrip.color.BT2020_LUMA_R
import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.color.TEN_BIT_CHROMA_MID
import dev.jordond.filmstrip.color.TEN_BIT_CHROMA_RANGE
import dev.jordond.filmstrip.color.TEN_BIT_LUMA_FLOOR
import dev.jordond.filmstrip.color.TEN_BIT_LUMA_RANGE
import dev.jordond.filmstrip.color.TEN_BIT_MAX_CODE
import dev.jordond.filmstrip.effects.color.HDR_COLOR_MATRIX_GLSL
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.js.ExperimentalWasmJsInterop

// The quad is a fixed unit square and the letterbox is a uniform, so the texture coordinates always
// span the whole frame. Deriving them from a shrunken quad instead would sample a centre crop out
// of every clip that had to be letterboxed.
internal val COMPOSITE_VERTEX_SHADER =
  """
  #version 300 es
  in vec2 aPosition;
  uniform mat3 uTexMatrix;
  uniform vec2 uQuadScale;
  out vec2 vTexCoord;
  void main() {
    vec2 uv = aPosition * 0.5 + 0.5;
    vTexCoord = (uTexMatrix * vec3(uv, 1.0)).xy;
    gl_Position = vec4(aPosition * uQuadScale, 0.0, 1.0);
  }
  """.trimIndent()

// A full-screen quad with no letterbox and no texture matrix: the blur, unpack, pack and present
// passes all read a texture that already covers the whole viewport, so there is nothing left to
// scale or warp.
internal val FULLSCREEN_VERTEX_SHADER =
  """
  #version 300 es
  in vec2 aPosition;
  out vec2 vTexCoord;
  void main() {
    vTexCoord = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
  }
  """.trimIndent()

/**
 * The composite pass, on the signal an eight-bit frame carries or on the light a kept grade holds.
 *
 * The clip's grade and the composition's are two matrices with a clamp between them rather than one
 * product, because the planner folds a run of color effects per stage and every backend writes the
 * clip's stage into a frame before the composition's runs. On SDR the color arithmetic is highp:
 * mediump is fp16 on a mobile GPU, and a matrix with a bias moves an eight-bit code by one there. A
 * grade is highp throughout instead, since the light it carries runs past one and the shared body
 * takes its arguments at the default precision.
 */
internal fun compositeFragmentShader(transfer: HdrTransfer?): String =
  if (transfer == null) {
    """
    #version 300 es
    precision mediump float;
    uniform sampler2D uTexSampler;
    uniform highp mat4 uColorMatrix;
    uniform highp mat4 uCompositionColorMatrix;
    in vec2 vTexCoord;
    out vec4 outColor;
    void main() {
      vec4 c = texture(uTexSampler, vTexCoord);
      highp vec3 graded = clamp((uColorMatrix * vec4(c.rgb, 1.0)).rgb, 0.0, 1.0);
      graded = clamp((uCompositionColorMatrix * vec4(graded, 1.0)).rgb, 0.0, 1.0);
      outColor = vec4(graded, c.a);
    }
    """.trimIndent()
  } else {
    """
    #version 300 es
    precision highp float;
    uniform sampler2D uTexSampler;
    uniform mat4 uColorMatrix;
    uniform mat4 uCompositionColorMatrix;
    uniform float uWhite;
    uniform float uDisplayGamma;
    uniform float uOotfGamma;
    uniform float uCeiling;
    in vec2 vTexCoord;
    out vec4 outColor;
    $HDR_COLOR_MATRIX_GLSL
    ${gradeLightGlsl(transfer)}
    void main() {
      vec4 c = texture(uTexSampler, vTexCoord);
      vec3 graded =
        filmstripGradeHdr(toGradeLight(c.rgb), uColorMatrix, uWhite, uDisplayGamma, uOotfGamma, uCeiling);
      graded =
        filmstripGradeHdr(graded, uCompositionColorMatrix, uWhite, uDisplayGamma, uOotfGamma, uCeiling);
      outColor = vec4(fromGradeLight(graded), c.a);
    }
    """.trimIndent()
  }

/**
 * Ten-bit limited-range BT.2020 NCL planes to linear display light, with one at
 * [HDR_REFERENCE_WHITE_NITS].
 *
 * HLG's opto-optical transfer is driven by the color's luminance, the one `hlgSceneFromNits`
 * inverts, so a blend in the buffer is a blend in display light for any color.
 */
internal fun unpackShader(transfer: HdrTransfer): String =
  """
  #version 300 es
  precision highp float;
  precision highp usampler2D;
  $PLANE_SIGNAL_GLSL
  ${eotfGlsl(transfer)}
  void main() {
    vec3 nits = eotf(planeSignal());
    outColor = vec4(nits / ${HDR_REFERENCE_WHITE_NITS.glsl()}, 1.0);
  }
  """.trimIndent()

/**
 * Ten-bit limited-range BT.2020 NCL planes of this transfer through the shared tone curve, to the eight-bit BT.709
 * SDR signal an uploaded SDR frame carries.
 */
internal fun toneMapShader(transfer: HdrTransfer): String =
  """
  #version 300 es
  precision highp float;
  precision highp usampler2D;
  $PLANE_SIGNAL_GLSL
  ${toneMapGlsl(transfer)}
  void main() {
    outColor = vec4(toneMappedSdr(planeSignal()), 1.0);
  }
  """.trimIndent()

// The planes arrive top-down where every texture here is +Y-up, so the sample is flipped on the way in.
private val PLANE_SIGNAL_GLSL =
  """
  uniform usampler2D uY;
  uniform usampler2D uU;
  uniform usampler2D uV;
  in vec2 vTexCoord;
  out vec4 outColor;
  float code(usampler2D plane) {
    return float(texture(plane, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).r);
  }
  vec3 planeSignal() {
    float y = (code(uY) - ${TEN_BIT_LUMA_FLOOR.glsl()}) / ${TEN_BIT_LUMA_RANGE.glsl()};
    float cb = (code(uU) - ${TEN_BIT_CHROMA_MID.glsl()}) / ${TEN_BIT_CHROMA_RANGE.glsl()};
    float cr = (code(uV) - ${TEN_BIT_CHROMA_MID.glsl()}) / ${TEN_BIT_CHROMA_RANGE.glsl()};
    float r = y + ${BT2020_CR_SCALE.glsl()} * cr;
    float b = y + ${BT2020_CB_SCALE.glsl()} * cb;
    float g = (y - ${BT2020_LUMA_R.glsl()} * r - ${BT2020_LUMA_B.glsl()} * b) / ${BT2020_LUMA_G.glsl()};
    return clamp(vec3(r, g, b), 0.0, 1.0);
  }
  """.trimIndent()

/**
 * Linear display light back to ten-bit limited-range BT.2020 NCL planes, packed as the bytes of an
 * `I420P10` frame.
 *
 * The framebuffer is half the output's width and twice its height: one row per plane row, luma
 * first, then Cb, then Cr, two samples to an `RGBA8` texel written little-endian. Chroma is
 * averaged over the two by two block of re-derived samples rather than taken from one of them, and
 * fills half of its row, which the layout the frame is built with accounts for.
 *
 * A read back row is bottom-up, so a buffer row maps to its own framebuffer row directly and the
 * flip happens where the linear texture is sampled instead.
 */
internal fun packShader(transfer: HdrTransfer): String =
  """
  #version 300 es
  precision highp float;
  uniform sampler2D uTexSampler;
  uniform vec2 uSize;
  out vec4 outColor;
  ${oetfGlsl(transfer)}
  vec3 signalAt(vec2 pixel) {
    vec3 light = texture(uTexSampler, (pixel + 0.5) / uSize).rgb;
    return oetf(max(light, 0.0) * ${HDR_REFERENCE_WHITE_NITS.glsl()});
  }
  vec3 ycc(vec3 rgb) {
    float y = ${BT2020_LUMA_R.glsl()} * rgb.r + ${BT2020_LUMA_G.glsl()} * rgb.g + ${BT2020_LUMA_B.glsl()} * rgb.b;
    return vec3(y, (rgb.b - y) / ${BT2020_CB_SCALE.glsl()}, (rgb.r - y) / ${BT2020_CR_SCALE.glsl()});
  }
  vec4 pack2(float first, float second) {
    float a = clamp(floor(first + 0.5), 0.0, ${TEN_BIT_MAX_CODE.glsl()});
    float b = clamp(floor(second + 0.5), 0.0, ${TEN_BIT_MAX_CODE.glsl()});
    return vec4(mod(a, 256.0), floor(a / 256.0), mod(b, 256.0), floor(b / 256.0)) / 255.0;
  }
  void main() {
    float x = floor(gl_FragCoord.x);
    float y = floor(gl_FragCoord.y);
    float h = uSize.y;
    if (y < h) {
      float row = h - 1.0 - y;
      float first = ycc(signalAt(vec2(2.0 * x, row))).x;
      float second = ycc(signalAt(vec2(2.0 * x + 1.0, row))).x;
      outColor =
        pack2(
          ${TEN_BIT_LUMA_FLOOR.glsl()} + ${TEN_BIT_LUMA_RANGE.glsl()} * first,
          ${TEN_BIT_LUMA_FLOOR.glsl()} + ${TEN_BIT_LUMA_RANGE.glsl()} * second
        );
      return;
    }
    if (x >= uSize.x / 4.0) {
      outColor = vec4(0.0);
      return;
    }
    bool isCr = y >= 1.5 * h;
    float chromaRow = isCr ? y - 1.5 * h : y - h;
    float top = h - 1.0 - 2.0 * chromaRow;
    float bottom = top - 1.0;
    float samples[2];
    for (int k = 0; k < 2; k++) {
      float left = 4.0 * x + 2.0 * float(k);
      vec3 mean =
        (ycc(signalAt(vec2(left, top))) +
          ycc(signalAt(vec2(left + 1.0, top))) +
          ycc(signalAt(vec2(left, bottom))) +
          ycc(signalAt(vec2(left + 1.0, bottom)))) * 0.25;
      samples[k] = isCr ? mean.z : mean.y;
    }
    outColor =
      pack2(
        ${TEN_BIT_CHROMA_MID.glsl()} + ${TEN_BIT_CHROMA_RANGE.glsl()} * samples[0],
        ${TEN_BIT_CHROMA_MID.glsl()} + ${TEN_BIT_CHROMA_RANGE.glsl()} * samples[1]
      );
  }
  """.trimIndent()

// What a preview draws: the same picture the browser's own upload of an HDR frame produces, which
// is reference white clipped at one through the SDR display curve.
internal val PRESENT_FRAGMENT_SHADER =
  """
  #version 300 es
  precision highp float;
  uniform sampler2D uTexSampler;
  in vec2 vTexCoord;
  out vec4 outColor;
  void main() {
    vec3 light = clamp(texture(uTexSampler, vTexCoord).rgb, 0.0, 1.0);
    outColor = vec4(pow(light, vec3(1.0 / ${SDR_DISPLAY_GAMMA.glsl()})), 1.0);
  }
  """.trimIndent()
