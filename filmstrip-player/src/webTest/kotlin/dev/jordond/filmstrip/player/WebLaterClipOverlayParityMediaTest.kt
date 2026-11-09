package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.LaterClipOverlayParityContractTest

/**
 * The later clip overlay contract in the browser, where the compositor hands each overlay the slot's composition time.
 */
class WebLaterClipOverlayParityMediaTest : LaterClipOverlayParityContractTest(WebClipParity)
