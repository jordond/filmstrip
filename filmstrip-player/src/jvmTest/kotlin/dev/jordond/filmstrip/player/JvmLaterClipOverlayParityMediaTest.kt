package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.LaterClipOverlayParityContractTest

/**
 * The later clip overlay contract on ffmpeg, where a preview of two clips reads the export's graph from its head.
 */
class JvmLaterClipOverlayParityMediaTest : LaterClipOverlayParityContractTest(JvmClipParity)
