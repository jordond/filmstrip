plugins {
  `kotlin-dsl`
  alias(libs.plugins.spotless)
}

kotlin {
  jvmToolchain(
    libs.versions.jvmTarget
      .get()
      .toInt(),
  )
}

spotless {
  kotlin {
    ktlint(libs.versions.ktlint.get()).setEditorConfigPath("${rootDir.parentFile}/.editorconfig")
    target("src/**/*.kt")
  }
  kotlinGradle {
    ktlint(libs.versions.ktlint.get()).setEditorConfigPath("${rootDir.parentFile}/.editorconfig")
    target("*.gradle.kts")
  }
}

dependencies {
  compileOnly(libs.bundles.logic.plugins)
}

gradlePlugin {
  plugins {
    register("filmstripRoot") {
      id = "filmstrip.root"
      implementationClass = "dev.jordond.filmstrip.convention.plugin.RootPlugin"
    }

    register("filmstripLibrary") {
      id = "filmstrip.library"
      implementationClass = "dev.jordond.filmstrip.convention.plugin.LibraryPlugin"
    }

    register("filmstripInternal") {
      id = "filmstrip.internal"
      implementationClass = "dev.jordond.filmstrip.convention.plugin.InternalPlugin"
    }

    register("filmstripComposeLibrary") {
      id = "filmstrip.library.compose"
      implementationClass = "dev.jordond.filmstrip.convention.plugin.ComposeLibraryPlugin"
    }

    register("filmstripJvmLibrary") {
      id = "filmstrip.library.jvm"
      implementationClass = "dev.jordond.filmstrip.convention.plugin.JvmLibraryPlugin"
    }

    register("filmstripAndroidLibrary") {
      id = "filmstrip.library.android"
      implementationClass = "dev.jordond.filmstrip.convention.plugin.AndroidLibraryPlugin"
    }

    register("filmstripAppleLibrary") {
      id = "filmstrip.library.apple"
      implementationClass = "dev.jordond.filmstrip.convention.plugin.AppleLibraryPlugin"
    }

    register("filmstripWebLibrary") {
      id = "filmstrip.library.web"
      implementationClass = "dev.jordond.filmstrip.convention.plugin.WebLibraryPlugin"
    }
  }
}
