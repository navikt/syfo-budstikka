package no.nav.budstikka.e2e

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
                    response.body() shouldContain """"name":"syfo-budstikka""""
                }
            }
        }
    })
