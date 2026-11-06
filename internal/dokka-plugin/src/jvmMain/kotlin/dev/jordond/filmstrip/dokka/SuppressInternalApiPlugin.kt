package dev.jordond.filmstrip.dokka

import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.transformers.documentables.SuppressedByConditionDocumentableFilterTransformer
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.Annotations
import org.jetbrains.dokka.model.Documentable
import org.jetbrains.dokka.model.properties.WithExtraProperties
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.DokkaPlugin
import org.jetbrains.dokka.plugability.DokkaPluginApiPreview
import org.jetbrains.dokka.plugability.Extension
import org.jetbrains.dokka.plugability.PluginApiPreviewAcknowledgement

/**
 * Leaves every declaration annotated with `@InternalFilmstripApi` out of the API reference, along with the
 * annotation class itself.
 */
class SuppressInternalApiPlugin : DokkaPlugin() {
  /**
   * Runs [SuppressInternalApiTransformer] before Dokka merges source sets, so a suppressed declaration
   * is gone from every platform's page.
   */
  val suppressInternalApi: Extension<*, *, *> by extending {
    plugin<DokkaBase>().preMergeDocumentableTransformer providing ::SuppressInternalApiTransformer
  }

  @DokkaPluginApiPreview
  override fun pluginApiPreviewAcknowledgement(): PluginApiPreviewAcknowledgement = PluginApiPreviewAcknowledgement
}

/**
 * Drops a documentable that carries the `@InternalFilmstripApi` annotation, or that is the annotation class.
 */
class SuppressInternalApiTransformer(
  context: DokkaContext,
) : SuppressedByConditionDocumentableFilterTransformer(context) {
  override fun shouldBeSuppressed(d: Documentable): Boolean {
    if (d.dri == INTERNAL_API) return true
    val annotations = (d as? WithExtraProperties<*>)?.extra?.allOfType<Annotations>().orEmpty()
    return annotations.any { extra ->
      extra.directAnnotations.values.any { list -> list.any { it.dri == INTERNAL_API } }
    }
  }
}

private val INTERNAL_API = DRI(packageName = "dev.jordond.filmstrip", classNames = "InternalFilmstripApi")
