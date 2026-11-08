import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters

plugins {
  id("filmstrip.root")
  alias(libs.plugins.multiplatform) apply false
  alias(libs.plugins.compose) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.multiplatform.library) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.poko) apply false
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.publish) apply false
  alias(libs.plugins.dokka)
  alias(libs.plugins.dependencies)
  alias(libs.plugins.kotlinx.kover)
}

val documented = subprojects.filter { it.name.startsWith("filmstrip") }

// The docs site's look on the API reference: its tokens and fonts, and templates for the header and the dark only page.
val websiteTheme: DokkaExtension.() -> Unit = {
  pluginsConfiguration.getByName<DokkaHtmlPluginParameters>("html").apply {
    customStyleSheets.from(
      rootDir.resolve("website/src/styles/fonts.css"),
      rootDir.resolve("website/src/styles/tokens.css"),
      rootDir.resolve("website/src/styles/dokka.css"),
    )
    templatesDir.set(rootDir.resolve("website/dokka-templates"))
    footerMessage.set("MIT licensed. Pre-alpha, so expect things to change.")
  }
}

dependencies {
  documented.forEach { dokka(project(it.path)) }
}

dokka {
  dokkaPublications.html {
    outputDirectory.set(rootDir.resolve("dokka"))
  }
  websiteTheme()
}

// Every published module's API reference: its Module.md, links to its sources on GitHub and to the coroutines and
// Compose references, with @InternalFilmstripApi declarations left out by :internal:dokka-plugin.
documented.forEach { module ->
  module.pluginManager.withPlugin("org.jetbrains.dokka") {
    // Dokka runs plugins on its own Kotlin stdlib, so the plugin comes without its dependencies.
    val plugin = module.dependencies.project(mapOf("path" to ":internal:dokka-plugin")) as ProjectDependency
    plugin.isTransitive = false
    module.dependencies.add("dokkaPlugin", plugin)

    module.extensions.configure<DokkaExtension> {
      websiteTheme()

      dokkaSourceSets.configureEach {
        includes.from(module.file("Module.md"))

        sourceLink {
          localDirectory.set(module.file("src"))
          remoteUrl("https://github.com/jordond/filmstrip/blob/main/${module.name}/src")
          remoteLineSuffix.set("#L")
        }

        externalDocumentationLinks.register("kotlinx-coroutines") {
          url("https://kotlinlang.org/api/kotlinx.coroutines/")
        }
        externalDocumentationLinks.register("androidx") {
          url("https://developer.android.com/reference/kotlin/")
          packageListUrl("https://developer.android.com/reference/kotlin/androidx/package-list")
        }
      }
    }
  }
}
