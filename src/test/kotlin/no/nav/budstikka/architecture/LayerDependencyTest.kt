package no.nav.budstikka.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo

class LayerDependencyTest :
    FunSpec({

        val testContext = TestContext()

        test("application and domain packages exist") {
            with(testContext) {
                check(Files.isDirectory(projectRoot)) {
                    "Project root does not exist: $projectRoot"
                }
                rules.forEach { rule ->
                    check(Files.isDirectory(rule.directory)) {

                        "Missing directory: ${rule.directory}"
                    }
                }
            }
        }

        test("application and domain must not depend on infrastructure or bootstrap") {
            with(testContext) {
                rules
                    .flatMap { rule ->
                        Files.walk(rule.directory).use { paths ->
                            paths
                                .filter { it.extension == "kt" }
                                .toList()
                                .flatMap { file ->
                                    file
                                        .readLines()
                                        .withIndex()
                                        .flatMap { (lineNumber, line) ->
                                            rule.forbiddenPackages
                                                .filter(line::contains)
                                                .map { forbidden ->
                                                    "${file.relativeTo(projectRoot)}:${lineNumber + 1} references $forbidden"
                                                }
                                        }
                                }
                        }
                    }.shouldBeEmpty()
            }
        }
    })

private class TestContext {
    val projectRoot: Path = Path.of("src/main/kotlin/no/nav/budstikka")
    val rules =
        listOf(
            LayerRule(
                projectRoot.resolve("application"),
                forbiddenDependencies,
            ),
            LayerRule(
                projectRoot.resolve("domain"),
                forbiddenDependencies + listOf("$BASE_PACKAGE.application."),
            ),
        )
}

private const val BASE_PACKAGE = "no.nav.budstikka"

private val forbiddenDependencies =
    listOf(
        "$BASE_PACKAGE.infrastructure.",
        "$BASE_PACKAGE.bootstrap.",
    )

private data class LayerRule(
    val directory: Path,
    val forbiddenPackages: List<String>,
)
