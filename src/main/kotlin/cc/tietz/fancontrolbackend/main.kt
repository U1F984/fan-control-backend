package cc.tietz.fancontrolbackend

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::myApplicationModule).start(wait = true)
}

@Serializable
data class OutdoorSensorRequest(
    val temperature: Double,
    val relativeHumidity: Double,
    val battery: Double,
)

@Serializable
data class OutdoorSensorResponse(
    val sleepDurationMilliseconds: Int,
)

@Serializable
data class IndoorSensorRequest(
    val temperature: Double,
    val relativeHumidity: Double,
    val windowOpen: Boolean,
)

@Serializable
data class IndoorSensorResponse(
    val sleepDurationMilliseconds: Int,
    val fanDutyCycle: Int,
)

@Serializable
data class CurrentSateResponse(
    val fanDutyCycle: Int,
    val windowOpen: Boolean,
    val nightModeConfig: NightModeConfig?,
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
        val relativeHumidity: Double,
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
        val relativeHumidity: Double,
        val absoluteHumidity: Double,
        val battery: Double,
    )
}

private const val defaultLimit = 1000

fun Application.myApplicationModule() {
    Database.initSchema()
    install(ContentNegotiation) {
        json()
    }

    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Accept)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Origin)
        allowHeader(HttpHeaders.Referrer)
        allowHost("ecetin.dev", schemes = listOf("https"), subDomains = listOf("iot"))
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
                    it.battery,
                )
            }))
        }
        post("/outdoor") {
            val req = call.receive<OutdoorSensorRequest>()
            Database.saveOutdoor(
                Database.OutdoorMeasurement(
                    Instant.now(),
                    req.temperature,
                    req.relativeHumidity,
                    req.battery
                )
            )
            val config = Database.loadConfig()
            val delay = config.pollingRateSensorOutside?.milliseconds
                ?: WeatherApi.read()?.let { weather ->
                    val currentWeather = weather.current.main
                    val currentAbsoluteHumidity =
                        calculateAbsoluteHumidity(currentWeather.temp, currentWeather.humidity.toDouble())
                    val sensorAbsoluteHumidity = calculateAbsoluteHumidity(req.temperature, req.relativeHumidity)
                    if ((currentAbsoluteHumidity - sensorAbsoluteHumidity).absoluteValue < config.hysteresisOffset) {
                        val futureWeather = weather.forecast.list.first().main
                        val futureAbsoluteHumidity =
                            calculateAbsoluteHumidity(futureWeather.temp, futureWeather.humidity.toDouble())
                        if ((futureAbsoluteHumidity - currentAbsoluteHumidity).absoluteValue < config.hysteresisOffset) {
                            return@let 30.minutes
                        }
                    }
                    null
                } ?: 10.minutes
            call.respond(OutdoorSensorResponse(delay.toInt(DurationUnit.MILLISECONDS)))
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
        get("/weather") {
            val weather = WeatherApi.read()
            if (weather == null) {
                call.respond(500)
            } else {
                call.respond(weather)
            }
        }
        val lastFanDutyCycle = AtomicInteger()
        val lastWindowOpen = AtomicBoolean()
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
            lastWindowOpen.set(req.windowOpen)
            val config = Database.loadConfig()
            val delay = config.pollingRateSensorInside
            val lastOurdoorMeasurement = Database.loadOutdoor(timeRange = null, limit = 1).lastOrNull()
            val fanDutyCycle = when {
                req.windowOpen && !config.ignoreWindow -> 0
                lastOurdoorMeasurement == null -> 0
                shouldEnable(
                    config,
                    lastFanDutyCycle.get() > 0,
                    calculateAbsoluteHumidity(req.temperature, req.relativeHumidity),
                    calculateAbsoluteHumidity(
                        lastOurdoorMeasurement.temperature,
                        lastOurdoorMeasurement.relativeHumidity
                    )
                ) -> 100

                else -> 0
            }
            val maxFanDutyCycle = getMaxDutyCycleIfNight(config.nightModeConfig)
            val finalFanDutyCycle = maxFanDutyCycle?.let { fanDutyCycle.coerceAtMost(it) } ?: fanDutyCycle
            lastFanDutyCycle.set(finalFanDutyCycle)
            call.respond(IndoorSensorResponse(delay?.toInt() ?: 5000, finalFanDutyCycle))
        }
        get("/state") {
            val config = Database.loadConfig()
            call.respond(CurrentSateResponse(lastFanDutyCycle.get(), lastWindowOpen.get(), config.nightModeConfig))
        }
    }
}

private fun calculateAbsoluteHumidity(temperature: Double, relativeHumidity: Double): Double {
    val saturationVaporPressure = 6.112 * exp((17.67 * temperature) / (temperature + 243.5))
    val actualVaporPressure = relativeHumidity * saturationVaporPressure / 100.0
    return 217 * actualVaporPressure / (temperature + 273.15)
}

private fun shouldEnable(
    config: PersistentConfig,
    lastSwitchValue: Boolean,
    insideAbsoluteHumidity: Double,
    outsideAbsoluteHumidity: Double
): Boolean {
    val offset = (insideAbsoluteHumidity - outsideAbsoluteHumidity).absoluteValue
    if (offset < config.hysteresisOffset) return lastSwitchValue
    return insideAbsoluteHumidity > outsideAbsoluteHumidity
}

fun getMaxDutyCycleIfNight(config: NightModeConfig?): Int? {
    val currentHour = LocalTime.now().hour
    val startHour = config?.startHour ?: return null
    val endHour = config.endHour ?: return null

    return config.maxDutyCycle?.takeIf {
        if (startHour <= endHour) currentHour in startHour..endHour
        else currentHour >= startHour || currentHour <= endHour
    }
}