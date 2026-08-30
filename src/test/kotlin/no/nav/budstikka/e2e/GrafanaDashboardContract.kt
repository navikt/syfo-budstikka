package no.nav.budstikka.e2e

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun assertGrafanaDashboardContract(dashboard: JsonObject) {
    val panels = dashboard.panels()
    val producerShadowPanels = panels.filter { it.id() in PRODUCER_SHADOW_PANEL_IDS }
    val budstikkaPanels = panels.filterNot { it.id() in PRODUCER_SHADOW_PANEL_IDS }
    val prometheusQueries = panels.queries("prometheus")
    val producerShadowQueries = producerShadowPanels.queries("prometheus")
    val budstikkaQueries = budstikkaPanels.queries("prometheus")
    val lokiQueries = panels.queries("loki")

    prometheusQueries shouldNotBe emptyList<String>()
    prometheusQueries.forEach { expression ->
        expression shouldNotContain "or vector("
        expression shouldNotContain "vector(100)"
        expression shouldNotContain "ceil("

        val metricSelectors = PROMETHEUS_METRIC_SELECTOR.findAll(expression).map(MatchResult::value).toList()
        metricSelectors shouldNotBe emptyList<String>()
        metricSelectors.forEach { selector ->
            selector shouldContain """namespace="team-esyfo""""
            selector shouldContain "k8s_cluster_name=\"\$environment\""
        }
    }
    budstikkaQueries.forEach { expression ->
        PROMETHEUS_METRIC_SELECTOR.findAll(expression).forEach { match ->
            match.value shouldContain """app="syfo-budstikka""""
        }
    }
    producerShadowQueries.forEach { expression ->
        PROMETHEUS_METRIC_SELECTOR.findAll(expression).forEach { match ->
            match.value.startsWith("syfo_oppfolgingsplan_backend_").shouldBeTrue()
            match.value shouldContain """app="syfo-oppfolgingsplan-backend""""
            match.value shouldContain """message_type="OPPFOLGINGSPLAN_CREATED""""
        }
    }

    val lagQuery = prometheusQueries.single { it.contains("kafka_consumer_fetch_manager_records_lag_max") }
    lagQuery shouldContain "max by (partition)"
    lagQuery shouldContain """client_id=~"consumer-syfo-budstikka-budstikka-v1-.+""""

    val consumedQuery =
        prometheusQueries.single { it.contains("kafka_consumer_fetch_manager_records_consumed_total") }
    consumedQuery shouldNotContain "by (partition)"
    consumedQuery shouldContain """client_id=~"consumer-syfo-budstikka-budstikka-v1-.+""""

    lokiQueries shouldNotBe emptyList<String>()
    lokiQueries.forEach { expression ->
        expression shouldContain """service_name="syfo-budstikka""""
        expression shouldContain """service_namespace="team-esyfo""""
        expression shouldContain "k8s_cluster_name=\"\$environment\""
    }

    assertNoDataSemantics(panels)
    assertProducerShadow(dashboard, producerShadowPanels)
    assertSafeEventTrace(dashboard, panels)
    assertSafeErrorProjection(panels)
    assertVariables(dashboard)
}

