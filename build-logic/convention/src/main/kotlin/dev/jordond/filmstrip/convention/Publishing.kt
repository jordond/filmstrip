package dev.jordond.filmstrip.convention

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * POM metadata for every published module, in Kotlin rather than `POM_*` properties.
 *
 * Coordinates come from the Gradle project name, so `:filmstrip-core` publishes as
 * `dev.jordond.filmstrip:filmstrip-core`. The version comes from `VERSION_NAME`, which a release
 * overrides with `ORG_GRADLE_PROJECT_VERSION_NAME`.
 */
internal fun Project.configurePublishing() {
  val repo = "https://github.com/jordond/filmstrip"

  extensions.configure<MavenPublishBaseExtension> {
    coordinates(groupId = version("group"), artifactId = name)
    publishToMavenCentral()
    signAllPublications()

    pom {
      name.set(this@configurePublishing.name)
      description.set("A Kotlin Multiplatform video encoding and editing library.")
      inceptionYear.set("2026")
      url.set(repo)

      licenses {
        license {
          name.set("MIT License")
          url.set("$repo/blob/main/LICENSE")
          distribution.set("repo")
        }
      }

      developers {
        developer {
          id.set("jordond")
          name.set("Jordon de Hoog")
          url.set("https://github.com/jordond")
        }
      }

      scm {
        url.set(repo)
        connection.set("scm:git:git://github.com/jordond/filmstrip.git")
        developerConnection.set("scm:git:ssh://git@github.com/jordond/filmstrip.git")
      }
    }
  }
}
