package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.SolidParityContractTest

/**
 * The solid parity contract in the browser, where a solid is one pixel of its colour the compositor stretches over its
 * quad in the preview and the export.
 */
class WebSolidParityMediaTest : SolidParityContractTest(WebClipParity)
