package no.nav.budstikka.testsupport

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun BudstikkaTestApp.installLocalProduceApi() {
    server.application.routing {
        post("/local/produce") {
            val req = Json.decodeFromString<ProduceRequest>(call.receiveText())
            produce(
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
