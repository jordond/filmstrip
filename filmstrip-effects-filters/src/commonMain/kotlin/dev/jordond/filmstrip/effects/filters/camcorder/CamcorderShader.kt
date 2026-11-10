package dev.jordond.filmstrip.effects.filters.camcorder

// The GLSL ES 3.00 pass that lays a camcorder display over the frame, texel for texel. The display texture holds
// CamcorderOsd's raster at the frame's own size with its top row first, and v runs up the picture, so the row read is
// counted down from the top.
internal object CamcorderShader {
  const val FRAME = "uFrame"
  const val DISPLAY = "uDisplay"

  val FRAGMENT =
    """
    #version 300 es
    precision highp float;

    in vec2 vUv;
    out vec4 fragColor;

    uniform sampler2D $FRAME;
    uniform sampler2D $DISPLAY;

    void main() {
      vec4 frame = texture($FRAME, vUv);
      ivec2 size = textureSize($DISPLAY, 0);
      ivec2 texel = clamp(ivec2(floor(vec2(vUv.x, 1.0 - vUv.y) * vec2(size))), ivec2(0), size - 1);
      vec4 display = texelFetch($DISPLAY, texel, 0);
      fragColor = mix(frame, display, display.a);
    }
    """.trimIndent()
}
