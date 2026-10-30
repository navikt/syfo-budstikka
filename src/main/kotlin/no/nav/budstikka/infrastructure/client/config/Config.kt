package no.nav.budstikka.infrastructure.client.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.application.delivery.ArbeidsgiversakConfig
import no.nav.budstikka.infrastructure.config.configFor
import no.nav.budstikka.infrastructure.config.validate

data class PdlConfig(
    val url: String,
    val scope: String,
    val behandlingsnummer: String,
)

fun ApplicationConfig.toPdlConfig() =
    with(configFor("pdl")) {
        PdlConfig(
            url = this("url"),
            scope = this("scope"),
            behandlingsnummer = this("behandlingsnummer"),
        ).validate { config ->
            buildList {
                if (config.url.isBlank()) add("pdl.url must be set (PDL_URL)")
                if (config.scope.isBlank()) add("pdl.scope must be set (PDL_SCOPE)")
                if (config.behandlingsnummer.isBlank()) add("pdl.behandlingsnummer must be set (PDL_BEHANDLINGSNUMMER)")
            }
        }
    }

data class DocumentDistributorConfig(
    val url: String,
    val scope: String,
)

fun ApplicationConfig.toDocumentDistributorConfig() =
    with(configFor("documentDistributor")) {
        DocumentDistributorConfig(
            url = this("url"),
            scope = this("scope"),
        ).validate { config ->
            buildList {
                if (config.url.isBlank()) add("documentDistributor.url must be set (DOCUMENT_DISTRIBUTOR_URL)")
                if (config.scope.isBlank()) add("documentDistributor.scope must be set (DOCUMENT_DISTRIBUTOR_SCOPE)")
            }
        }
    }

data class ArbeidsgiverNotifikasjonConfig(
    val url: String,
    val scope: String,
)

fun ApplicationConfig.toArbeidsgiverNotifikasjonConfig() =
    with(configFor("arbeidsgiverNotifikasjon")) {
        ArbeidsgiverNotifikasjonConfig(
            url = this("url"),
            scope = this("scope"),
        ).validate { config ->
            buildList {
                if (config.url.isBlank()) add("arbeidsgiverNotifikasjon.url must be set (ARBEIDSGIVER_NOTIFIKASJON_URL)")
                if (config.scope.isBlank()) add("arbeidsgiverNotifikasjon.scope must be set (ARBEIDSGIVER_NOTIFIKASJON_SCOPE)")
            }
        }
    }

data class KrrConfig(
    val url: String,
    val scope: String,
)

fun ApplicationConfig.toKrrConfig() =
    with(configFor("krr")) {
        KrrConfig(
            url = this("url"),
            scope = this("scope"),
        ).validate { config ->
            buildList {
                if (config.url.isBlank()) add("krr.url must be set (KRR_URL)")
                if (config.scope.isBlank()) add("krr.scope must be set (KRR_SCOPE)")
            }
        }
    }

data class NarmesteLederConfig(
    val url: String,
    val scope: String,
)

fun ApplicationConfig.toNarmesteLederConfig() =
    with(configFor("narmesteLeder")) {
        NarmesteLederConfig(
            url = this("url"),
            scope = this("scope"),
        ).validate { config ->
            buildList {
                if (config.url.isBlank()) add("narmesteLeder.url must be set (NARMESTELEDER_URL)")
                if (config.scope.isBlank()) add("narmesteLeder.scope must be set (NARMESTELEDER_SCOPE)")
            }
        }
    }

fun ApplicationConfig.toArbeidsgiversakConfig() =
    with(configFor("arbeidsgiversak")) {
        ArbeidsgiversakConfig(
            dineSykmeldteUrl = this("dineSykmeldteUrl"),
            pseudonymNokkel = this("pseudonymNokkel"),
        ).validate { config ->
            buildList {
                if (config.dineSykmeldteUrl.isBlank()) add("arbeidsgiversak.dineSykmeldteUrl must be set (BASE_URL_DINE_SYKMELDTE)")
                if (config.pseudonymNokkel.length < MIN_PSEUDONYM_KEY_LENGTH) {
                    add(
                        "arbeidsgiversak.pseudonymNokkel must be at least $MIN_PSEUDONYM_KEY_LENGTH characters (ARBEIDSGIVERSAK_PSEUDONYMNOKKEL)",
                    )
                }
            }
        }
    }

private const val MIN_PSEUDONYM_KEY_LENGTH = 32