private fun assertProducerShadow(
    dashboard: JsonObject,
    producerShadowPanels: List<JsonObject>,
) {
    producerShadowPanels.map { it.id() }.toSet() shouldBe PRODUCER_SHADOW_PANEL_IDS
    producerShadowPanels.forEach(::assertNeutralNoObservations)

    val rows =
        dashboard
            .getValue("layout")
            .jsonObject
            .getValue("spec")
            .jsonObject
            .getValue("rows")
            .jsonArray
            .map { it.jsonObject }
    rows[1]
        .getValue("spec")
        .jsonObject
        .getValue("title")
        .jsonPrimitive.content shouldBe PRODUCER_SHADOW_ROW_TITLE
    val shadowRow =
        rows
            .single {
                it
                    .getValue("spec")
                    .jsonObject
                    .getValue("title")
                    .jsonPrimitive.content == PRODUCER_SHADOW_ROW_TITLE
            }.getValue("spec")
            .jsonObject
    shadowRow
        .getValue("collapse")
        .jsonPrimitive.boolean
        .shouldBeFalse()
    val shadowItems =
        shadowRow
            .getValue("layout")
            .jsonObject
            .getValue("spec")
            .jsonObject
            .getValue("items")
            .jsonArray
            .map { it.jsonObject.getValue("spec").jsonObject }
    val shadowElements =
        shadowItems
            .map {
                it
                    .getValue("element")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            }.toSet()
    shadowElements shouldBe PRODUCER_SHADOW_PANEL_IDS.map { "panel-$it" }.toSet()
    shadowItems.forEach {
        it
            .getValue("width")
            .jsonPrimitive.content
            .toInt() shouldBe 6
        it
            .getValue("height")
            .jsonPrimitive.content
            .toInt() shouldBe 6
    }
    shadowItems
        .map {
            it
                .getValue("x")
                .jsonPrimitive.content
                .toInt()
        }.toSet() shouldBe setOf(0, 6, 12, 18)

    val oldestDue = producerShadowPanels.single { it.id() == 23 }
    oldestDue.queries("prometheus").single().apply {
        shouldContain("max(syfo_oppfolgingsplan_backend_outbox_oldest_due_age_seconds{")
        shouldNotContain("sum(")
    }
    oldestDue.description() shouldContain "not an SLA or pager"
    oldestDue.description() shouldContain "available_at"

    val queueSnapshot = producerShadowPanels.single { it.id() == 24 }
    queueSnapshot.queries("prometheus").apply {
        shouldHaveSize(3)
        forEach { it.startsWith("max(").shouldBeTrue() }
        any { it.contains("outbox_due_ready") }.shouldBeTrue()
        any { it.contains("outbox_retrying") }.shouldBeTrue()
        any { it.contains("outbox_expired_claims") }.shouldBeTrue()
    }
    queueSnapshot.description() shouldContain "not proof"

    val lifecycle = producerShadowPanels.single { it.id() == 25 }
    lifecycle.queries("prometheus").apply {
        any { it.contains("outbox_enqueued_total") }.shouldBeTrue()
        any { it.contains("outbox_terminal_total") }.shouldBeTrue()
        any { it.contains("outbox_messages_total") }.shouldBeTrue()
    }
    lifecycle.description() shouldContain "not durable reconciliation"

    val latency = producerShadowPanels.single { it.id() == 26 }
    latency.queries("prometheus").apply {
        shouldHaveSize(2)
        any { it.contains("latency_seconds_sum") && it.contains("latency_seconds_count") }.shouldBeTrue()
        any { it.contains("latency_seconds_max") && it.contains("latency_seconds_count") }.shouldBeTrue()
        forEach {
            it shouldContain """outcome="handler_acknowledged""""
            it shouldNotContain "histogram_quantile"
        }
        single { it.contains("latency_seconds_sum") } shouldContain "> 0)"
    }
    latency.description() shouldContain "cannot prove 28-day compliance"
    latency.description() shouldContain "sampled rolling per-pod Timer max"

    listOf(oldestDue, latency).forEach { panel ->
        val thresholdValues =
            panel
                .defaultFieldConfig()
                .getValue("thresholds")
                .jsonObject
                .getValue("steps")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("value")
                        .jsonPrimitive.content
                }
        thresholdValues.contains("300").shouldBeTrue()
    }

    producerShadowPanels.queries("prometheus").forEach { expression ->
        expression shouldNotContain "delivery_total"
        expression shouldNotContain "inbox_message_"
        expression shouldNotContain "inbox_dead_letter"
        expression shouldNotContain "kafka_consumer_"
    }
}

