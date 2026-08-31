package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import javax.inject.Inject

/**
 * Boots one simulator device and waits until it has finished coming up.
 *
 * A test spawned into a device needs that device already booted, since `simctl spawn` boots none
 * itself. `simctl bootstatus -b` boots a shut down device and returns straight away for one that is
 * already up, so a test task can depend on this unconditionally.
 */
abstract class BootSimulatorTask : DefaultTask() {
  /**
   * The device name or UDID to boot.
   */
  @get:Input
  abstract val device: Property<String>

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun boot() {
    execOperations.exec {
      commandLine("xcrun", "simctl", "bootstatus", device.get(), "-b")
    }
  }
}

/**
 * Makes `iosSimulatorArm64Test` spawn into a booted simulator instead of running standalone.
 *
 * AVFoundation reaches mediaserverd over XPC, and a standalone spawn is given no bootstrap to reach
 * it through: every AVPlayerItem fails with -12746 and CIContext() answers nil. Spawning into a
 * booted device is what gives the test process the services a real app has, and simctl boots none
 * on its own.
 */
fun Project.bootIosSimulatorForTests() {
  val simulatorDevice = objects.property<String>()

  val bootIosSimulator =
    tasks.register<BootSimulatorTask>("bootIosSimulator") {
      description = "Boots the simulator the iOS test task spawns into."
      device.set(simulatorDevice)
    }

  tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    if (name != "iosSimulatorArm64Test") return@configureEach

    standalone.set(false)
    simulatorDevice.set(device)
    dependsOn(bootIosSimulator)
  }
}
