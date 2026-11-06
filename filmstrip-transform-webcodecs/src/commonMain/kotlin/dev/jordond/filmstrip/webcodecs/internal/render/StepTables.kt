@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.effect.WebGlTable
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The textures of every [WebGlTable] the passes read, each uploaded once however many frames and steps read it.
 *
 * A table is found by instance first, so a table read before costs no walk of its bytes, and an equal table of another
 * instance joins the texture the first one uploaded. [evictPastBound] holds the textures to [maxResidentBytes].
 *
 * A table wider or taller than [maxTextureSide] is refused by name, which is this GL context's limit.
 */
internal class StepTables(
  private val gl: WebGl2,
  private val maxTextureSide: Int = gl.getParameter(GL_MAX_TEXTURE_SIZE),
  private val maxResidentBytes: Long = MAX_RESIDENT_TABLE_BYTES,
) {
  // Least recently read first.
  private val residents = mutableListOf<Resident>()
  private var residentBytes = 0L

  /**
   * The texture of every table held right now.
   */
  val textures: List<JsAny> get() = residents.map { it.texture }

  /**
   * The texture holding [table], uploading it when no equal table has been, for the step named [reader].
   */
  fun textureOf(
    reader: String,
    table: WebGlTable,
  ): JsAny {
    val resident = residentOf(table) ?: upload(reader, table)
    if (residents.lastOrNull() !== resident) {
      residents.remove(resident)
      residents.add(resident)
    }
    return resident.texture
  }

  /**
   * Deletes the least recently read textures until the rest fit [maxResidentBytes], and always keeps the latest.
   *
   * A step's draw is the only thing reading a texture, so this runs between draws. A table evicted here uploads again
   * the next time it is read.
   */
  fun evictPastBound() {
    while (residentBytes > maxResidentBytes && residents.size > 1) {
      val oldest = residents.removeAt(0)
      gl.deleteTexture(oldest.texture)
      residentBytes -= oldest.bytes
    }
  }

  /**
   * Deletes every texture, so the tables of a plan that replaced this one upload afresh.
   */
  fun forget() {
    residents.forEach { gl.deleteTexture(it.texture) }
    residents.clear()
    residentBytes = 0L
  }

  private fun residentOf(table: WebGlTable): Resident? =
    residents.firstOrNull { resident -> resident.instances.any { it === table } }
      ?: residents.firstOrNull { it.instances.first() == table }?.also { it.instances += table }

  private fun upload(
    reader: String,
    table: WebGlTable,
  ): Resident {
    if (table.width > maxTextureSide || table.height > maxTextureSide) {
      throw BrowserExportFailure(
        "$reader needs a ${table.width}x${table.height} texture, and this GL context's MAX_TEXTURE_SIZE is " +
          "$maxTextureSide.",
      )
    }
    val texture = createTexture(gl, GL_NEAREST)
    gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 0)
    gl.texImage2D(
      GL_TEXTURE_2D,
      0,
      GL_RGBA,
      table.width,
      table.height,
      0,
      GL_RGBA,
      GL_UNSIGNED_BYTE,
      table.bytes.toUint8Array(),
    )
    return Resident(texture, table.bytes.size, mutableListOf(table)).also {
      residents.add(it)
      residentBytes += it.bytes
    }
  }

  private class Resident(
    val texture: JsAny,
    val bytes: Int,
    val instances: MutableList<WebGlTable>,
  )
}

// The bound on resident tables: seven of the largest LUT atlas, 3096x1419, or over fifty of a 65 point one. It reaches
// a plan with dozens of clips each on its own table, and the clips ahead upload again when they come round.
private const val MAX_RESIDENT_TABLE_BYTES = 128L * 1024 * 1024
