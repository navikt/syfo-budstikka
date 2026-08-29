package no.nav.budstikka.e2e

import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import no.nav.budstikka.testsupport.projectRoot

class GrafanaDashboardContractSpec :
    FunSpec({
        test("dashboard queries and drilldowns keep the operational safety contract") {
            val dashboard =
                Json
                    .parseToJsonElement(
                        projectRoot()
                            .resolve("grafana/dashboards/syfo-budstikka.json")
                            .toFile()
                            .readText(),
                    ).jsonObject

            assertGrafanaDashboardContract(dashboard)
        }
    })
