"""Builds colr-glyphs.ttf, the color font the browser text tests draw.

"A" is a square with its left half red and its right half blue. "B" is a square with its top half green and its
bottom half yellow. Each half is its own COLRv0 layer, so the inside of each glyph holds one palette color and only
its edges are shaded.

Run it with fonttools installed, from any directory: python3 colr-glyphs.py. The timestamps are pinned, so the same
fonttools writes the same bytes.
"""

from pathlib import Path

from fontTools.colorLib.builder import buildCOLR, buildCPAL
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

UNITS = 1000
ASCENT = 800
DESCENT = 200
ADVANCE = 1000
LEFT, RIGHT, BOTTOM, TOP = 100, 900, 0, 800

RED = (1.0, 0.0, 0.0, 1.0)
BLUE = (0.0, 0.0, 1.0, 1.0)
GREEN = (0.0, 0.6, 0.0, 1.0)
YELLOW = (1.0, 0.8, 0.0, 1.0)


def box(left, bottom, right, top):
    pen = TTGlyphPen(None)
    pen.moveTo((left, bottom))
    pen.lineTo((left, top))
    pen.lineTo((right, top))
    pen.lineTo((right, bottom))
    pen.closePath()
    return pen.glyph()


def main():
    middle_x = (LEFT + RIGHT) // 2
    middle_y = (BOTTOM + TOP) // 2
    glyphs = {
        ".notdef": TTGlyphPen(None).glyph(),
        "A": box(LEFT, BOTTOM, RIGHT, TOP),
        "B": box(LEFT, BOTTOM, RIGHT, TOP),
        "A.left": box(LEFT, BOTTOM, middle_x, TOP),
        "A.right": box(middle_x, BOTTOM, RIGHT, TOP),
        "B.top": box(LEFT, middle_y, RIGHT, TOP),
        "B.bottom": box(LEFT, BOTTOM, RIGHT, middle_y),
    }
    order = list(glyphs)

    builder = FontBuilder(UNITS, isTTF=True)
    builder.setupGlyphOrder(order)
    builder.setupCharacterMap({ord("A"): "A", ord("B"): "B"})
    builder.setupGlyf(glyphs)
    # A renderer places an outline by its left side bearing, so each glyph's has to be where its outline starts.
    glyf = builder.font["glyf"]
    for name in order:
        glyf[name].recalcBounds(glyf)
    builder.setupHorizontalMetrics({name: (ADVANCE, getattr(glyf[name], "xMin", 0)) for name in order})
    builder.setupHorizontalHeader(ascent=ASCENT, descent=-DESCENT)
    builder.setupNameTable({"familyName": "Filmstrip Colr", "styleName": "Regular"})
    builder.setupOS2(sTypoAscender=ASCENT, sTypoDescender=-DESCENT, usWinAscent=ASCENT, usWinDescent=DESCENT)
    builder.setupPost()
    builder.font["COLR"] = buildCOLR({"A": [("A.left", 0), ("A.right", 1)], "B": [("B.top", 2), ("B.bottom", 3)]})
    builder.font["CPAL"] = buildCPAL([[RED, BLUE, GREEN, YELLOW]])
    builder.font["head"].created = builder.font["head"].modified = 0
    builder.font.recalcTimestamp = False
    builder.save(str(Path(__file__).with_name("colr-glyphs.ttf")))


if __name__ == "__main__":
    main()
