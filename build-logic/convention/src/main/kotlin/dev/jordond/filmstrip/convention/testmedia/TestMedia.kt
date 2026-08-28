package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * The fixture tasks a module gets, and where the clips land.
 *
 * @property download What a test depends on. Fetches the published clips and refuses any whose
 *   bytes are not the ones the manifest names.
 * @property generate Rolls the clips locally with host ffmpeg. Needed only to change one.
 * @property publish Writes the manifest for whatever [generate] produced and pushes it.
 * @property directory Where the clips end up, whichever task put them there.
 */
class TestMedia(
    val download: TaskProvider<DownloadTestMediaTask>,
    val generate: TaskProvider<GenerateTestMediaTask>,
    val publish: TaskProvider<PublishTestMediaTask>,
    val directory: File,
)

/**
 * Registers the fixture tasks for a module.
 *
 * Tests read [TestMedia.directory] and depend on [TestMedia.download], so a runner needs no ffmpeg
 * to run them. Changing a clip means editing [specs], running the generate task, then the publish
 * one, and committing the manifest it writes.
 *
 * @param name What the tasks are named after, so a build spanning several modules reads clearly.
 * @param specs The clips, which [GenerateTestMediaTask] both encodes and verifies.
 */
fun Project.testMedia(
    name: String,
    specs: List<FixtureSpec>,
): TestMedia {
    val directory = layout.buildDirectory.dir("test-fixtures").get().asFile
    val manifestFile = layout.projectDirectory.file(TestMediaManifest.FILE_NAME).asFile
    val capitalised = name.replaceFirstChar { it.uppercase() }

    val generate =
        tasks.register("generate${capitalised}TestMedia", GenerateTestMediaTask::class.java) {
            group = TASK_GROUP
            description = "Encodes the $name fixtures with host ffmpeg. Only needed to change one."
            this.specs.set(specs)
            outputDirectory.set(directory)
            manifest.set(layout.buildDirectory.file("test-fixtures/fixtures.txt"))
        }

    val download =
        tasks.register("download${capitalised}TestMedia", DownloadTestMediaTask::class.java) {
            group = TASK_GROUP
            description = "Fetches the published $name fixtures."
            manifest.set(manifestFile)
            outputDirectory.set(directory)
            // Outside the project tree on purpose: one fetch serves every module, branch and
            // worktree on the machine.
            cacheDirectory.set(gradle.gradleUserHomeDir.resolve("filmstrip/test-media"))
            baseUrl.set(TestMediaManifest.HOST)
        }

    val publish =
        tasks.register("publish${capitalised}TestMedia", PublishTestMediaTask::class.java) {
            group = TASK_GROUP
            description = "Publishes the $name fixtures and writes the manifest that pins them."
            dependsOn(generate)
            fixtures.set(directory)
            manifest.set(manifestFile)
        }

    return TestMedia(download, generate, publish, directory)
}

private const val TASK_GROUP = "test media"
