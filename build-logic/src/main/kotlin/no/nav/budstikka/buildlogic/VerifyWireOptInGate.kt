package no.nav.budstikka.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.assign
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Compiles the raw-wire fixture with and without the opt-in, and asserts the difference.
 *
 * It drives the Kotlin CLI compiler the build already resolves for its own compilations, so the check
 * needs no new dependency and can never disagree with the compiler that builds the artifact.
 */
abstract class VerifyWireOptInGate : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureDirectory: DirectoryProperty

    @get:Classpath
    abstract val kotlinCompilerClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val contractClasspath: ConfigurableFileCollection

    @get:Input
    abstract val optInMarker: Property<String>

    @get:Input
    abstract val jvmTarget: Property<String>

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:OutputDirectory
    abstract val workDirectory: DirectoryProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        val sources =
            fixtureDirectory.asFileTree
                .matching { include("**/*.kt") }
                .files
                .sortedBy { it.absolutePath }
        check(sources.isNotEmpty()) { "No fixture sources found in ${fixtureDirectory.get()}." }

        val withOptIn = compile("with-opt-in", sources, optIn = true)
        check(withOptIn.exitValue == 0) {
            "The raw-wire fixture must compile WHEN the caller opts in; otherwise this task proves nothing " +
                "about the gate. Compiler output:\n${withOptIn.output}"
        }

        val withoutOptIn = compile("without-opt-in", sources, optIn = false)
        check(withoutOptIn.exitValue != 0) {
            "The raw wire compiled WITHOUT '${optInMarker.get()}'. The producer boundary is gone: envelope " +
                "and unsupported-variant DTOs are ordinary public API again."
        }

        val errors = withoutOptIn.output.lines().filter { ERROR_LINE.containsMatchIn(it) }
        val unexpected = errors.filterNot { it.contains(OPT_IN_EVIDENCE) }
        check(unexpected.isEmpty()) {
            "The fixture failed for reasons other than the opt-in gate, so the failure is not evidence:\n" +
                unexpected.joinToString("\n")
        }

        val expectedErrorLocations =
            sources.flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    if (OPT_IN_PROBE in line) ErrorLocation(source.name, index + 1) else null
                }
            }
        check(expectedErrorLocations.isNotEmpty()) {
            "No '$OPT_IN_PROBE' markers found in the raw-wire fixtures."
        }

        val actualErrorLocations =
            errors
                .mapNotNull { line ->
                    ERROR_LOCATION.find(line)?.destructured?.let { (fileName, lineNumber) ->
                        ErrorLocation(fileName, lineNumber.toInt())
                    }
                }.toSet()
        val ungated = expectedErrorLocations.filterNot(actualErrorLocations::contains)
        check(ungated.isEmpty()) {
            "These raw-wire probes compiled WITHOUT '${optInMarker.get()}': ${ungated.joinToString()}"
        }

        logger.lifecycle(
            "Wire opt-in gate verified: ${sources.size} fixture files compile with " +
                "-opt-in=${optInMarker.get()} and are rejected without it (${errors.size} opt-in errors).",
        )
    }

    private fun compile(
        name: String,
        sources: List<File>,
        optIn: Boolean,
    ): CompilerRun {
        val output = ByteArrayOutputStream()
        val destination = workDirectory.get().dir(name).asFile
        destination.deleteRecursively()
        val javaExecutable =
            javaLauncher
                .get()
                .executablePath
                .asFile
                .absolutePath
        val result =
            execOperations.javaexec {
                executable = javaExecutable
                classpath = kotlinCompilerClasspath
                mainClass = KOTLIN_CLI_MAIN_CLASS
                args =
                    buildList {
                        add("-no-stdlib")
                        add("-no-reflect")
                        add("-nowarn")
                        add("-jvm-target")
                        add(jvmTarget.get())
                        add("-classpath")
                        add(contractClasspath.asPath)
                        add("-d")
                        add(destination.absolutePath)
                        if (optIn) add("-opt-in=${optInMarker.get()}")
                        addAll(sources.map { it.absolutePath })
                    }
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
        return CompilerRun(result.exitValue, output.toString(Charsets.UTF_8))
    }

    private data class CompilerRun(
        val exitValue: Int,
        val output: String,
    )

    private data class ErrorLocation(
        val fileName: String,
        val lineNumber: Int,
    )

    private companion object {
        const val KOTLIN_CLI_MAIN_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        /** The CLI compiler prints `<path>:<line>:<column>: error: <message>`. */
        val ERROR_LINE = Regex("""(^|\s)error: """)
        val ERROR_LOCATION = Regex("""([^/\\\s]+\.kt):(\d+):\d+: error:""")

        /** Distinctive words from the marker's own `@RequiresOptIn` message. */
        const val OPT_IN_EVIDENCE = "raw wire API"
        const val OPT_IN_PROBE = "// opt-in-probe"
    }
}