private fun assertNoDataSemantics(panels: List<JsonObject>) {
    val deliveryRatio = panels.single { it.id() == 20 }
    deliveryRatio.title() shouldBe "Recorded Downstream Acceptance %"
    deliveryRatio.noValue() shouldBe "No observations"
    deliveryRatio.description() shouldContain "not proof of end-user delivery"
    assertNeutralNoObservations(deliveryRatio)
    deliveryRatio
        .vizOptions()
        .getValue("colorMode")
        .jsonPrimitive.content shouldBe "value"
    val deliveryRatioQuery = deliveryRatio.queries("prometheus").single()
    val deliverySentSum =
        """sum(increase(delivery_total{$PROMETHEUS_SCOPE, result="sent"}[${'$'}__range]))"""
    val deliveryOutcomeSum =
        """sum(increase(delivery_total{$PROMETHEUS_SCOPE}[${'$'}__range]))"""
    deliveryRatioQuery shouldContain "($deliverySentSum or on() vector(0))"
    deliveryRatioQuery shouldContain "/ ($deliveryOutcomeSum > 0)"

    val inboxRatio = panels.single { it.id() == 22 }
    inboxRatio.title() shouldBe "Recorded Inbox Outcomes without Failure %"
    inboxRatio.noValue() shouldBe "No observations"
    inboxRatio.description() shouldContain "not represented"
    assertNeutralNoObservations(inboxRatio)
    inboxRatio
        .vizOptions()
        .getValue("colorMode")
        .jsonPrimitive.content shouldBe "value"
    val inboxRatioQuery = inboxRatio.queries("prometheus").single()
    val droppedSum =
        """sum(increase(inbox_message_dropped_total{$PROMETHEUS_SCOPE}[${'$'}__range]))"""
    val droppedZeroDefault = "($droppedSum or on() vector(0))"
    Regex(Regex.escape(droppedZeroDefault)).findAll(inboxRatioQuery).count() shouldBe 2
    inboxRatioQuery shouldContain ") > 0)"

    val processedSum =
        """sum(increase(inbox_message_processed_total{$PROMETHEUS_SCOPE}[${'$'}__range]))"""
    val failedSum =
        """sum(increase(inbox_message_failed_total{$PROMETHEUS_SCOPE}[${'$'}__range]))"""
    inboxRatioQuery shouldNotContain "$processedSum or on() vector(0)"
    inboxRatioQuery shouldNotContain "$failedSum or on() vector(0)"

    val handoffCount = panels.single { it.id() == 16 }
    assertNeutralNoObservations(handoffCount)
    handoffCount
        .vizOptions()
        .getValue("colorMode")
        .jsonPrimitive.content shouldBe "value"

    val dropReasons = panels.single { it.id() == 3 }
    assertNeutralNoObservations(dropReasons)
    dropReasons
        .vizOptions()
        .getValue("valueMode")
        .jsonPrimitive.content shouldBe "color"

    val help =
        panels
            .single { it.id() == 201 }
            .getValue("vizConfig")
            .jsonObject
            .getValue("spec")
            .jsonObject
            .getValue("options")
            .jsonObject
            .getValue("content")
            .jsonPrimitive.content
    help shouldContain "not authoritative database state"
    help shouldContain "No data is not green"
    help shouldNotContain "expected if dedup"
}

private fun assertNeutralNoObservations(panel: JsonObject) {
    val mapping =
        panel
            .defaultFieldConfig()
            .getValue("mappings")
            .jsonArray
            .single()
            .jsonObject
    mapping.getValue("type").jsonPrimitive.content shouldBe "special"
    mapping.getValue("options").jsonObject.apply {
        getValue("match").jsonPrimitive.content shouldBe "null"
        getValue("result").jsonObject.apply {
            getValue("text").jsonPrimitive.content shouldBe "No observations"
            getValue("color").jsonPrimitive.content shouldBe "gray"
        }
    }
}

