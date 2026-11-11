package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.player.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.player.appleExportLowering
import dev.jordond.filmstrip.player.appleFixtureComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.cinterop.BetaInteropApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.audioMix
import platform.AVFoundation.status
import platform.AVFoundation.videoComposition
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import platform.Foundation.numberWithUnsignedInt

/**
 * The fixture lowered the way the preview lowers it, on an item a player has made ready to seek.
 */
internal class LoadedFixture(
  val resolved: ResolvedComposition,
  val av: AvComposition,
  val item: AVPlayerItem,
  val player: AVPlayer,
)

/**
 * Lowers [composition], puts it on a fresh player and waits for the item to be ready.
 *
 * The main run loop has to be pumped for the item to get there, which a contract body does.
 */
internal suspend fun loadedFixture(composition: Composition = appleFixtureComposition()): LoadedFixture {
  val resolved = appleExportLowering(composition)
  val av = resolved.toAvComposition()
  val item =
    AVPlayerItem(asset = av.composition).apply {
      videoComposition = av.videoComposition
      audioMix = av.audioMix
    }
  val player = AVPlayer(playerItem = item)
  awaitContract("the fixture item to be ready") { item.status == AVPlayerItemStatusReadyToPlay }
  return LoadedFixture(resolved, av, item, player)
}

/**
 * A video output that hands frames back as BGRA.
 *
 * An item with nothing to draw into decodes no frame for a seek, so a test that reads where a seek settled attaches
 * one of these first.
 */
@OptIn(BetaInteropApi::class)
internal fun bgraVideoOutput(): AVPlayerItemVideoOutput =
  AVPlayerItemVideoOutput(
    pixelBufferAttributes =
      NSDictionary.dictionaryWithObject(
        NSNumber.numberWithUnsignedInt(kCVPixelFormatType_32BGRA),
        forKey = NSString.create(string = PIXEL_FORMAT_KEY),
      ),
  )
