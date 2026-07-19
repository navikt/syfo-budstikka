package no.nav.budstikka.testsupport

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lokal produksjons-endepunkt (B50): lar deg sende Kafka-meldinger med `curl` mens appen kjører * mot testcontainers. Kun installert via [BudstikkaTestApp.start]-parameteren `localRoutes`,
 * aldri i prod. * * ```bash * curl -X POST http://localhost:<port>/local/produce \ *   -H 'Content-Type: application/json' \ *   -d '{"topic":"team-esyfo.budstikka.v1","key":"12345678901","value":"...","headers":{"eventId":"..."} }' * ``` * * Ruten installeres under modul-init (før [BudstikkaTestApp] er opprettet), men brukes først * ved første HTTP-forespørsel — da er [app] allerede satt. */
internal lateinit var app: BudstikkaTestApp

/** Installerer POST `/local/produce`-ruten. Må kalles i [BudstikkaTestApp.start]s `localRoutes`-lambda. */
fun Application.configureLocalProduceApi() {
    routing {
        post("/local/produce") {
            val req = Json.decodeFromString<ProduceRequest>(call.receiveText())
            app.produce(
                topic = req.topic,
                key = req.key,
                value = req.value,
                headers = req.headers,
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class ProduceRequest(
    val topic: String,
    val key: String? = null,
    val value: String,
    val headers: Map<String, String> = emptyMap(),
)