private fun assertSafeEventTrace(
    dashboard: JsonObject,
    panels: List<JsonObject>,
) {
    val eventVariables =
        dashboard
            .getValue("variables")
            .jsonArray
            .map { it.jsonObject }
            .filter {
                it.getValue("kind").jsonPrimitive.content == "TextVariable" &&
                    it
                        .getValue("spec")
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive.content == "eventId"
            }
    eventVariables shouldHaveSize 1
    eventVariables.single().getValue("spec").jsonObject.apply {
        getValue("label").jsonPrimitive.content shouldBe "Event ID"
        getValue("skipUrlSync").jsonPrimitive.boolean.shouldBeTrue()
        getValue("current").jsonObject.apply {
            getValue("text").jsonPrimitive.content shouldBe ""
            getValue("value").jsonPrimitive.content shouldBe ""
        }
    }

    val tracePanel = panels.single { it.id() == 15 }
    tracePanel.title() shouldBe "Event Trace"
    tracePanel.description().lowercase() shouldNotContain "reference"
    tracePanel.logDetailsEnabled().shouldBeFalse()

    val traceQueries = tracePanel.queries("loki")
    traceQueries shouldHaveSize 4
    traceQueries.forEach { expression ->
        expression shouldContain "event_id!=\"\""
        expression shouldContain "event_id=\${eventId:doublequote}"
        expression shouldContain "| json event_id,"
        expression shouldContain "| line_format"
        expression shouldNotContain "reference"
        expression shouldNotContain ":regex"

        val projectedFields = expression.jsonProjectionFields()
        projectedFields shouldNotBe emptyList<String>()
        projectedFields.forEach { field ->
            TRACE_LOG_FIELDS.contains(field).shouldBeTrue()
        }

        val projection = expression.substringAfter("| line_format")
        assertSafeLineFormat(projection)
    }

    val eventTraceRow =
        dashboard
            .getValue("layout")
            .jsonObject
            .getValue("spec")
            .jsonObject
            .getValue("rows")
            .jsonArray
            .map { it.jsonObject }
            .single {
                it
                    .getValue("spec")
                    .jsonObject
                    .getValue("title")
                    .jsonPrimitive.content == "Event Trace"
            }.getValue("spec")
            .jsonObject
    eventTraceRow
        .getValue("collapse")
        .jsonPrimitive.boolean
        .shouldBeTrue()
}

private fun assertSafeErrorProjection(panels: List<JsonObject>) {
    val logPanels = panels.filter { it.vizGroup() == "logs" }
    logPanels.map { it.id() }.toSet() shouldBe setOf(14, 15)
    logPanels.forEach { panel ->
        panel.logDetailsEnabled().shouldBeFalse()
        panel.queries("loki").forEach { query ->
            query shouldContain "| json "
            query shouldContain "| line_format"
            val projectedFields = query.jsonProjectionFields()
            projectedFields shouldNotBe emptyList<String>()
            projectedFields.forEach { field ->
                SAFE_LOG_FIELDS.contains(field).shouldBeTrue()
            }
            assertSafeLineFormat(query.substringAfter("| line_format"))
        }
    }

    val errorLogs = panels.single { it.id() == 14 }
    val query = errorLogs.queries("loki").single()
    query shouldContain "| json level, logger_name, event_id"
    query shouldContain "| line_format"
    query shouldNotContain "reference"
    query.jsonProjectionFields() shouldBe ERROR_LOG_FIELDS
}

private fun assertVariables(dashboard: JsonObject) {
    val variables = dashboard.getValue("variables").jsonArray.map { it.jsonObject }
    variables
        .filter { it.getValue("kind").jsonPrimitive.content == "DatasourceVariable" }
        .forEach {
            it
                .getValue("spec")
                .jsonObject
                .getValue("allowCustomValue")
                .jsonPrimitive.boolean
                .shouldBeFalse()
        }

    val environment =
        variables.single {
            it.getValue("kind").jsonPrimitive.content == "QueryVariable" &&
                it
                    .getValue("spec")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content == "environment"
        }
    environment.getValue("spec").jsonObject.apply {
        getValue("allowCustomValue").jsonPrimitive.boolean.shouldBeFalse()
        getValue("includeAll").jsonPrimitive.boolean.shouldBeFalse()
        getValue("multi").jsonPrimitive.boolean.shouldBeFalse()
        getValue("current")
            .jsonObject
            .getValue("value")
            .jsonPrimitive.content shouldBe "prod"
    }
}

