package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * What [ExportPlanner.encoderAskOf] found an export asks of its video encoder.
 *
 * @property encoderName The encoder the plan opens, as the device named it, or null when the device names none.
 * @property frame The frame the plan writes, after the encoder's floor and ceiling were applied to it.
 * @property encodesHdr Whether the grade reaches the encoder, which then has to open a ten-bit profile.
 */
@InternalFilmstripApi
public class EncoderAsk(
  public val encoderName: String?,
  public val frame: Size,
  public val encodesHdr: Boolean,
)
