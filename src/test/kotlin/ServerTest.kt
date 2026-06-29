package no.nav.syfo

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.*
import kotlin.test.*

class ServerTest {
    @Test
    fun `test root endpoint`() =
        testApplication {
            // testApplication does not auto-load modules from application.yaml in Ktor 3.x,
            // so load the module explicitly
            application {
                configureRouting()
            }
            // verify server root returns 200
            assertEquals(HttpStatusCode.OK, client.get("/").status)
        }
}