private fun JsonObject.panels(): List<JsonObject> =
    getValue("elements")
        .jsonObject.values
        .map { it.jsonObject }
        .filter { it.getValue("kind").jsonPrimitive.content == "Panel" }
        .map { it.getValue("spec").jsonObject }

private fun List<JsonObject>.queries(group: String): List<String> =
    flatMap { panel ->
        panel
            .getValue("data")
            .jsonObject
            .getValue("spec")
            .jsonObject
            .getValue("queries")
            .jsonArray
            .map { it.jsonObject }
    }.map {
        it
            .getValue("spec")
            .jsonObject
            .getValue("query")
            .jsonObject
    }.filter { it.getValue("group").jsonPrimitive.content == group }
        .map {
            it
                .getValue("spec")
                .jsonObject
                .getValue("expr")
                .jsonPrimitive.content
        }

private fun JsonObject.queries(group: String): List<String> = listOf(this).queries(group)

private fun JsonObject.id(): Int = getValue("id").jsonPrimitive.content.toInt()

private fun JsonObject.title(): String = getValue("title").jsonPrimitive.content

private fun JsonObject.description(): String = getValue("description").jsonPrimitive.content

private fun JsonObject.vizGroup(): String =
    getValue("vizConfig")
        .jsonObject
        .getValue("group")
        .jsonPrimitive.content

private fun JsonObject.noValue(): String =
    defaultFieldConfig()
        .getValue("noValue")
        .jsonPrimitive.content

private fun JsonObject.defaultFieldConfig(): JsonObject =
    getValue("vizConfig")
        .jsonObject
        .getValue("spec")
        .jsonObject
        .getValue("fieldConfig")
        .jsonObject
        .getValue("defaults")
        .jsonObject

private fun JsonObject.vizOptions(): JsonObject =
    getValue("vizConfig")
        .jsonObject
        .getValue("spec")
        .jsonObject
        .getValue("options")
        .jsonObject

private fun JsonObject.logDetailsEnabled(): Boolean =
    getValue("vizConfig")
        .jsonObject
        .getValue("spec")
        .jsonObject
        .getValue("options")
        .jsonObject
        .getValue("enableLogDetails")
        .jsonPrimitive.boolean

private fun String.jsonProjectionFields(): List<String> =
    substringAfter("| json ", missingDelimiterValue = "")
        .substringBefore(" |")
        .split(",")
        .map(String::trim)
        .filter(String::isNotEmpty)

private fun assertSafeLineFormat(lineFormat: String) {
    val returnedFields =
        Regex("""\{\{\.([a-zA-Z0-9_]+)}}""")
            .findAll(lineFormat)
            .map { it.groupValues[1] }
            .toList()
    returnedFields shouldNotBe emptyList<String>()
    returnedFields.forEach { field ->
        SAFE_LOG_FIELDS.contains(field).shouldBeTrue()
    }
    lineFormat shouldNotContain "__line__"
}

private const val PROMETHEUS_SCOPE =
    """app="syfo-budstikka", namespace="team-esyfo", k8s_cluster_name="${'$'}environment""""

private val PROMETHEUS_METRIC_SELECTOR =
    Regex("""(?:[a-zA-Z_:][a-zA-Z0-9_:]*)?\{[^{}]*}""")

private val PRODUCER_SHADOW_PANEL_IDS = setOf(23, 24, 25, 26)

private const val PRODUCER_SHADOW_ROW_TITLE =
    "Shadow: OPPFOLGINGSPLAN_CREATED — producer leg only · E2E IKKE EVALUERT"

private val ERROR_LOG_FIELDS =
    listOf(
        "level",
        "logger_name",
        "event_id",
        "worker",
        "consumer",
        "delivery_channel",
        "error_type",
        "cause_type",
    )

private val TRACE_LOG_FIELDS =
    setOf(
        "event_id",
        "topic",
        "partition",
        "kafka_offset",
        "result",
        "delivery_count",
        "delivery_channel",
    )

private val SAFE_LOG_FIELDS = ERROR_LOG_FIELDS.toSet() + TRACE_LOG_FIELDS
