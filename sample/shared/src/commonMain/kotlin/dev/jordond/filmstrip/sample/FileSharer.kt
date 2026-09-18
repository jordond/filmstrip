package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.PlatformFile

/**
 * Hands a file to the platform's share sheet.
 *
 * Takes the sink an export reported rather than a path string, so a content uri destination reaches
 * the sheet as the uri it is instead of being flattened to something the sheet cannot open.
 */
public interface FileSharer {
  /**
   * Shares a file that was already resolved, such as the diagnostics report.
   */
  public fun share(file: PlatformFile)

  /**
   * Shares whatever an export wrote, on the arm it reported.
   */
  public fun share(output: MediaSink)
}

/**
 * The sharer for this platform.
 *
 * Null where there is no share sheet to hand a file to, which is every target that is not a phone.
 * The result screen hides its share button rather than showing one that cannot do anything.
 */
@Composable
public expect fun rememberFileSharer(): FileSharer?
