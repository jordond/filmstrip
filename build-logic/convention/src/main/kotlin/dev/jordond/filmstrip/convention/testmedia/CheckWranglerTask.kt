package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Fails naming `wrangler` when it is not on [searchPath], before the publish task's generate pass has encoded anything.
 *
 * It does nothing under [noUpload], the publish task's `--no-upload`, since that run never calls `wrangler`. The task
 * has no outputs, so it runs every time.
 */
abstract class CheckWranglerTask : DefaultTask() {
  @get:Internal
  abstract val noUpload: Property<Boolean>

  @get:Internal
  abstract val searchPath: Property<String>

  @TaskAction
  fun check() {
    if (noUpload.getOrElse(false)) return
    requireOnPath(WRANGLER, searchPath.get())
  }
}

internal const val WRANGLER: String = "wrangler"
