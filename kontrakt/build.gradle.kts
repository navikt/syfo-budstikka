import com.adarshr.gradle.testlogger.theme.ThemeType
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.test.logger)
}

group = rootProject.group
version = rootProject.version

kotlin {
    // Built with the repository toolchain, but the *artifact* targets the producing applications, not
    // this service. Producers verified so far run JVM 25 and Kotlin 2.3.x, so the binary is pinned to
    // the lower bound they can all consume:
    //   - jvmTarget 21 (LTS floor) with -Xjdk-release, so no newer JDK API leaks into the bytecode;
    //   - language/apiVersion 2.3, because a Kotlin 2.3 compiler cannot read metadata written by 2.4.
    // Raising either bound is a breaking change for every producer below it.
    jvmToolchain(
        libs.versions.java
            .get()
            .toInt(),
    )
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // `api`: the contract types are `@Serializable` and `dispatchJson` is public, so producers need
    // kotlinx-serialization on their own compile classpath. Nothing else may be added here — the
    // module must stay free of Ktor, Exposed, Flyway and kafka-clients.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    // ContractPrivacyTest walks the sealed hierarchies via `sealedSubclasses`, which needs Kotlin
    // reflection. Test-only: it must never leak onto `api`/`implementation` or the runtime classpath.
    testImplementation(libs.kotlin.reflect)
}

// --- Compile-time proof of the @InternalBudstikkaWire gate ---------------------------------------
//
// The gate is a compile-time claim, so the evidence has to be a compilation, not an assertion about
// one. Two halves, both wired into `check`:
//
//   1. `producerFixture` — a source set standing in for a producing application. Facade only, no
//      opt-in anywhere. The ordinary Kotlin plugin compiles it, so the build fails if a type a
//      Produsent needs disappears behind the marker.
//   2. `verifyWireOptInGate` — compiles src/wireGateFixture twice and asserts the raw wire is
//      reachable WITH the opt-in and rejected WITHOUT it.
//
// Neither fixture ends up in the jar or on any published classpath.

val producerFixture: SourceSet = sourceSets.create("producerFixture")

dependencies {
    add(producerFixture.implementationConfigurationName, sourceSets.main.get().output)
    add(producerFixture.implementationConfigurationName, libs.kotlinx.serialization.json)
}

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

val verifyWireOptInGate =
    tasks.register<VerifyWireOptInGate>("verifyWireOptInGate") {
        group = "verification"
        description = "Proves the raw wire contract requires an explicit opt-in at compile time."
        dependsOn(tasks.named("classes"))
        fixtureDirectory = layout.projectDirectory.dir("src/wireGateFixture")
        kotlinCompilerClasspath.from(configurations.named("kotlinCompilerClasspath"))
        contractClasspath.from(sourceSets.main.get().output, configurations.named("runtimeClasspath"))
        optInMarker = "no.nav.budstikka.contract.InternalBudstikkaWire"
        jvmTarget = JvmTarget.JVM_21.target
        javaLauncher =
            javaToolchains.launcherFor {
                languageVersion =
                    JavaLanguageVersion.of(
                        libs.versions.java
                            .get()
                            .toInt(),
                    )
            }
        workDirectory = layout.buildDirectory.dir("wire-opt-in-gate")
    }

tasks {
    test {
        useJUnitPlatform()
        testlogger {
            theme = ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
            showSimpleNames = true
        }
    }

    named("check") {
        dependsOn("ktlintCheck")
        dependsOn(producerFixture.classesTaskName)
        dependsOn(verifyWireOptInGate)
    }
}
