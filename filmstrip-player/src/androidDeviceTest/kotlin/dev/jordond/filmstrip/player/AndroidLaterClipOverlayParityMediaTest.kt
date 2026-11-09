package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.LaterClipOverlayParityContractTest

/**
 * The later clip overlay contract on media3, where each clip's span carries the composition time its overlay reads.
 */
class AndroidLaterClipOverlayParityMediaTest : LaterClipOverlayParityContractTest(AndroidClipParity)
