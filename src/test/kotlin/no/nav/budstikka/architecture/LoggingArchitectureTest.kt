package no.nav.budstikka.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.infrastructure.HealthResult
import org.slf4j.LoggerFactory
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.MemberRefEntry
import java.nio.file.Files
import java.nio.file.Path

class LoggingArchitectureTest :
    StringSpec({
        "production code uses only the local application logging binding" {
            val classes = Path.of(HealthResult::class.java.protectionDomain.codeSource.location.toURI())
            val violations =
                Files.walk(classes).use { files ->
                    files
                        .filter { it.toString().endsWith(".class") }
                        .filter { isProductionClass(compiledClassName(classes, it)) }
                        .flatMap { file ->
                            val className = compiledClassName(classes, file)
                            forbiddenLogging(Files.readAllBytes(file))
                                .map { "$className: $it" }
                                .stream()
                        }.sorted()
                        .toList()
                }

            violations.shouldBeEmpty()
        }

        "the guard detects native logging even when imports are aliased or omitted" {
            with(forbiddenLogging(classBytes(NativeLoggingFixture::class.java))) {
                this shouldContain "org/slf4j/LoggerFactory"
                this shouldContain "org/slf4j/Logger"
                this shouldContain "org/slf4j/spi/LoggingEventBuilder"
            }
        }

        "the guard detects standard output" {
            with(forbiddenLogging(classBytes(StandardOutputFixture::class.java))) {
                this shouldContain "java/lang/System.out"
                this shouldContain "java/lang/System.err"
                this shouldContain "java/io/PrintStream.print"
                this shouldContain "java/io/PrintStream.println"
                this shouldContain "java/io/PrintStream.printf"
                this shouldContain "java/io/PrintStream.format"
            }
        }

        "the guard detects direct library factory use outside the local binding" {
            forbiddenLogging(classBytes(DirectLibraryFactoryFixture::class.java)) shouldContain
                "no/nav/esyfo/observability/ApplicationLoggerKt"
        }

        "event severity and tracing context are not logger bypasses" {
            forbiddenLogging(classBytes(TracingFixture::class.java)).shouldBeEmpty()
        }

        "the local facade is allowed" {
            forbiddenLogging(classBytes(ApplicationLoggingFixture::class.java)).shouldBeEmpty()
        }
    })

private const val PRODUCTION_PACKAGE = "no/nav/budstikka/"
private const val LOCAL_LOGGING_BINDING = "no/nav/budstikka/application/logging/ApplicationLoggingKt"

private val FORBIDDEN_LOGGING_CLASSES =
    setOf(
        "org/slf4j/Logger",
        "org/slf4j/LoggerFactory",
        "org/slf4j/spi/LoggingEventBuilder",
        "no/nav/esyfo/observability/ApplicationLoggerKt",
    )

private fun compiledClassName(
    classes: Path,
    classFile: Path,
): String = classes.relativize(classFile).joinToString("/") { it.toString() }.removeSuffix(".class")

private fun isProductionClass(className: String): Boolean =
    className.startsWith(PRODUCTION_PACKAGE) && className != LOCAL_LOGGING_BINDING

private fun forbiddenLogging(bytes: ByteArray): List<String> =
    ClassFile
        .of()
        .parse(bytes)
        .constantPool()
        .mapNotNull { entry ->
            when (entry) {
                is ClassEntry -> entry.asInternalName().takeIf { it in FORBIDDEN_LOGGING_CLASSES }
                is MemberRefEntry -> {
                    val owner = entry.owner().asInternalName()
                    val name = entry.nameAndType().name().stringValue()

                    "$owner.$name".takeIf {
                        (owner == "kotlin/io/ConsoleKt" && name in setOf("print", "println")) ||
                            (owner == "java/lang/System" && name in setOf("out", "err")) ||
                            (owner == "java/io/PrintStream" && name in setOf("print", "println", "printf", "format"))
                    }
                }
                else -> null
            }
        }.distinct()

private fun classBytes(type: Class<*>): ByteArray =
    requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))
        .use { it.readAllBytes() }

private class NativeLoggingFixture {
    fun log() = LoggerFactory.getLogger(javaClass).atWarn().log("Unstructured warning")
}

private class StandardOutputFixture {
    fun log() {
        print("Unstructured output")
        println("Unstructured output")
        System.out.printf("%s", "Unstructured output")
        System.err.format("%s", "Unstructured output")
    }
}

private class DirectLibraryFactoryFixture {
    fun logger(native: org.slf4j.Logger) = no.nav.esyfo.observability.createLogger(native)
}

private class TracingFixture {
    fun context() = org.slf4j.MDC.get("trace_id") to org.slf4j.event.Level.ERROR
}

private class ApplicationLoggingFixture {
    private val logger = applicationLogger(javaClass)

    fun log() = logger.info("Lookup completed")
}
