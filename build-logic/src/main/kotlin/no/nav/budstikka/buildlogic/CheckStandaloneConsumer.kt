package no.nav.budstikka.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Runs a separate Kotlin 2.3 producer build against a file Maven repository. It deliberately uses
 * neither a project dependency nor Gradle's global Maven cache as a publication source. The nested
 * build reuses the already-running Gradle installation instead of resolving a wrapper distribution
 * into its isolated Gradle home; only dependency and metadata resolution stays isolated.
 */
abstract class CheckStandaloneConsumer : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gradleExecutable: RegularFileProperty

    @get:Input
    abstract val publishedVersion: Property<String>

    @get:OutputDirectory
    abstract val workDirectory: DirectoryProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @TaskAction
    fun check() {
        val work = workDirectory.get().asFile
        work.deleteRecursively()
        work.mkdirs()
        execOperations.exec {
            workingDir = fixtureDirectory.get().asFile
            executable = gradleExecutable.get().asFile.absolutePath
            args(
                "--no-daemon",
                "--gradle-user-home",
                work.resolve("gradle-user-home").absolutePath,
                "--project-cache-dir",
                work.resolve("project-cache").absolutePath,
                "-p",
                fixtureDirectory.get().asFile.absolutePath,
                "check",
                "-PcontractRepository=${repositoryDirectory.get().asFile.toURI()}",
                "-PcontractVersion=${publishedVersion.get()}",
                "-PconsumerBuildDirectory=${work.resolve("consumer-build").absolutePath}",
            )
        }
    }
}
