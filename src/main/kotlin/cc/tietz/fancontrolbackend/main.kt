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
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::myApplicationModule).start(wait = true)
}

@Serializable
data class OutdoorSensorRequest(
    val temperature: Double,
    val relativeHumidity: Int,
)
@Serializable
data class OutdoorSensorResponse(
    val sleepDurationMilliseconds: Int,
)

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
        post("/outdoor") {
            val req = call.receive<OutdoorSensorRequest>()
            Database.saveOutdoor(Database.OutdoorMeasurement(Instant.now(), req.temperature, req.relativeHumidity))
            // todo: compute delay based on outdoor weather information
            val delay = Database.loadConfig().pollingRateSensorOutside ?: 5.seconds
            call.respond(OutdoorSensorResponse(delay.inWholeMilliseconds.toInt()))
        }
    }
}