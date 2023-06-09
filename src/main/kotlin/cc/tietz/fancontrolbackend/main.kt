package cc.tietz.fancontrolbackend

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import kotlin.math.exp
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

@Serializable
data class IndoorSensorRequest(
    val temperature: Double,
    val relativeHumidity: Int,
    val windowOpen: Boolean,
)

@Serializable
data class IndoorSensorResponse(
    val sleepDurationMilliseconds: Int,
    val fanDutyCycle: Int,
)

data class DateRange(
    val start: Instant,
    val end: Instant,
) {
    fun toRange(): ClosedRange<Instant> = start..end
}

data class FetchRequest(
    val limit: Int?,
    val dateRange: DateRange?,
) {
    companion object {
        fun fromParameters(parameters: Parameters): FetchRequest {
            val limit = parameters["limit"]?.toIntOrNull()
            val start = parameters["start"]?.let(Instant::parse)
            val end = parameters["end"]?.let(Instant::parse)

            val dateRange = if (start != null && end != null) DateRange(start, end) else null
            return FetchRequest(limit, dateRange)
        }
    }
}

@Serializable
data class IndoorFetchResponse(
    val data: List<Measurement>,
) {
    @Serializable
    data class Measurement(
        val date: String,
        val temperature: Double,
        val relativeHumidity: Int,
        val absoluteHumidity: Double,
        val windowOpen: Boolean,
    )
}


@Serializable
data class OutdoorFetchResponse(
    val data: List<Measurement>,
) {
    @Serializable
    data class Measurement(
        val date: String,
        val temperature: Double,
        val relativeHumidity: Int,
        val absoluteHumidity: Double,
    )
}

private const val defaultLimit = 1000

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
        get("/outdoor") {
            val req = call.parameters.let(FetchRequest::fromParameters)
            val data = Database.loadOutdoor(req.dateRange?.toRange(), req.limit ?: defaultLimit)
            call.respond(OutdoorFetchResponse(data.map {
                OutdoorFetchResponse.Measurement(
                    it.time.toString(),
                    it.temperature,
                    it.relativeHumidity,
                    calculateAbsoluteHumidity(it.temperature, it.relativeHumidity),
                )
            }))
        }
        post("/outdoor") {
            val req = call.receive<OutdoorSensorRequest>()
            Database.saveOutdoor(Database.OutdoorMeasurement(Instant.now(), req.temperature, req.relativeHumidity))
            // todo: compute delay based on outdoor weather information
            val delay = Database.loadConfig().pollingRateSensorOutside ?: 5.seconds
            call.respond(OutdoorSensorResponse(delay.inWholeMilliseconds.toInt()))
        }
        get("/indoor") {
            val req = call.parameters.let(FetchRequest::fromParameters)
            val data = Database.loadIndoor(req.dateRange?.toRange(), req.limit ?: defaultLimit)
            call.respond(IndoorFetchResponse(data.map {
                IndoorFetchResponse.Measurement(
                    it.time.toString(),
                    it.temperature,
                    it.relativeHumidity,
                    calculateAbsoluteHumidity(it.temperature, it.relativeHumidity),
                    it.windowOpen,
                )
            }))
        }
        val lastSwitchValue = AtomicBoolean()
        post("/indoor") {
            val req = call.receive<IndoorSensorRequest>()
            Database.saveIndoor(
                Database.IndoorMeasurement(
                    Instant.now(),
                    req.temperature,
                    req.relativeHumidity,
                    req.windowOpen
                )
            )
            val config = Database.loadConfig()
            val delay = config.pollingRateSensorInside
            val lastOurdoorMeasurement = Database.loadOutdoor(timeRange = null, limit = 1).lastOrNull()
            val fanDutyCycle = when {
                req.windowOpen && !config.ignoreWindow -> 0
                lastOurdoorMeasurement == null -> 0
                shouldEnable(
                    config,
                    lastSwitchValue,
                    calculateAbsoluteHumidity(req.temperature, req.relativeHumidity),
                    calculateAbsoluteHumidity(lastOurdoorMeasurement.temperature, lastOurdoorMeasurement.relativeHumidity)
                ) -> 100

                else -> 0
            }
            val maxFanDutyCycle = getMaxDutyCycleIfNight(config.nightModeConfig)
            val finalFanDutyCycle = maxFanDutyCycle?.let { fanDutyCycle.coerceAtMost(it) } ?: fanDutyCycle
            call.respond(IndoorSensorResponse(delay.inWholeMilliseconds.toInt(), finalFanDutyCycle))
        }
    }
}

private fun calculateAbsoluteHumidity(temperature: Double, relativeHumidity: Int): Double {
    val saturationVaporPressure = 6.112 * exp((17.67 * temperature) / (temperature + 243.5))
    val actualVaporPressure = relativeHumidity * saturationVaporPressure / 100.0
    return 217 * actualVaporPressure / (temperature + 273.15)
}

private fun shouldEnable(config: PersistentConfig, lastSwitchValue: AtomicBoolean, insideAbsoluteHumidity: Double, outsideAbsoluteHumidity: Double): Boolean {
    val offset = (insideAbsoluteHumidity - outsideAbsoluteHumidity).absoluteValue
    if (offset < config.hysteresisOffset)  return lastSwitchValue.get()
    return (insideAbsoluteHumidity > outsideAbsoluteHumidity).also(lastSwitchValue::set)
}

fun getMaxDutyCycleIfNight(config: NightModeConfig): Int? {
    val currentHour = LocalTime.now().hour
    val startHour = config.startHour ?: return null
    val endHour = config.endHour ?: return null

    return config.maxDutyCycle?.takeIf {
        if (startHour <= endHour) currentHour in startHour..endHour
        else currentHour >= startHour || currentHour <= endHour
    }
}