package cc.tietz.fancontrolbackend

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::myApplicationModule).start(wait = true)
}

fun Application.myApplicationModule() {
    Database.initSchema()
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/frontend/config") {
            call.respond(Database.loadConfig())
        }
        post("/frontend/config") {
            Database.saveConfig(call.receive<PersistentConfig>())
            call.respond(Database.loadConfig())
        }
    }
}