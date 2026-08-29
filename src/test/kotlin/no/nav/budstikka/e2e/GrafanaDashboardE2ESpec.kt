package no.nav.budstikka.e2e

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.budstikka.testsupport.GrafanaContainer
import org.testcontainers.containers.Network
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Tags("E2E")
class GrafanaDashboardE2ESpec :
    FunSpec({
        test("provisions the schema-v2 dashboard") {
            Network.newNetwork().use { network ->
                GrafanaContainer(network).use { grafana ->
                    val response =
                        HttpClient
                            .newHttpClient()
                            .send(
                                HttpRequest
                                    .newBuilder(URI.create("${grafana.dashboardApiUrl}/dto"))
                                    .GET()
                                    .build(),
                                HttpResponse.BodyHandlers.ofString(),
                            )

                    response.statusCode() shouldBe 200
                    val dashboardResource = Json.parseToJsonElement(response.body()).jsonObject
                    dashboardResource
                        .getValue("metadata")
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive.content shouldBe "syfo-budstikka"
                    assertGrafanaDashboardContract(dashboardResource.getValue("spec").jsonObject)
                }
            }
        }
    })
