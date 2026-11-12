package dev.jordond.filmstrip.convention

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPomLicenseSpec
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * A lock every upload to a repository takes, so the modules of a build upload one at a time.
 *
 * The snapshot repository answers 429 to modules uploading side by side.
 */
internal abstract class UploadLock : BuildService<BuildServiceParameters.None>

/**
 * POM metadata for every published module, in Kotlin rather than `POM_*` properties.
 *
 * Coordinates come from the Gradle project name, so `:filmstrip-core` publishes as
 * `dev.jordond.filmstrip:filmstrip-core`. The version comes from `VERSION_NAME`, which a release
 * overrides with `ORG_GRADLE_PROJECT_VERSION_NAME`.
 *
 * A release is signed. A `-SNAPSHOT` version is not, since the snapshot repository validates nothing and a signature
 * and its checksums are half of what a module uploads.
 */
internal fun Project.configurePublishing(license: (MavenPomLicenseSpec.() -> Unit)? = null) {
  val repo = "https://github.com/jordond/filmstrip"
  val uploads =
    gradle.sharedServices.registerIfAbsent(
      "uploadLock",
      UploadLock::class.java,
    ) { maxParallelUsages.set(1) }
  tasks.withType<PublishToMavenRepository>().configureEach { usesService(uploads) }

  extensions.configure<MavenPublishBaseExtension> {
    coordinates(groupId = version("group"), artifactId = name)
    publishToMavenCentral(automaticRelease = true)
    if (!providers.gradleProperty("VERSION_NAME").get().endsWith("-SNAPSHOT")) signAllPublications()

    pom {
      name.set(this@configurePublishing.name)
      description.set("A Kotlin Multiplatform video encoding and editing library.")
      inceptionYear.set("2026")
      url.set(repo)

      licenses {
        if (license == null) {
          license {
            name.set("MIT License")
            url.set("$repo/blob/main/LICENSE")
            distribution.set("repo")
          }
        } else {
          license(this@licenses)
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
